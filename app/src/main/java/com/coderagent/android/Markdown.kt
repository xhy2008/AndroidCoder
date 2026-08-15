package com.coderagent.android

import android.content.Context
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/** Markwon 单例（markdown → Spanned 渲染，深色主题沿用 TextView 默认配色） */
object Markdown {
    @Volatile
    private var instance: Markwon? = null

    fun get(ctx: Context): Markwon = instance ?: synchronized(this) {
        instance ?: Markwon.builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(ctx))
            .usePlugin(LinkifyPlugin.create())
            .build()
            .also { instance = it }
    }
}
