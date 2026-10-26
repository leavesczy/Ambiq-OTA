package github.leavesczy.ambiq.ota.ota

import github.leavesczy.ambiq.ota.util.appLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * AMOTA 协议引擎（模块内部使用）。
 *
 * 不负责建连；假定上层 [OtaClient] 已 Ready，
 * 并通过 [OtaTransport] 提供写通道与分片大小。
 *
 * ## 升级顺序
 * 1. [AmotaOtaCommand.FW_HEADER]：发 packed 固件前 48 字节；解析长度；读设备返回的续传偏移
 * 2. 若偏移 > 0，跳过已写入字节
 * 3. [AmotaOtaCommand.FW_DATA]：循环发送最多 512 字节/包，每包等 Notify 成功
 * 4. [AmotaOtaCommand.FW_VERIFY]：整包校验
 * 5. [AmotaOtaCommand.FW_RESET]：复位；须收到设备成功应答后才算 Success
 *
 * ## 包格式（小端）
 * `[lenLE 2][cmd 1][data N][crc32LE 4]`，其中 len = N + 4（只含 data+crc）
 */
class OtaUpdater(private val onProgress: (OtaProgress) -> Unit) {

    private companion object {
        /** packed 固件文件头长度（含 meta，原始镜像从偏移 48 开始）。 */
        private const val FIRMWARE_HEADER_SIZE = 48

        /** 每个 AMOTA DATA 命令最多携带的固件字节数。 */
        private const val FIRMWARE_PACKET_SIZE = 512

        /** AMOTA 帧头：2 字节长度 + 1 字节命令。 */
        private const val HEADER_SIZE = 3
        private const val CRC_SIZE = 4
        private const val CMD_TIMEOUT_MS = 3000L
        private const val VERIFY_TIMEOUT_MS = 5000L

        /** 数据传输阶段进度上限；Verifying=96 / Resetting=98 / Success=100。 */
        private const val DATA_PERCENT_MAX = 94.0

        private const val VERIFY_PERCENT = 96.0

        private const val RESET_PERCENT = 98.0

        private const val SUCCESS_PERCENT = 100.0

        private const val PROGRESS_EPSILON = 0.001

        private fun toHex(data: ByteArray): String =
            data.joinToString(separator = " ") { String.format("%02X", it) }
    }

    /** 等待设备 Notify 应答；与 [onNotification] 成对使用。 */
    private val cmdResponseSemaphore = Semaphore(0)

    /** 最近一次命令应答是否成功（status==0）。 */
    private val isLastCommandOk = AtomicBoolean(false)

    private val pendingError = AtomicReference<OtaError?>(null)

    private var isStopRequested = false

    /** HEADER 应答里的续传偏移（已成功写入的固件字节数）。 */
    private var fileOffset = 0

    /** 从固件头 [8..11] 解析出的原始固件长度。 */
    private var firmwareSize = 0

    private var transport: OtaTransport? = null

    private var progress = OtaProgress()

    /** 固件 DATA 读缓冲（每会话复用）。 */
    private val firmwareReadBuffer = ByteArray(size = FIRMWARE_PACKET_SIZE)

    /** AMOTA 整包组装缓冲：header(3) + data(≤512) + crc(4)。 */
    private val packetBuffer = ByteArray(size = HEADER_SIZE + FIRMWARE_PACKET_SIZE + CRC_SIZE)

    suspend fun start(
        firmwareFile: File,
        transport: OtaTransport
    ): OtaResult = withContext(context = Dispatchers.IO) {
        FileInputStream(firmwareFile).use { input ->
            startInternal(input = input, transport = transport)
        }
    }

    fun cancel() {
        isStopRequested = true
    }

