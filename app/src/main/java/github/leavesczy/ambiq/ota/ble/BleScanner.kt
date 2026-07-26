package github.leavesczy.ambiq.ota.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.annotation.RequiresPermission
import github.leavesczy.ambiq.ota.ble.BleScanner.Companion.AUTO_STOP_MS
import github.leavesczy.ambiq.ota.util.appLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于系统 [BluetoothLeScanner] 的 BLE 扫描器。
 *
 * - 扫描模式：`SCAN_MODE_LOW_LATENCY`
 * - 过滤器：空（展示附近全部 BLE 设备）
 * - 自动停止：启动后 [AUTO_STOP_MS]
 * - 回调保证在主线程派发
 */
class BleScanner(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val listener: Listener
) {

    interface Listener {
        fun onDeviceFound(device: ScannedDevice)
        fun onScanFailed(message: String)
        fun onScanStopped()
    }

    companion object {
        private const val AUTO_STOP_MS = 5 * 60 * 1000L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter = bluetoothManager?.adapter

    private val bluetoothLeScanner: BluetoothLeScanner?
        get() {
            val adapter = bluetoothAdapter
            return if (adapter == null || !adapter.isEnabled) {
                null
            } else {
                adapter.bluetoothLeScanner
            }
        }

    private val seenAddresses = ConcurrentHashMap.newKeySet<String>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var autoStopJob: Job? = null

    private val scanning = AtomicBoolean(false)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val macAddress = device.address?.trim().orEmpty()
            if (macAddress.isEmpty()) {
                return
            }
            val scanName = result.scanRecord?.deviceName?.trim().orEmpty()
            val bondedName = try {
                device.name?.trim().orEmpty()
            } catch (_: SecurityException) {
                ""
            }
            // 广播名与系统缓存名皆空则忽略
            val name = when {
                scanName.isNotEmpty() -> scanName
                bondedName.isNotEmpty() -> bondedName
                else -> return
            }
            val isNew = seenAddresses.add(macAddress)
            val scanned = ScannedDevice(
                name = name,
                macAddress = macAddress,
                rssi = result.rssi
            )
            if (isNew) {
                appLog { "scan new: $scanned" }
            }
            mainHandler.post {
                listener.onDeviceFound(device = scanned)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning.set(false)
            cancelAutoStop()
            val message = scanErrorMessage(errorCode = errorCode)
            appLog { "onScanFailed: $errorCode" }
            mainHandler.post {
                listener.onScanFailed(message = message)
            }
        }
    }

    fun isScanning(): Boolean = scanning.get()

    @SuppressLint("MissingPermission")
    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        ]
    )
    fun startScan() {
        if (!scanning.compareAndSet(false, true)) {
            return
        }
        if (!BluetoothPermissionHelper.isBleSupported(context = context)) {
            failAndReset(message = "设备不支持蓝牙低功耗")
            return
        }
        if (!BluetoothPermissionHelper.isBluetoothEnabled(context = context)) {
            failAndReset(message = "请先开启蓝牙")
            return
        }
        if (!BluetoothPermissionHelper.hasBluetoothPermissions(context = context)) {
            failAndReset(message = "缺少蓝牙权限")
            return
        }
        val scanner = bluetoothLeScanner
        if (scanner == null) {
            failAndReset(message = "蓝牙不可用")
            return
        }
        seenAddresses.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(emptyList(), settings, scanCallback)
            autoStopJob = coroutineScope.launch {
                delay(timeMillis = AUTO_STOP_MS)
                stopScan()
            }
            appLog { "BLE 扫描已开始" }
        } catch (e: Exception) {
            failAndReset(message = e.message ?: "启动扫描失败")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning.compareAndSet(true, false)) {
            mainHandler.post { listener.onScanStopped() }
            return
        }
        cancelAutoStop()
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            // ignore
        }
        appLog { "BLE 扫描已停止" }
        mainHandler.post { listener.onScanStopped() }
    }

    fun release() {
        stopScan()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun failAndReset(message: String) {
        scanning.set(false)
        cancelAutoStop()
        mainHandler.post {
            listener.onScanFailed(message = message)
        }
    }

    private fun cancelAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = null
    }

    private fun scanErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已在进行"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "扫描注册失败"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "设备不支持 BLE 扫描"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "扫描内部错误"
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "硬件资源不足"
            else -> "扫描失败 ($errorCode)"
        }
    }

}
