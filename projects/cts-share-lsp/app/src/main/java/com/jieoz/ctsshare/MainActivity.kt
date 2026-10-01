package com.jieoz.ctsshare

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView

/** Status page only. The module has no settings. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics
        ).toInt()
        val status = if (isModuleActive()) "模块状态：已激活" else "模块状态：未激活（请在 LSPosed 中启用）"
        setContentView(TextView(this).apply {
            setPadding(pad, pad * 2, pad, pad)
            textSize = 16f
            text = buildString {
                appendLine(status)
                appendLine()
                appendLine("作用域：Google（com.google.android.googlequicksearchbox）")
                appendLine()
                appendLine("用法：启用后强制停止 Google 应用，长按导航栏打开圈选即搜，圈出区域后点“分享”。")
                appendLine()
                append("图片只写入 Google 应用私有缓存，十分钟后自动删除，不进相册。")
            }
        })
    }

    /** Replaced with `true` by the module hook when LSPosed loads us into this app. */
    fun isModuleActive(): Boolean = false
}
