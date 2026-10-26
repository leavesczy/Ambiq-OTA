package github.leavesczy.ambiq.ota.ota

/**
 * OTA 结构化错误。
 *
 * 明确场景用独立子类型；设备 AMOTA 应答 status 非 [AmotaStatus.SUCCESS] 时统一为 [AmotaStatusError]。
 * [message] 为面向界面的固定文案（不含系统 / 厂商原文）；诊断细节见 [debugDetail]。
 */
sealed class OtaError(open val message: String) {

    open val debugDetail: String?
        get() = null

    data class DeviceNotFound(val macAddress: String) : OtaError(
        message = "无法找到设备"
    ) {
        override val debugDetail: String
            get() = macAddress
    }

    data class ConnectFailed(val detail: String?) : OtaError(
        message = "GATT 连接失败"
    ) {
        override val debugDetail: String?
            get() = detail
    }

    data object ConnectTimeout : OtaError(message = "连接准备超时")

    data class ServiceDiscoverFailed(val detail: String) : OtaError(
        message = "服务发现失败"
    ) {
        override val debugDetail: String
            get() = detail
    }

    data object AmotaServiceNotFound : OtaError(message = "未找到 AMOTA 服务")

    data object AmotaCharacteristicMissing : OtaError(message = "AMOTA 特征值缺失")

    data object CccdMissing : OtaError(message = "缺少 CCCD")

    data object CccdWriteFailed : OtaError(message = "写入 CCCD 失败")

    data class NotifyEnableFailed(val detail: String) : OtaError(
        message = "开启通知失败"
    ) {
        override val debugDetail: String
            get() = detail
    }

    data object TransportUnavailable : OtaError(message = "传输通道不可用")

    data object GattWriteStartFailed : OtaError(message = "GATT 写入启动失败")

    data object GattWriteTimeout : OtaError(message = "GATT 写入超时")

    data class GattWriteFailed(val detail: String? = null) : OtaError(
        message = "GATT 写入失败"
    ) {
        override val debugDetail: String?
            get() = detail
    }

    data object OtaInProgress : OtaError(message = "已有升级正在进行")

    data object Cancelled : OtaError(message = "升级已取消")

    data class FirmwareEmpty(val detail: String?) : OtaError(
        message = "固件文件为空"
    ) {
        override val debugDetail: String?
            get() = detail
    }

    data class FirmwareHeaderInvalid(val detail: String) : OtaError(
        message = "固件头无效"
    ) {
        override val debugDetail: String
            get() = detail
    }

    data class FirmwareSizeInvalid(val detail: String) : OtaError(
        message = "固件长度无效"
    ) {
        override val debugDetail: String
            get() = detail
    }

    data object SendHeaderFailed : OtaError(message = "发送固件头失败")

    data class ResumeOffsetFailed(val detail: String) : OtaError(
        message = "无法定位续传偏移"
    ) {
        override val debugDetail: String
            get() = detail
    }

    data class SendDataFailed(val detail: String?) : OtaError(
        message = "传输固件数据失败"
    ) {
        override val debugDetail: String?
            get() = detail
    }

    data object VerifyFailed : OtaError(message = "固件校验失败")

    data object ResetFailed : OtaError(message = "复位命令失败")

    data class ResponseTimeout(val detail: String? = null) : OtaError(
        message = "等待设备响应超时"
    ) {
        override val debugDetail: String?
            get() = detail
    }

    data class AmotaStatusError(
        val status: AmotaStatus?,
        val rawHex: String
    ) : OtaError(
        message = "设备状态码异常"
    ) {
        override val debugDetail: String
            get() = buildString {
                append("status=${status ?: "nil"}")
                if (rawHex.isNotBlank()) {
                    append(", raw=$rawHex")
                }
            }
    }

    data class OtaException(val detail: String?) : OtaError(
        message = "OTA 过程发生异常"
    ) {
        override val debugDetail: String?
            get() = detail
    }

}

fun OtaError.logText(): String {
    val detail = debugDetail
    return if (detail.isNullOrBlank()) {
        message
    } else {
        "$message detail=$detail"
    }
}
