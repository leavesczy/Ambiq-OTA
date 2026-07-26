package github.leavesczy.ambiq.ota.ota

/**
 * AMOTA 命令字节（写在帧的第 3 字节，即 index=2）。
 * 设备 Notify 应答里会回显同一命令码。
 */
enum class AmotaOtaCommand(val code: Int) {
    UNKNOWN(code = 0),

    /** 发送 packed 固件 48 字节头。 */
    FW_HEADER(code = 1),

    /** 发送固件数据块（最多 512 字节）。 */
    FW_DATA(code = 2),

    /** 请求设备校验整包。 */
    FW_VERIFY(code = 3),

    /** 请求设备复位以应用新固件。 */
    FW_RESET(code = 4);

    companion object {

        fun fromByte(value: Int): AmotaOtaCommand {
            return entries.firstOrNull { it.code == (value and 0xFF) } ?: UNKNOWN
        }

    }

}
