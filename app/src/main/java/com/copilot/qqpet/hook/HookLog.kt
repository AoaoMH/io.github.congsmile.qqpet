package com.copilot.qqpet.hook

import de.robv.android.xposed.XposedBridge

/**
 * 统一日志输出控制器：默认对 LSPosed 框架日志和 logcat 保持完全静默，
 * 仅当用户主动在设置中开启「调试模式日志」时才向 XposedBridge 打印。
 */
object HookLog {

    @Volatile
    var isDebugEnabled: Boolean = false

    fun log(tag: String, msg: String) {
        if (isDebugEnabled) {
            try {
                XposedBridge.log("[$tag] $msg")
            } catch (_: Throwable) {}
        }
    }

    fun log(msg: String) {
        if (isDebugEnabled) {
            try {
                XposedBridge.log(msg)
            } catch (_: Throwable) {}
        }
    }
}
