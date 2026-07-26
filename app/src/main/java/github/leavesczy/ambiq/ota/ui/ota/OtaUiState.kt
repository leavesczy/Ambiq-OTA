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
    val progressPercent: Int = 0,
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
}
