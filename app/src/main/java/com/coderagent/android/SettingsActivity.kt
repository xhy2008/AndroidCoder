package com.coderagent.android

import android.os.Bundle

/** 设置页：复用 SetupActivity 的表单与容器管理逻辑 */
class SettingsActivity : SetupActivity() {

    companion object {
        const val EXTRA_FROM_SETTINGS = "from_settings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra(EXTRA_FROM_SETTINGS, true)
        super.onCreate(savedInstanceState)
    }
}
