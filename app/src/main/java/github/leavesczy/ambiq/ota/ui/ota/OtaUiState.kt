package github.leavesczy.ambiq.ota.ui.ota

import github.leavesczy.ambiq.ota.ota.OtaPhase

/**
 * OTA 升级页不可变 UI 状态。
 */
data class OtaUiState(
    val firmwareName: String? = null,
    val firmwarePath: String? = null,
    val firmwareSizeBytes: Long? = null,
    val phase: OtaPhase = OtaPhase.Idle,
    val progressPercent: Double = 0.0,
    val statusMessage: String = "",
    val errorDetail: String? = null,
    val isRunning: Boolean = false,
    val isSucceeded: Boolean = false,
    val elapsedMs: Long = 0L,
    val toastMessage: String? = null
) {
    val canStart: Boolean
        get() = firmwarePath != null && !isRunning

    val canPickFirmware: Boolean
        get() = !isRunning

    /** 界面展示用整数进度（0–100）。 */
    val progressPercentDisplay: Int
        get() = progressPercent.toInt().coerceIn(minimumValue = 0, maximumValue = 100)
}
