package github.leavesczy.ambiq.ota.ota

/**
 * 模块内部 BLE 写抽象，由 [OtaClient] 实现。
 *
 * 协议引擎 [OtaUpdater] 只依赖本接口发数据，不直接碰 BluetoothGatt：
 * - [write]：写入一帧 ATT payload（已是切片后的小包）
 * - [maxPayloadSize]：当前允许的最大帧长（`mtu - 3` 减去安全余量）
 * - [consumeLastWriteError]：最近一次 [write] 失败的原因（读后清空）
 */
interface OtaTransport {

    fun write(data: ByteArray): Boolean {
        return write(data = data, offset = 0, length = data.size)
    }

    fun write(data: ByteArray, offset: Int, length: Int): Boolean

    fun maxPayloadSize(): Int {
        return 20
    }

    fun consumeLastWriteError(): OtaError? {
        return null
    }

}