    /**
     * 处理 TX Notify 数据。
     * 布局：`[len0][len1][cmd][status][可选 data…]`；本实现主要用 cmd/status，
     * HEADER 成功时再读 [4..7] 作为续传偏移。
     */
    fun onNotification(response: ByteArray) {
        if (response.size < 4) {
            return
        }
        val cmd = AmotaOtaCommand.fromByte(value = response[2].toInt())
        if (cmd == AmotaOtaCommand.UNKNOWN) {
            return
        }
        val responseStatus = response[3].toInt() and 0xFF
        when (val amotaStatus = AmotaStatus.status(status = responseStatus)) {
            AmotaStatus.SUCCESS -> {
                when (cmd) {
                    AmotaOtaCommand.FW_HEADER -> {
                        if (response.size >= 8) {
                            fileOffset = (response[4].toInt() and 0xFF) or
                                    ((response[5].toInt() and 0xFF) shl 8) or
                                    ((response[6].toInt() and 0xFF) shl 16) or
                                    ((response[7].toInt() and 0xFF) shl 24)
                            appLog {
                                "固件头应答，续传偏移=$fileOffset"
                            }
                        }
                        isLastCommandOk.set(true)
                        cmdResponseSemaphore.release()
                    }

                    AmotaOtaCommand.FW_DATA,
                    AmotaOtaCommand.FW_VERIFY,
                    AmotaOtaCommand.FW_RESET -> {
                        isLastCommandOk.set(true)
                        cmdResponseSemaphore.release()
                    }
                }
            }

            AmotaStatus.CRC_ERROR,
            AmotaStatus.INVALID_HEADER_INFO,
            AmotaStatus.INVALID_PKT_LENGTH,
            AmotaStatus.INSUFFICIENT_BUFFER,
            AmotaStatus.INSUFFICIENT_FLASH,
            AmotaStatus.UNKNOWN_ERROR,
            AmotaStatus.FLASH_WRITE_ERROR,
            AmotaStatus.MAX,
            null -> {
                isLastCommandOk.set(false)
                cmdResponseSemaphore.release()
                fail(
                    error = OtaError.AmotaStatusError(
                        status = amotaStatus,
                        rawHex = toHex(data = response)
                    )
                )
            }
        }
    }

    private fun startInternal(
        input: InputStream,
        transport: OtaTransport
    ): OtaResult {
        this.transport = transport
        isStopRequested = false
        fileOffset = 0
        firmwareSize = 0
        isLastCommandOk.set(false)
        pendingError.set(null)
        drain(semaphore = cmdResponseSemaphore)
        update(phase = OtaPhase.Idle, percent = 0.0)
        return try {
            if (input.available() == 0) {
                return failResult(error = OtaError.FirmwareEmpty(detail = null))
            }
            update(phase = OtaPhase.SendingHeader, percent = 0.0)
            appLog {
                "开始发送固件头"
            }
            if (!sendFirmwareHeader(input = input)) {
                return cancelledOrFailed(
                    fallback = pendingError.get() ?: OtaError.SendHeaderFailed
                )
            }
            if (fileOffset > 0) {
                if (!skipFully(input = input, bytes = fileOffset.toLong())) {
                    return cancelledOrFailed(
                        fallback = OtaError.ResumeOffsetFailed(detail = "offset=$fileOffset")
                    )
                }
                appLog {
                    "已跳过续传字节=$fileOffset"
                }
            }
            update(phase = OtaPhase.SendingData, percent = 0.0)
            appLog {
                "开始传输固件数据，size=$firmwareSize"
            }
            if (!sendFirmwareData(input = input)) {
                return cancelledOrFailed(
                    fallback = pendingError.get() ?: OtaError.SendDataFailed(detail = null)
                )
            }
            update(phase = OtaPhase.Verifying, percent = VERIFY_PERCENT)
            appLog {
                "开始校验固件"
            }
            if (!sendVerifyCmd()) {
                return cancelledOrFailed(
                    fallback = pendingError.get() ?: OtaError.VerifyFailed
                )
            }
            update(phase = OtaPhase.Resetting, percent = RESET_PERCENT)
            appLog {
                "发送复位命令"
            }
            if (!sendResetCmd()) {
                return cancelledOrFailed(
                    fallback = pendingError.get() ?: OtaError.ResetFailed
                )
            }
            update(phase = OtaPhase.Success, percent = SUCCESS_PERCENT)
            appLog {
                "升级成功，设备即将重启"
            }
            OtaResult.Success
        } catch (e: Exception) {
            failResult(
                error = OtaError.OtaException(detail = e.message)
            )
        } finally {
            this.transport = null
        }
    }

