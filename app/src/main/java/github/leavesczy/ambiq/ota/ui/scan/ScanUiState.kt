package github.leavesczy.ambiq.ota.ui.scan

import github.leavesczy.ambiq.ota.ble.ScannedDevice

/**
 * 扫描页不可变 UI 状态。
 */
data class ScanUiState(
    val devices: List<ScannedDevice> = emptyList(),
    val isScanning: Boolean = false,
    val hasPermission: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null
)
