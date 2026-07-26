package github.leavesczy.ambiq.ota

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import github.leavesczy.ambiq.ota.navigation.AppRoute
import github.leavesczy.ambiq.ota.ui.ota.OtaScreen
import github.leavesczy.ambiq.ota.ui.scan.ScanScreen
import github.leavesczy.ambiq.ota.ui.theme.AmbiqOtaTheme

/**
 * 单 Activity 入口：扫描页 → OTA 升级页。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmbiqOtaTheme {
                var route by remember { mutableStateOf<AppRoute>(AppRoute.Scan) }
                when (val current = route) {
                    is AppRoute.Scan -> {
                        ScanScreen(
                            onDeviceSelected = { device ->
                                route = AppRoute.Ota(device = device)
                            }
                        )
                    }

                    is AppRoute.Ota -> {
                        OtaScreen(
                            device = current.device,
                            onBack = { route = AppRoute.Scan }
                        )
                    }
                }
            }
        }
    }

}