    /** 读 48 字节头、解析固件长度，发送 FW_HEADER 并等待应答（可能带回续传偏移）。 */
    private fun sendFirmwareHeader(input: InputStream): Boolean {
        val header = ByteArray(size = FIRMWARE_HEADER_SIZE)
        val read = input.read(header)
        if (read < FIRMWARE_HEADER_SIZE) {
            fail(
                error = OtaError.FirmwareHeaderInvalid(detail = "read=$read")
            )
            return false
        }
        firmwareSize = (header[8].toInt() and 0xFF) or
                ((header[9].toInt() and 0xFF) shl 8) or
                ((header[10].toInt() and 0xFF) shl 16) or
                ((header[11].toInt() and 0xFF) shl 24)
        if (firmwareSize <= 0) {
            fail(
                error = OtaError.FirmwareSizeInvalid(detail = "size=$firmwareSize")
            )
            return false
        }
        appLog {
            "固件长度=$firmwareSize"
        }
        if (!sendOtaCmd(cmd = AmotaOtaCommand.FW_HEADER, data = header, len = header.size)) {
            return false
        }
        return waitCmdResponse(timeoutMs = CMD_TIMEOUT_MS)
    }

    /** 循环发送固件数据；每发完一个 AMOTA DATA 包都要等设备 Notify。 */
    private fun sendFirmwareData(input: InputStream): Boolean {
        var offset = fileOffset
        while (offset < firmwareSize) {
            if (isStopRequested) return false
            if (progress.phase == OtaPhase.Failed) return false
            val read = input.read(firmwareReadBuffer)
            if (read <= 0) {
                fail(
                    error = OtaError.SendDataFailed(detail = "unexpected EOF offset=$offset")
                )
                return false
            }
            val len = minOf(a = read, b = FIRMWARE_PACKET_SIZE)
            if (!sendOtaCmd(cmd = AmotaOtaCommand.FW_DATA, data = firmwareReadBuffer, len = len)) {
                return false
            }
            if (!waitCmdResponse(timeoutMs = CMD_TIMEOUT_MS)) {
                return false
            }
            offset += len
            val percent = if (firmwareSize <= 0) {
                0.0
            } else {
                ((offset.toDouble() * DATA_PERCENT_MAX) / firmwareSize.toDouble())
                    .coerceIn(minimumValue = 0.0, maximumValue = DATA_PERCENT_MAX)
            }
            update(phase = OtaPhase.SendingData, percent = percent)
        }
        return true
    }

    private fun sendVerifyCmd(): Boolean {
        if (!sendOtaCmd(cmd = AmotaOtaCommand.FW_VERIFY, data = null, len = 0)) {
            return false
        }
        return waitCmdResponse(timeoutMs = VERIFY_TIMEOUT_MS)
    }

    private fun sendResetCmd(): Boolean {
        if (!sendOtaCmd(cmd = AmotaOtaCommand.FW_RESET, data = null, len = 0)) {
            return false
        }
        return waitCmdResponse(timeoutMs = CMD_TIMEOUT_MS)
    }

    /**
     * 组装一条 AMOTA 命令帧并下发。
     * CRC 只覆盖 data；无 data 时 CRC 字段为 0。
     */
    private fun sendOtaCmd(cmd: AmotaOtaCommand, data: ByteArray?, len: Int): Boolean {
        val checksum = if (len != 0 && data != null) {
            AmotaCrcCalculator.calcCrc32(len = len, buf = data)
        } else {
            0
        }
        val packetLength = HEADER_SIZE + len + CRC_SIZE
        val dataPlusCrc = len + CRC_SIZE
        packetBuffer[0] = (dataPlusCrc and 0xFF).toByte()
        packetBuffer[1] = ((dataPlusCrc shr 8) and 0xFF).toByte()
        packetBuffer[2] = cmd.code.toByte()
        if (len > 0 && data != null) {
            System.arraycopy(data, 0, packetBuffer, HEADER_SIZE, len)
        }
        packetBuffer[HEADER_SIZE + len] = (checksum and 0xFF).toByte()
        packetBuffer[HEADER_SIZE + len + 1] = ((checksum shr 8) and 0xFF).toByte()
        packetBuffer[HEADER_SIZE + len + 2] = ((checksum shr 16) and 0xFF).toByte()
        packetBuffer[HEADER_SIZE + len + 3] = ((checksum shr 24) and 0xFF).toByte()
        return sendPacket(data = packetBuffer, length = packetLength)
    }

