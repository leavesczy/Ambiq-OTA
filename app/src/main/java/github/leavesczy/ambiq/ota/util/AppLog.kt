package github.leavesczy.ambiq.ota.util

import android.util.Log
import github.leavesczy.ambiq.ota.BuildConfig

/**
 * Debug 构建下输出日志；Release 自动关闭。
 */
inline fun appLog(tag: String = "AmbiqOTA", log: () -> Any?) {
    if (!BuildConfig.DEBUG) {
        return
    }
    val logText = log()?.toString()
    if (!logText.isNullOrBlank()) {
        Log.d(tag, logText)
    }
}
