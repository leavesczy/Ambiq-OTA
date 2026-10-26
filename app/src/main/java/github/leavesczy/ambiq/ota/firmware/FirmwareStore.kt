package github.leavesczy.ambiq.ota.firmware

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * 将系统文档 URI 流式拷贝到缓存，供 OTA 按文件路径读取。
 */
object FirmwareStore {

    const val MIN_FIRMWARE_BYTES = 48

    data class CachedFirmware(
        val displayName: String,
        val absolutePath: String,
        val sizeBytes: Long
    )

    fun cacheFromUri(context: Context, uri: Uri): CachedFirmware {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context = context, uri = uri) ?: "firmware.bin"
        val safeName = displayName.replace(
            regex = Regex(pattern = "[^\\w.\\-]"),
            replacement = "_"
        )
        val cached = File(context.cacheDir, "ota_$safeName")
        val size = resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cached).use { output ->
                input.copyTo(out = output)
            }
            cached.length()
        } ?: throw IllegalStateException("无法读取固件文件")
        if (size < MIN_FIRMWARE_BYTES) {
            cached.delete()
            throw IllegalStateException("固件过短，需含 48 字节头")
        }
        return CachedFirmware(
            displayName = displayName,
            absolutePath = cached.absolutePath,
            sizeBytes = size
        )
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) {
                cursor.getString(index)
            } else {
                null
            }
        }
    }

}