    /**
     * 把完整 AMOTA 包按 [OtaTransport.maxPayloadSize] 切成多帧写入 BLE。
     * 例如 519 字节包按当前 payload 分片写入（协商 MTU 越大，写次数越少）。
     */
    private fun sendPacket(data: ByteArray, length: Int = data.size): Boolean {
        val writer = transport
        if (writer == null) {
            fail(error = OtaError.TransportUnavailable)
            return false
        }
        val payloadSize = writer.maxPayloadSize().coerceAtLeast(minimumValue = 1)
        var idx = 0
        while (idx < length) {
            if (isStopRequested) {
                return false
            }
            val frameLen = minOf(a = payloadSize, b = length - idx)
            if (!writer.write(data = data, offset = idx, length = frameLen)) {
                fail(
                    error = writer.consumeLastWriteError()
                        ?: OtaError.GattWriteFailed(detail = "idx=$idx")
                )
                return false
            }
            idx += frameLen
        }
        return true
    }

    /** 阻塞等待 [onNotification] 释放信号量；超时或 status≠0 则失败。 */
    private fun waitCmdResponse(timeoutMs: Long): Boolean {
        return try {
            val acquired = cmdResponseSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
            if (!acquired) {
                fail(
                    error = OtaError.ResponseTimeout(detail = "timeoutMs=$timeoutMs")
                )
                return false
            }
            isLastCommandOk.get() && progress.phase != OtaPhase.Failed
        } catch (_: InterruptedException) {
            fail(
                error = OtaError.OtaException(detail = "interrupted")
            )
            false
        }
    }

    private fun skipFully(input: InputStream, bytes: Long): Boolean {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                val buf = ByteArray(size = minOf(a = remaining, b = 4096L).toInt())
                val read = input.read(buf)
                if (read <= 0) {
                    return false
                }
                remaining -= read
            } else {
                remaining -= skipped
            }
        }
        return true
    }

    private fun update(phase: OtaPhase, percent: Double) {
        if (progress.phase == phase &&
            kotlin.math.abs(x = progress.percent - percent) < PROGRESS_EPSILON
        ) {
            return
        }
        val percentChanged = kotlin.math.abs(x = progress.percent - percent) >= PROGRESS_EPSILON
        progress = OtaProgress(phase = phase, percent = percent)
        // Failed / Cancelled 只更新内部状态，成败由 OtaResult 外抛，不推进度。
        if (phase == OtaPhase.Failed || phase == OtaPhase.Cancelled) {
            return
        }
        if (percentChanged) {
            onProgress(progress)
        }
    }

    private fun fail(error: OtaError) {
        pendingError.set(error)
        appLog {
            error.logText()
        }
        update(phase = OtaPhase.Failed, percent = progress.percent)
    }

    /** 用户取消优先于业务失败；各阶段中断统一走此出口。 */
    private fun cancelledOrFailed(fallback: OtaError): OtaResult {
        if (isStopRequested) {
            val error = OtaError.Cancelled
            appLog {
                "${error.logText()} (phase=${progress.phase}, percent=${progress.percent})"
            }
            update(phase = OtaPhase.Cancelled, percent = progress.percent)
            return OtaResult.Cancelled
        }
        if (progress.phase == OtaPhase.Failed) {
            return OtaResult.Failed(error = pendingError.get() ?: fallback)
        }
        return failResult(error = fallback)
    }

    private fun failResult(error: OtaError): OtaResult {
        if (progress.phase != OtaPhase.Failed || pendingError.get() == null) {
            fail(error = error)
        }
        return OtaResult.Failed(error = pendingError.get() ?: error)
    }

    private fun drain(semaphore: Semaphore) {
        while (semaphore.tryAcquire()) {
            // discard
        }
    }

}
