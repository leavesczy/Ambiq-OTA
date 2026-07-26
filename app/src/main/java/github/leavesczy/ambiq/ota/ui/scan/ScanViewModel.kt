package github.leavesczy.ambiq.ota.ui.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import github.leavesczy.ambiq.ota.ble.BleScanner
import github.leavesczy.ambiq.ota.ble.BluetoothPermissionHelper
import github.leavesczy.ambiq.ota.ble.ScannedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 蓝牙扫描页 ViewModel：管理权限状态、扫描生命周期与设备列表。
 */
class ScanViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        ScanUiState(
            hasPermission = BluetoothPermissionHelper.hasBluetoothPermissions(application)
        )
    )
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val deviceMap = LinkedHashMap<String, ScannedDevice>()

    private val scanner = BleScanner(
        context = application.applicationContext,
        coroutineScope = viewModelScope,
        listener = object : BleScanner.Listener {
            override fun onDeviceFound(device: ScannedDevice) {
                deviceMap[device.macAddress] = device
                publishDevices()
            }

            override fun onScanFailed(message: String) {
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = message,
                        toastMessage = message
                    )
                }
            }

            override fun onScanStopped() {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    )

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasPermission = granted) }
        if (granted) {
            startScan()
        } else {
            _uiState.update {
                it.copy(
                    errorMessage = "需要蓝牙权限才能扫描设备",
                    toastMessage = "需要蓝牙权限才能扫描设备"
                )
            }
        }
    }

    fun onBluetoothEnableResult(enabled: Boolean) {
        if (enabled) {
            startScan()
        } else {
            _uiState.update {
                it.copy(errorMessage = "请开启蓝牙后再扫描")
            }
        }
    }

    fun startScan() {
        val context = getApplication<Application>()
        if (!BluetoothPermissionHelper.hasBluetoothPermissions(context)) {
            _uiState.update { it.copy(hasPermission = false) }
            return
        }
        if (!BluetoothPermissionHelper.isBluetoothEnabled(context)) {
            return
        }
        deviceMap.clear()
        publishDevices()
        _uiState.update {
            it.copy(
                isScanning = true,
                errorMessage = null,
                hasPermission = true
            )
        }
        scanner.startScan()
    }

    fun stopScan() {
        scanner.stopScan()
    }

    fun toggleScan() {
        if (_uiState.value.isScanning) {
            stopScan()
        } else {
            startScan()
        }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun needsPermissionRequest(): Boolean {
        return !BluetoothPermissionHelper.hasBluetoothPermissions(getApplication())
    }

    fun needsBluetoothEnable(): Boolean {
        val context = getApplication<Application>()
        return BluetoothPermissionHelper.hasBluetoothPermissions(context) &&
                !BluetoothPermissionHelper.isBluetoothEnabled(context)
    }

    override fun onCleared() {
        scanner.release()
        super.onCleared()
    }

    private fun publishDevices() {
        val sorted = deviceMap.values.sortedBy { it.name.lowercase() }
        _uiState.update { it.copy(devices = sorted) }
    }

}
