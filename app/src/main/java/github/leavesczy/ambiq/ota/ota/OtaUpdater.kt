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
        private const val DATA_PERCENT_MAX = 94

        private const val VERIFY_PERCENT = 96

        private const val RESET_PERCENT = 98

        private const val SUCCESS_PERCENT = 100

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

    fun cancel() {
        isStopRequested = true
    }

    suspend fun start(
        firmwareFile: File,
        transport: OtaTransport
    ): OtaResult = withContext(Dispatchers.IO) {
        FileInputStream(firmwareFile).use { input ->
            startInternal(input = input, transport = transport)
        }
    }

    suspend fun start(
        firmwareStream: InputStream,
        transport: OtaTransport
    ): OtaResult = withContext(Dispatchers.IO) {
        startInternal(
            input = firmwareStream,
            transport = transport
        )
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
        update(phase = OtaPhase.Idle, percent = 0)
        return try {
            if (input.available() == 0) {
                return failResult(error = OtaError.FirmwareEmpty(detail = null))
            }
            update(phase = OtaPhase.SendingHeader, percent = 0)
            appLog {
                "开始发送固件头"
            }
            if (!sendFirmwareHeader(input = input)) {
                return failResult(
                    error = pendingError.get()
                        ?: OtaError.SendHeaderFailed
                )
            }
            if (fileOffset > 0) {
                if (!skipFully(input = input, bytes = fileOffset.toLong())) {
                    return failResult(
                        error = OtaError.ResumeOffsetFailed(detail = "offset=$fileOffset")
                    )
                }
                appLog {
                    "已跳过续传字节=$fileOffset"
                }
            }
            update(phase = OtaPhase.SendingData, percent = 0)
            appLog {
                "开始传输固件数据，size=$firmwareSize"
            }
            if (!sendFirmwareData(input = input)) {
                return when {
                    isStopRequested -> {
                        val error = OtaError.Cancelled
                        appLog {
                            error.message
                        }
                        update(
                            phase = OtaPhase.Cancelled,
                            percent = progress.percent,
                            error = error
                        )
                        OtaResult.Cancelled
                    }

                    progress.phase == OtaPhase.Failed ->
                        OtaResult.Failed(
                            error = progress.error
                                ?: pendingError.get()
                                ?: OtaError.SendDataFailed(detail = null)
                        )

                    else -> failResult(
                        error = pendingError.get()
                            ?: OtaError.SendDataFailed(detail = null)
                    )
                }
            }
            update(phase = OtaPhase.Verifying, percent = VERIFY_PERCENT)
            appLog {
                "开始校验固件"
            }
            if (!sendVerifyCmd()) {
                return failResult(
                    error = pendingError.get()
                        ?: OtaError.VerifyFailed
                )
            }
            update(phase = OtaPhase.Resetting, percent = RESET_PERCENT)
            appLog {
                "发送复位命令"
            }
            if (!sendResetCmd()) {
                return failResult(
                    error = pendingError.get()
                        ?: OtaError.ResetFailed
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
        val header = ByteArray(FIRMWARE_HEADER_SIZE)
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
            val buffer = ByteArray(FIRMWARE_PACKET_SIZE)
            val read = input.read(buffer)
            if (read <= 0) {
                fail(
                    error = OtaError.SendDataFailed(detail = "unexpected EOF offset=$offset")
                )
                return false
            }
            val len = minOf(read, FIRMWARE_PACKET_SIZE)
            if (!sendOtaCmd(cmd = AmotaOtaCommand.FW_DATA, data = buffer, len = len)) {
                return false
            }
            if (!waitCmdResponse(timeoutMs = CMD_TIMEOUT_MS)) {
                return false
            }
            offset += len
            val percent = ((offset.toLong() * DATA_PERCENT_MAX) / firmwareSize.toLong())
                .toInt()
                .coerceIn(0, DATA_PERCENT_MAX)
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
        val packet = ByteArray(packetLength)
        val dataPlusCrc = len + CRC_SIZE
        packet[0] = (dataPlusCrc and 0xFF).toByte()
        packet[1] = ((dataPlusCrc shr 8) and 0xFF).toByte()
        packet[2] = cmd.code.toByte()
        if (len > 0 && data != null) {
            System.arraycopy(data, 0, packet, HEADER_SIZE, len)
        }
        packet[HEADER_SIZE + len] = (checksum and 0xFF).toByte()
        packet[HEADER_SIZE + len + 1] = ((checksum shr 8) and 0xFF).toByte()
        packet[HEADER_SIZE + len + 2] = ((checksum shr 16) and 0xFF).toByte()
        packet[HEADER_SIZE + len + 3] = ((checksum shr 24) and 0xFF).toByte()
        return sendPacket(data = packet)
    }

    /**
     * 把完整 AMOTA 包按 [OtaTransport.maxPayloadSize] 切成多帧写入 BLE。
     * 例如 519 字节包 + 237 分片 ≈ 3 次 GATT 写。
     */
    private fun sendPacket(data: ByteArray): Boolean {
        val writer = transport
        if (writer == null) {
            fail(error = OtaError.TransportUnavailable)
            return false
        }
        val payloadSize = writer.maxPayloadSize().coerceAtLeast(1)
        var idx = 0
        while (idx < data.size) {
            if (isStopRequested) {
                return false
            }
            val frameLen = minOf(payloadSize, data.size - idx)
            val frame = data.copyOfRange(fromIndex = idx, toIndex = idx + frameLen)
            if (!writer.write(data = frame)) {
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
                val buf = ByteArray(minOf(remaining, 4096L).toInt())
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

    private fun update(phase: OtaPhase, percent: Int, error: OtaError? = null) {
        if (progress.phase == phase &&
            progress.percent == percent &&
            progress.error == error
        ) {
            return
        }
        val value = OtaProgress(phase = phase, percent = percent, error = error)
        progress = value
        onProgress(value)
    }

    private fun fail(error: OtaError) {
        pendingError.set(error)
        appLog {
            error.message
        }
        update(
            phase = OtaPhase.Failed,
            percent = progress.percent,
            error = error
        )
    }

    private fun failResult(error: OtaError): OtaResult {
        if (progress.phase != OtaPhase.Failed || progress.error == null) {
            fail(error = error)
        }
        return OtaResult.Failed(error = progress.error ?: error)
    }

    private fun drain(semaphore: Semaphore) {
        while (semaphore.tryAcquire()) {
            // discard
        }
    }

}