package github.leavesczy.ambiq.ota.ble

/**
 * 扫描到的 BLE 设备快照。
 * [name] 非空：扫描层已过滤无名字设备。
 */
data class ScannedDevice(
    val name: String,
    val macAddress: String,
    val rssi: Int
) {
    val displayName: String
        get() = name
}
