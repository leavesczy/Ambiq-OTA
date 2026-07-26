package github.leavesczy.ambiq.ota.ota

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import github.leavesczy.ambiq.ota.util.appLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * AMOTA OTA 入口：独立 GATT 会话。
 *
 * 1. [setListener] 注册进度回调
 * 2. [startOta] 连接设备并完成 HEADER → DATA → VERIFY → RESET
 * 3. [cancelOta] / [close] 取消或释放资源
 */
@SuppressLint("MissingPermission")
class OtaClient(private val context: Context) {

    private companion object {
        private const val DEFAULT_PAYLOAD = 20
        private const val REQUESTED_MTU = 247
        private const val MAX_ATT_PAYLOAD = 237
        private const val WRITE_TIMEOUT_MS = 3000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val bluetoothAdapter = bluetoothManager.adapter

    private val listenerRef = AtomicReference<OtaClientListener?>(null)

    private val connectionStateRef = AtomicReference(OtaConnectionState.Disconnected)

    private val progressRef = AtomicReference(OtaProgress())

    private val isOtaRunning = AtomicBoolean(false)

    private val isWaitingForReady = AtomicBoolean(false)

    private val setupErrorRef = AtomicReference<OtaError?>(null)

    private var readySemaphore = Semaphore(0)

    private val updater = OtaUpdater(onProgress = ::emitProgress)

    private var gatt: BluetoothGatt? = null

    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private var writeSemaphore = Semaphore(0)

    private var lastWriteStatus = BluetoothGatt.GATT_FAILURE

    private val attPayloadSize = AtomicInteger(DEFAULT_PAYLOAD)

    private val isServicesDiscoveryStarted = AtomicBoolean(false)

    private val isNotificationSetupStarted = AtomicBoolean(false)

    private val isMtuRequestStarted = AtomicBoolean(false)

    private val isReadyMarked = AtomicBoolean(false)

    private val lastTransportError = AtomicReference<OtaError?>(null)

    private val transport = object : OtaTransport {
        override fun write(data: ByteArray): Boolean {
            return writeRx(data = data)
        }

        override fun maxPayloadSize(): Int {
            return attPayloadSize.get()
        }

        override fun consumeLastWriteError(): OtaError? {
            return lastTransportError.getAndSet(null)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionStateRef.set(OtaConnectionState.Disconnected)
                failSetup(
                    error = OtaError.ConnectFailed(detail = "status=$status")
                )
                closeGatt()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    appLog {
                        "已连接"
                    }
                    connectionStateRef.set(OtaConnectionState.Connected)
                    resetSetupFlags()
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    startServiceDiscovery(gatt = gatt)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    appLog {
                        "连接已断开"
                    }
                    updater.cancel()
                    connectionStateRef.set(OtaConnectionState.Disconnected)
                    if (isWaitingForReady.get()) {
                        failSetup(
                            error = OtaError.ConnectFailed(detail = "disconnected before ready")
                        )
                    }
                    closeGatt()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 3) {
                val payload = if (mtu - 3 >= MAX_ATT_PAYLOAD) {
                    MAX_ATT_PAYLOAD
                } else {
                    mtu - 3
                }
                attPayloadSize.set(payload)
                appLog {
                    "MTU 协商成功: mtu=$mtu, payload=$payload"
                }
            }
            // 系统可能在连接后提前回调 onMtuChanged；仅在通知已开启并主动 requestMtu 之后才 ready。
            if (isMtuRequestStarted.get()) {
                markReady()
            } else {
                appLog {
                    "忽略就绪前的 MTU 回调（服务/通知尚未就绪）"
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                isServicesDiscoveryStarted.set(false)
                failSetup(
                    error = OtaError.ServiceDiscoverFailed(detail = "status=$status")
                )
                return
            }
            if (!isNotificationSetupStarted.compareAndSet(false, true)) {
                appLog {
                    "忽略重复的服务发现回调"
                }
                return
            }
            val service = gatt.getService(OtaUuids.SERVICE)
            if (service == null) {
                isNotificationSetupStarted.set(false)
                failSetup(error = OtaError.AmotaServiceNotFound)
                return
            }
            rxCharacteristic = service.getCharacteristic(OtaUuids.RX)
            txCharacteristic = service.getCharacteristic(OtaUuids.TX)
            if (rxCharacteristic == null || txCharacteristic == null) {
                isNotificationSetupStarted.set(false)
                failSetup(error = OtaError.AmotaCharacteristicMissing)
                return
            }
            appLog {
                "已找到 AMOTA 服务，正在开启通知"
            }
            enableTxNotification(
                gatt = gatt,
                characteristic = txCharacteristic!!
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != OtaUuids.CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                requestMtuAfterNotify(gatt = gatt)
            } else {
                isNotificationSetupStarted.set(false)
                failSetup(
                    error = OtaError.NotifyEnableFailed(detail = "status=$status")
                )
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            lastWriteStatus = status
            writeSemaphore.release()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { value ->
                    updater.onNotification(response = value)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            updater.onNotification(response = value)
        }
    }

    /**
     * 注册事件监听。传 null 解除注册。
     * 注册时立刻回放当前进度。
     */
    fun setListener(listener: OtaClientListener?) {
        listenerRef.set(listener)
        listener?.onProgress(progress = progressRef.get())
    }

    /**
     * 开始 OTA：内部自动连接并完成 GATT 准备后执行协议升级。
     * [firmwareFile] 须为 packed AMOTA 固件（含 48 字节头）。
     */
    suspend fun startOta(macAddress: String, firmwareFile: File): OtaResult {
        return runOtaSession(macAddress = macAddress) {
            updater.start(firmwareFile = firmwareFile, transport = transport)
        }
    }

    /**
     * 与文件重载相同；由调用方负责 [firmwareStream] 的生命周期（本方法不会关闭流）。
     */
    suspend fun startOta(macAddress: String, firmwareStream: InputStream): OtaResult {
        return runOtaSession(macAddress = macAddress) {
            updater.start(firmwareStream = firmwareStream, transport = transport)
        }
    }

    /**
     * 请求取消正在进行的升级；同时结束连接准备等待并断开 GATT。
     */
    fun cancelOta() {
        updater.cancel()
        if (isWaitingForReady.get()) {
            failSetup(error = OtaError.Cancelled)
        }
        disconnectInternal()
    }

    /** 立刻释放资源；会取消进行中的 OTA。 */
    fun close() {
        updater.cancel()
        isWaitingForReady.set(false)
        closeGatt()
        connectionStateRef.set(OtaConnectionState.Disconnected)
        isOtaRunning.set(false)
    }

    private suspend fun runOtaSession(
        macAddress: String,
        upgrade: suspend () -> OtaResult
    ): OtaResult {
        if (!isOtaRunning.compareAndSet(false, true)) {
            val error = OtaError.OtaInProgress
            emitFailed(error = error)
            return OtaResult.Failed(error = error)
        }
        return try {
            emitProgress(OtaProgress(phase = OtaPhase.Preparing, percent = 0))
            val setupError = connectAndWaitReady(macAddress = macAddress)
            if (setupError != null) {
                if (setupError is OtaError.Cancelled) {
                    emitProgress(
                        OtaProgress(
                            phase = OtaPhase.Cancelled,
                            percent = 0,
                            error = setupError
                        )
                    )
                    return OtaResult.Cancelled
                }
                emitFailed(error = setupError)
                return OtaResult.Failed(error = setupError)
            }
            upgrade()
        } finally {
            disconnectInternal()
            closeGatt()
            connectionStateRef.set(OtaConnectionState.Disconnected)
            isOtaRunning.set(false)
        }
    }

    private suspend fun connectAndWaitReady(macAddress: String): OtaError? =
        withContext(Dispatchers.IO) {
            val device = try {
                bluetoothAdapter?.getRemoteDevice(macAddress)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (device == null) {
                return@withContext OtaError.DeviceNotFound(macAddress = macAddress)
            }
            closeGatt()
            writeSemaphore = Semaphore(0)
            readySemaphore = Semaphore(0)
            setupErrorRef.set(null)
            resetSetupFlags()
            isWaitingForReady.set(true)
            connectionStateRef.set(OtaConnectionState.Connecting)
            appLog {
                "正在连接设备: $macAddress"
            }
            gatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
            if (gatt == null) {
                isWaitingForReady.set(false)
                return@withContext OtaError.ConnectFailed(detail = "connectGatt returned null")
            }
            val acquired = readySemaphore.tryAcquire(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            isWaitingForReady.set(false)
            if (!acquired) {
                disconnectInternal()
                return@withContext OtaError.ConnectTimeout
            }
            if (connectionStateRef.get() == OtaConnectionState.Ready) {
                null
            } else {
                setupErrorRef.get() ?: OtaError.ConnectFailed(detail = null)
            }
        }

    private fun writeRx(data: ByteArray): Boolean {
        val characteristic = rxCharacteristic
        val bluetoothGatt = gatt
        if (characteristic == null || bluetoothGatt == null) {
            lastTransportError.set(OtaError.TransportUnavailable)
            return false
        }
        while (writeSemaphore.tryAcquire()) {
            // discard
        }
        lastWriteStatus = BluetoothGatt.GATT_FAILURE
        val started = writeCharacteristicCompat(
            gatt = bluetoothGatt,
            characteristic = characteristic,
            data = data
        )
        if (!started) {
            lastTransportError.set(OtaError.GattWriteStartFailed)
            return false
        }
        val ok = writeSemaphore.tryAcquire(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!ok) {
            lastTransportError.set(OtaError.GattWriteTimeout)
            return false
        }
        val success = lastWriteStatus == BluetoothGatt.GATT_SUCCESS
        if (!success) {
            lastTransportError.set(
                OtaError.GattWriteFailed(detail = "status=$lastWriteStatus")
            )
        }
        return success
    }

    private fun emitProgress(progress: OtaProgress) {
        progressRef.set(progress)
        val listener = listenerRef.get() ?: return
        appLog {
            "emitProgress: $progress"
        }
        listener.onProgress(progress = progress)
        progress.error?.let { error ->
            listener.onError(error = error)
        }
    }

    private fun emitFailed(error: OtaError) {
        emitProgress(
            OtaProgress(
                phase = OtaPhase.Failed,
                percent = progressRef.get().percent,
                error = error
            )
        )
    }

    private fun failSetup(error: OtaError) {
        appLog {
            error.message
        }
        if (isWaitingForReady.compareAndSet(true, false)) {
            setupErrorRef.set(error)
            readySemaphore.release()
        }
    }

    private fun disconnectInternal() {
        connectionStateRef.set(OtaConnectionState.Disconnecting)
        gatt?.disconnect()
    }

    private fun resetSetupFlags() {
        attPayloadSize.set(DEFAULT_PAYLOAD)
        isServicesDiscoveryStarted.set(false)
        isNotificationSetupStarted.set(false)
        isMtuRequestStarted.set(false)
        isReadyMarked.set(false)
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
        rxCharacteristic = null
        txCharacteristic = null
        resetSetupFlags()
    }

    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        if (!isServicesDiscoveryStarted.compareAndSet(false, true)) {
            appLog {
                "忽略重复的服务发现请求"
            }
            return
        }
        appLog {
            "开始发现服务"
        }
        if (!gatt.discoverServices()) {
            isServicesDiscoveryStarted.set(false)
            failSetup(
                error = OtaError.ServiceDiscoverFailed(detail = "discoverServices=false")
            )
        }
    }

    private fun requestMtuAfterNotify(gatt: BluetoothGatt) {
        if (!isMtuRequestStarted.compareAndSet(false, true)) {
            appLog {
                "忽略重复的 MTU 请求"
            }
            return
        }
        appLog {
            "通知已开启，请求协商 MTU=$REQUESTED_MTU"
        }
        // 若 requestMtu 启动失败，或连接阶段已协商过同值导致不再回调，直接进入 Ready。
        if (!gatt.requestMtu(REQUESTED_MTU) || attPayloadSize.get() > DEFAULT_PAYLOAD) {
            markReady()
        }
    }

    private fun markReady() {
        if (!isReadyMarked.compareAndSet(false, true)) {
            return
        }
        connectionStateRef.set(OtaConnectionState.Ready)
        appLog {
            "AMOTA 就绪，payload=${attPayloadSize.get()}，开始升级"
        }
        if (isWaitingForReady.compareAndSet(true, false)) {
            setupErrorRef.set(null)
            readySemaphore.release()
        }
    }

    private fun enableTxNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(OtaUuids.CCCD)
        if (descriptor == null) {
            isNotificationSetupStarted.set(false)
            failSetup(error = OtaError.CccdMissing)
            return
        }
        val ok = writeDescriptorCompat(
            gatt = gatt,
            descriptor = descriptor,
            value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        )
        if (!ok) {
            isNotificationSetupStarted.set(false)
            failSetup(error = OtaError.CccdWriteFailed)
        }
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = data
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

}