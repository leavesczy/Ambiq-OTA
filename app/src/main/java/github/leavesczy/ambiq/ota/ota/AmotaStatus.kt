package github.leavesczy.ambiq.ota.ota

/**
 * 设备 Notify 中的 AMOTA status（文档 eAmotaStatus）。
 * SUCCESS=0；其余为失败码。
 */
enum class AmotaStatus(val code: Int) {
    SUCCESS(code = 0),
    CRC_ERROR(code = 1),
    INVALID_HEADER_INFO(code = 2),
    INVALID_PKT_LENGTH(code = 3),
    INSUFFICIENT_BUFFER(code = 4),
    INSUFFICIENT_FLASH(code = 5),
    UNKNOWN_ERROR(code = 6),
    FLASH_WRITE_ERROR(code = 7),
    MAX(code = 8);

    companion object {

        fun status(status: Int): AmotaStatus? {
            return fromByte(value = status)
        }

        private fun fromByte(value: Int): AmotaStatus? {
            return entries.firstOrNull {
                it.code == (value and 0xFF)
            }
        }
    }
}
