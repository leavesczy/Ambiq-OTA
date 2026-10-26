package github.leavesczy.ambiq.ota.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * 蓝牙权限与适配器状态辅助。
 *
 * minSdk 31 起仅需 [Manifest.permission.BLUETOOTH_SCAN] /
 * [Manifest.permission.BLUETOOTH_CONNECT]。
 */
object BluetoothPermissionHelper {

    fun requiredPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    }

    fun hasBluetoothPermissions(context: Context): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    fun isBleSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

}
