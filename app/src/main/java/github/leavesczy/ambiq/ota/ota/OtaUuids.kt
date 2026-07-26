package github.leavesczy.ambiq.ota.ota

import github.leavesczy.ambiq.ota.ota.OtaUuids.CCCD
import github.leavesczy.ambiq.ota.ota.OtaUuids.RX
import github.leavesczy.ambiq.ota.ota.OtaUuids.SERVICE
import github.leavesczy.ambiq.ota.ota.OtaUuids.TX
import java.util.UUID

/**
 * Ambiq AMOTA 固定 GATT UUID。
 *
 * - [SERVICE]：AMOTA 服务
 * - [RX]：手机写入命令/数据
 * - [TX]：设备 Notify 应答
 * - [CCCD]：开启 TX 通知时写入的客户端配置描述符
 */
object OtaUuids {

    val SERVICE: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e1001")

    /** Phone → Device；仅使用 Write Without Response */
    val RX: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0001")

    /** Device → Phone（Notify） */
    val TX: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0002")

    /** Client Characteristic Configuration Descriptor */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

}
