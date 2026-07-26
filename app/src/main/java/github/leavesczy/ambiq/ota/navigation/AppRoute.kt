package github.leavesczy.ambiq.ota.navigation

import github.leavesczy.ambiq.ota.ble.ScannedDevice

/**
 * 单 Activity 内页面路由。
 */
sealed interface AppRoute {
    data object Scan : AppRoute
    data class Ota(val device: ScannedDevice) : AppRoute
}
