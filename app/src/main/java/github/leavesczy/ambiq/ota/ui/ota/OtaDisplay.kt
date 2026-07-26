package github.leavesczy.ambiq.ota.ui.ota

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import github.leavesczy.ambiq.ota.ota.OtaPhase

val OtaPhase.displayLabel: String
    get() = when (this) {
        OtaPhase.Idle -> "空闲"
        OtaPhase.Preparing -> "准备连接"
        OtaPhase.SendingHeader -> "发送固件头"
        OtaPhase.SendingData -> "传输数据"
        OtaPhase.Verifying -> "校验固件"
        OtaPhase.Resetting -> "设备复位"
        OtaPhase.Success -> "升级成功"
        OtaPhase.Failed -> "升级失败"
        OtaPhase.Cancelled -> "已取消"
    }

@Composable
fun OtaPhase.accentColor(scheme: ColorScheme): Color {
    return when (this) {
        OtaPhase.Success -> scheme.primary
        OtaPhase.Failed, OtaPhase.Cancelled -> scheme.error
        OtaPhase.Preparing,
        OtaPhase.SendingHeader,
        OtaPhase.SendingData,
        OtaPhase.Verifying,
        OtaPhase.Resetting -> scheme.tertiary

        OtaPhase.Idle -> scheme.onSurfaceVariant
    }
}

fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.2f MB".format(mb)
}
