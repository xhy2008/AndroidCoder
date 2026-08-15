package com.coderagent.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.coderagent.android.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val items = mutableListOf<ChatItem>()
    private lateinit var adapter: ChatAdapter
    private var agentJob: Job? = null
    private var currentAssistant: ChatItem.Assistant? = null
    private var currentThinking: ChatItem.Thinking? = null
    private var lastRunning: Boolean? = null

    /** 工具执行中实时输出的显示上限（字符），防止 UI 无限增长 */
    private val MAX_TOOL_LIVE = 20000

    /** 流式刷新节流：模型 token 高频到达时合并为 60ms 一次 notify+滚动，避免动画频繁被打断 */
    private val uiHandler = Handler(Looper.getMainLooper())
    private var refreshScheduled = false
    private var pendingRefreshIndex = -1

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 保活服务状态变化监听（同进程回调，即时可靠，替代系统广播） */
    private val keepAliveListener: () -> Unit = {
        runOnUiThread { updateStatus() }
    }

    private var inputExpanded = false

    /** 会话（上下文）管理：当前会话 id + 会话列表 */
    private var currentSessionId: String? = null
    private val sessionList = mutableListOf<SessionStore.SessionMeta>()
    private lateinit var sessionAdapter: SessionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        // 左上角菜单按钮：点击滑出会话边栏
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_menu_hamburger)
        val toggle = ActionBarDrawerToggle(
            this, binding.drawer, binding.toolbar,
            R.string.drawer_open, R.string.drawer_close
        )
        binding.drawer.addDrawerListener(toggle)
        toggle.syncState()

        if (!Config.isConfigured(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        adapter = ChatAdapter(items)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        // 关闭 item 变更动画：流式输出高频更新时动画互相打断会导致渲染错位，直接同步刷新
        binding.recycler.itemAnimator = null

        setupSessionDrawer()
        initSession()

        binding.btnSend.setOnClickListener {
            // 执行中：按钮变为"停止"，点击终止输出
            if (agentJob?.isActive == true) {
                AgentEngine.cancelCurrent()
                agentJob?.cancel()
                Toast.makeText(this, R.string.stop_agent, Toast.LENGTH_SHORT).show()
            } else {
                send()
            }
        }
        binding.btnExpand.setOnClickListener { toggleInputExpand() }

        requestNotifPermissionIfNeeded()

        // 容器已安装时自动启动保活服务，无需手动点菜单
        if (ContainerRuntime.isInstalled(this) && !CoderAgentService.isRunning()) {
            CoderAgentService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        CoderAgentService.addStateListener(keepAliveListener)
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        CoderAgentService.removeStateListener(keepAliveListener)
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ---------- 会话（上下文）管理 ----------

    private fun setupSessionDrawer() {
        binding.sessionDrawer.sessionList.layoutManager = LinearLayoutManager(this)
        sessionAdapter = SessionAdapter(
            sessionList,
            onClick = { switchSession(it) },
            onLongClick = { confirmDeleteSession(it) }
        )
        binding.sessionDrawer.sessionList.adapter = sessionAdapter
        binding.sessionDrawer.sessionList.itemAnimator = null
        binding.sessionDrawer.btnNewSession.setOnClickListener { newSession() }
    }

    /** 启动时加载最近会话；无历史则新建 */
    private fun initSession() {
        sessionList.clear()
        sessionList.addAll(SessionStore.list(this))
        val last = sessionList.firstOrNull()
        if (last != null) {
            currentSessionId = last.id
            SessionStore.load(this, last.id)?.let { loaded ->
                items.clear()
                items.addAll(loaded)
                adapter.notifyDataSetChanged()
            }
        } else {
            currentSessionId = SessionStore.newId()
        }
        refreshSessionList()
    }

    /** 将当前上下文落盘（任务完成/打断/切换/新建时调用） */
    private fun saveCurrentSession() {
        if (items.isEmpty()) return
        val id = currentSessionId ?: SessionStore.newId().also { currentSessionId = it }
        SessionStore.save(this, id, deriveTitle(), items)
        refreshSessionList()
    }

    private fun deriveTitle(): String {
        val first = items.filterIsInstance<ChatItem.User>().firstOrNull()?.text.orEmpty().trim()
        return first.take(20).ifBlank { "未命名对话" }
    }

    private fun switchSession(meta: SessionStore.SessionMeta) {
        if (agentJob?.isActive == true) {
            Toast.makeText(this, R.string.session_busy, Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrentSession()
        currentSessionId = meta.id
        items.clear()
        SessionStore.load(this, meta.id)?.let { items.addAll(it) }
        adapter.notifyDataSetChanged()
        binding.drawer.closeDrawer(GravityCompat.START)
    }

    private fun newSession() {
        if (agentJob?.isActive == true) {
            Toast.makeText(this, R.string.session_busy, Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrentSession()
        currentSessionId = SessionStore.newId()
        items.clear()
        adapter.notifyDataSetChanged()
        binding.drawer.closeDrawer(GravityCompat.START)
    }

    private fun confirmDeleteSession(meta: SessionStore.SessionMeta) {
        AlertDialog.Builder(this)
            .setTitle(R.string.session_delete)
            .setMessage(R.string.session_delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                SessionStore.delete(this, meta.id)
                if (meta.id == currentSessionId) {
                    currentSessionId = null
                    items.clear()
                    adapter.notifyDataSetChanged()
                }
                refreshSessionList()
            }
            .show()
    }

    private fun refreshSessionList() {
        sessionList.clear()
        sessionList.addAll(SessionStore.list(this))
        sessionAdapter.notifyDataSetChanged()
    }

    /** 会话列表适配器：点击切换、长按删除 */
    private inner class SessionAdapter(
        private val data: List<SessionStore.SessionMeta>,
        private val onClick: (SessionStore.SessionMeta) -> Unit,
        private val onLongClick: (SessionStore.SessionMeta) -> Unit
    ) : RecyclerView.Adapter<SessionAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            private val tvTitle: TextView = v.findViewById(R.id.tv_title)
            private val tvTime: TextView = v.findViewById(R.id.tv_time)

            fun bind(meta: SessionStore.SessionMeta) {
                tvTitle.text = meta.title
                tvTime.text = SessionStore.formatTime(meta.updatedAt)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.session_item, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val meta = data[position]
            holder.bind(meta)
            holder.itemView.setOnClickListener { onClick(meta) }
            holder.itemView.setOnLongClickListener {
                onLongClick(meta)
                true
            }
        }

        override fun getItemCount(): Int = data.size
    }

    private fun updateStatus() {
        val cfg = Config.load(this)
        val installed = ContainerRuntime.isInstalled(this)
        val running = CoderAgentService.isRunning()
        binding.statusText.text = when {
            !installed -> getString(R.string.status_not_installed)
            running -> getString(R.string.container_running_notif_text)
            else -> getString(R.string.status_installed)
        }
        binding.statusDot.setColorFilter(
            when {
                !installed -> ContextCompat.getColor(this, R.color.status_err)
                running -> ContextCompat.getColor(this, R.color.status_ok)
                else -> ContextCompat.getColor(this, R.color.status_warn)
            }
        )
        // 运行状态变化时才重建菜单（菜单标题随运行状态切换，避免 2s 轮询频繁重建）
        if (running != lastRunning) {
            lastRunning = running
            invalidateOptionsMenu()
        }
    }

    private fun send() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (agentJob?.isActive == true) {
            Toast.makeText(this, "当前任务执行中，请先停止或等待", Toast.LENGTH_SHORT).show()
            return
        }
        binding.input.text?.clear()

        items += ChatItem.User(text)
        adapter.notifyItemInserted(items.size - 1)
        scrollToBottom()

        val cfg = Config.load(this)
        if (!ContainerRuntime.isInstalled(this)) {
            items += ChatItem.Assistant("容器尚未安装，请先在设置中完成容器安装。")
            adapter.notifyItemInserted(items.size - 1)
            return
        }

        agentJob = lifecycleScope.launch {
            try {
                AgentEngine.run(this@MainActivity, cfg, text) { event ->
                    runOnUiThread { handleEvent(event) }
                }
            } finally {
                agentJob = null
                currentAssistant = null
                sealThinking()
                updateSendButton()
                // 任务完成或打断后自动保存当前上下文
                saveCurrentSession()
            }
        }
        updateSendButton()
    }

    /** 发送/停止按钮状态：执行中显示"停止"，空闲显示"发送" */
    private fun updateSendButton() {
        val running = agentJob?.isActive == true
        binding.btnSend.text = getString(if (running) R.string.btn_stop else R.string.btn_send)
    }

    /** 输入框展开/收起：展开时铺满到顶端（隐藏发送按钮），展开图标保留以便收回 */
    private fun toggleInputExpand() {
        inputExpanded = !inputExpanded
        if (inputExpanded) {
            binding.recycler.visibility = View.GONE
            binding.btnSend.visibility = View.GONE
            // 底部栏占满整个屏幕高度
            binding.inputRow.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            // 输入框铺满底部栏（全新 LayoutParams 确保约束求解重新生效）
            binding.input.layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
            binding.input.maxLines = 9999
        } else {
            binding.recycler.visibility = View.VISIBLE
            binding.btnSend.visibility = View.VISIBLE
            binding.inputRow.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            binding.input.layoutParams = ConstraintLayout.LayoutParams(
                0,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToStart = R.id.btn_send
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                marginStart = (6 * resources.displayMetrics.density).toInt()
            }
            binding.input.maxLines = 4
        }
        // 展开图标保持可见，旋转 180° 指示"收起"
        binding.btnExpand.rotation = if (inputExpanded) 180f else 0f
        binding.root.requestLayout()
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            // 思考过程：实时追加到"思考过程"卡片（流式期间展开，结束自动折叠）
            is AgentEvent.ReasoningDelta -> {
                val t = currentThinking ?: ChatItem.Thinking("", expanded = true).also {
                    currentThinking = it
                    items += it
                    adapter.notifyItemInserted(items.size - 1)
                }
                t.text += event.text
                scheduleStreamRefresh(items.indexOfLast { it === t })
            }
            // 回答正文：实时追加到当前 AI 气泡
            is AgentEvent.TextDelta -> {
                // 思考已结束进入正文，立即折叠思考卡（避免等下一事件才折叠的延迟）
                sealThinking()
                // 模型在工具调用前可能发出纯空白 content delta（如换行），
                // 尚无正文气泡时忽略，避免出现内容为空的气泡
                val item = if (currentAssistant == null && event.text.isBlank()) null
                else currentAssistant ?: ChatItem.Assistant("").also {
                    currentAssistant = it
                    items += it
                    adapter.notifyItemInserted(items.size - 1)
                }
                if (item != null) {
                    item.text += event.text
                    scheduleStreamRefresh(items.indexOfLast { it === item })
                }
            }
            // 每轮流结束：收尾（折叠思考卡，封口气泡）
            is AgentEvent.TextDone -> {
                if (currentAssistant == null && event.text.isNotBlank()) {
                    items += ChatItem.Assistant(event.text)
                    adapter.notifyItemInserted(items.size - 1)
                }
                currentAssistant = null
                sealThinking()
                scrollToBottom()
            }
            is AgentEvent.ToolStart -> {
                sealThinking()
                items += ChatItem.ToolCallItem(event.name, event.argsSummary)
                adapter.notifyItemInserted(items.size - 1)
                scrollToBottom()
            }
            // 工具实时输出：追加到当前执行中的工具卡
            is AgentEvent.ToolDelta -> {
                val idx = items.indexOfLast { it is ChatItem.ToolCallItem && !it.done }
                android.util.Log.d("ToolStream", "ui toolDelta idx=$idx len=${event.text.length}")
                if (idx >= 0) {
                    val t = items[idx] as ChatItem.ToolCallItem
                    if (t.result.length < MAX_TOOL_LIVE) t.result += event.text
                    scheduleStreamRefresh(idx)
                }
            }
            is AgentEvent.ToolResult -> {
                val idx = items.indexOfLast { it is ChatItem.ToolCallItem && !it.done }
                if (idx >= 0) {
                    val t = items[idx] as ChatItem.ToolCallItem
                    t.result = event.result
                    t.done = true
                    t.ok = !event.result.startsWith("__ERR__")
                    adapter.notifyItemChanged(idx)
                    scrollToBottom()
                }
            }
            // 本轮统计：追加到最后一个 AI 气泡之后
            is AgentEvent.RoundStats -> {
                items += ChatItem.Meta(buildStats(event))
                adapter.notifyItemInserted(items.size - 1)
                scrollToBottom()
            }
            is AgentEvent.Error -> {
                currentAssistant = null
                sealThinking()
                items += ChatItem.Assistant("⚠️ ${event.message}")
                adapter.notifyItemInserted(items.size - 1)
                scrollToBottom()
            }
            is AgentEvent.LimitReached -> {
                currentAssistant = null
                sealThinking()
                items += ChatItem.Assistant(event.text)
                adapter.notifyItemInserted(items.size - 1)
                scrollToBottom()
            }
        }
    }

    /** 思考结束：折叠当前思考卡并保留在列表中，后续思考开新卡 */
    private fun sealThinking() {
        val t = currentThinking ?: return
        t.expanded = false
        adapter.notifyItemChanged(items.indexOfLast { it === t })
        currentThinking = null
    }

    private fun buildStats(s: AgentEvent.RoundStats): String {
        val secs = String.format("%.1f", s.elapsedMs / 1000.0)
        return "本轮：输入 ${String.format("%,d", s.totalInput)} · 输出 ${String.format("%,d", s.totalOutput)} · 缓存命中 ${String.format("%,d", s.cacheHit)} · 用时 ${secs}s"
    }

    /** 流式刷新节流：合并高频 delta，60ms 内只刷新一次；仅当用户接近底部时自动滚动 */
    private fun scheduleStreamRefresh(index: Int) {
        pendingRefreshIndex = maxOf(pendingRefreshIndex, index)
        if (refreshScheduled) return
        refreshScheduled = true
        uiHandler.postDelayed({
            refreshScheduled = false
            val idx = pendingRefreshIndex
            pendingRefreshIndex = -1
            if (idx >= 0 && idx < items.size) adapter.notifyItemChanged(idx)
            // 用户正在阅读历史时不要强制滚动，仅在贴底时跟随最新输出
            if (isNearBottom()) scrollToBottom()
        }, 60)
    }

    /** 是否接近列表底部（最后可见项距底部 3 项以内） */
    private fun isNearBottom(): Boolean {
        val lm = binding.recycler.layoutManager as? LinearLayoutManager ?: return true
        val last = lm.findLastCompletelyVisibleItemPosition()
        return last >= lm.itemCount - 3
    }

    private fun scrollToBottom() {
        binding.recycler.post {
            if (items.isNotEmpty()) {
                // Int.MIN_VALUE 表示尽量滚动到该项底部（AI 长气泡时保证最新文本贴底可见）
                (binding.recycler.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(items.size - 1, Int.MIN_VALUE)
                    ?: binding.recycler.scrollToPosition(items.size - 1)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val item = menu.findItem(R.id.action_container)
        item.title = if (CoderAgentService.isRunning()) {
            getString(R.string.menu_container_off)
        } else {
            getString(R.string.menu_container_on)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                binding.drawer.openDrawer(GravityCompat.START)
                true
            }
            R.id.action_container -> {
                if (CoderAgentService.isRunning()) {
                    CoderAgentService.stop(this)
                } else {
                    CoderAgentService.start(this)
                }
                updateStatus()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_files -> {
                if (ContainerRuntime.isInstalled(this)) {
                    startActivity(Intent(this, FileBrowserActivity::class.java))
                } else {
                    Toast.makeText(this, R.string.fb_not_installed, Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_terminal -> {
                if (ContainerRuntime.isInstalled(this)) {
                    startActivity(Intent(this, TerminalActivity::class.java))
                } else {
                    Toast.makeText(this, R.string.terminal_not_installed, Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        agentJob?.cancel()
        super.onDestroy()
    }
}
