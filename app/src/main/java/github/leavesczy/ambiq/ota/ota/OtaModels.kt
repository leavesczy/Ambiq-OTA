package github.leavesczy.ambiq.ota.ota

/**
 * 升级协议阶段（含内部连接准备；对外部统一以进度呈现）。
 *
 * 正常顺序：Idle → Preparing → SendingHeader → SendingData → Verifying → Resetting → Success
 */
enum class OtaPhase {
    Idle,

    /** 内部 connectGatt / 发现服务 / 开通知 / 协商 MTU。 */
    Preparing,

    /** 发送 48 字节固件头（含长度等 meta）。 */
    SendingHeader,

    /** 按最多 512 字节一块发送固件本体。 */
    SendingData,

    /** 让设备校验整包固件。 */
    Verifying,

    /** 发送复位命令，设备随后重启。 */
    Resetting,
    Success,
    Failed,
    Cancelled;
}

/**
 * 升级进度快照。
 * @param phase 当前阶段
 * @param percent 0–100。Preparing / SendingHeader 为 0；SendingData 按已 ACK 字节线性映射到 0–94；
 * Verifying 为 96；Resetting 为 98；Success 为 100。失败或取消时保留当时 percent。
 * 对外回调在 [OtaUpdater] 内去重：phase / percent / error 未变时不重复通知。
 * @param error 失败时的结构化错误（成功时为 null）
 */
data class OtaProgress(
    val phase: OtaPhase = OtaPhase.Idle,
    val percent: Int = 0,
    val error: OtaError? = null
)

/** [OtaClient.startOta] 的最终结果。 */
sealed class OtaResult {

    data object Success : OtaResult()

    data object Cancelled : OtaResult()

    data class Failed(val error: OtaError) : OtaResult()

}

/**
 * [OtaClient] 对外事件出口。回调可能来自 Binder / 后台线程，UI 侧请自行切主线程。
 *
 * 通过 [OtaClient.setListener] 注册；注册时立刻回放当前进度。
 */
interface OtaClientListener {

    fun onProgress(progress: OtaProgress) {

    }

    fun onError(error: OtaError) {

    }

}

/** 模块内部连接准备状态，不对外部暴露。 */
enum class OtaConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Ready,
    Disconnecting;
}