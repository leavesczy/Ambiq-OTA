package github.leavesczy.ambiq.ota.ui.scan

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import github.leavesczy.ambiq.ota.ble.BluetoothPermissionHelper
import github.leavesczy.ambiq.ota.ble.ScannedDevice
import github.leavesczy.ambiq.ota.ui.components.CompactActionButton
import github.leavesczy.ambiq.ota.ui.components.DeviceListItem
import github.leavesczy.ambiq.ota.ui.components.EmptyState
import github.leavesczy.ambiq.ota.ui.components.SectionCard
import github.leavesczy.ambiq.ota.ui.components.StatusChip
import github.leavesczy.ambiq.ota.ui.theme.AppDimens
import github.leavesczy.ambiq.ota.ui.theme.AtmosphereScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onDeviceSelected: (ScannedDevice) -> Unit,
    viewModel: ScanViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.onPermissionResult(granted = result.values.all { it })
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onBluetoothEnableResult(enabled = result.resultCode == Activity.RESULT_OK)
    }

    fun requestStartScan() {
        when {
            viewModel.needsPermissionRequest() -> {
                permissionLauncher.launch(BluetoothPermissionHelper.requiredPermissions())
            }

            viewModel.needsBluetoothEnable() -> {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }

            else -> viewModel.startScan()
        }
    }

    LaunchedEffect(Unit) {
        requestStartScan()
    }

    LaunchedEffect(uiState.toastMessage) {
        val message = uiState.toastMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.consumeToast()
    }

    val scheme = MaterialTheme.colorScheme
    AtmosphereScaffold {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "设备扫描",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "选择附近的 BLE 设备进行 OTA",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = scheme.onSurface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                SectionCard(
                    title = if (uiState.isScanning) "正在扫描" else "蓝牙扫描",
                    modifier = Modifier
                        .padding(horizontal = AppDimens.PagePadding)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(space = 12.dp)
                    ) {
                        Text(
                            text = if (uiState.isScanning) {
                                "正在搜索附近 BLE 设备…"
                            } else {
                                "点击开始搜索附近设备"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(weight = 1f)
                        )
                        if (uiState.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(size = 18.dp),
                                strokeWidth = 2.dp,
                                color = scheme.primary
                            )
                        }
                        CompactActionButton(
                            label = if (uiState.isScanning) "停止" else "扫描",
                            onClick = {
                                if (uiState.isScanning) {
                                    viewModel.stopScan()
                                } else {
                                    requestStartScan()
                                }
                            },
                            primary = !uiState.isScanning
                        )
                    }
                }
                Spacer(
                    modifier = Modifier
                        .height(height = AppDimens.SectionGap)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppDimens.PagePadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "附近设备",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "已发现 ${uiState.devices.size} 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    when {
                        uiState.isScanning -> StatusChip(label = "扫描中", color = scheme.tertiary)
                        uiState.errorMessage != null ->
                            StatusChip(label = "扫描失败", color = scheme.error)
                    }
                }
                Spacer(
                    modifier = Modifier
                        .height(height = AppDimens.CardInnerGap)
                )
                if (uiState.devices.isEmpty()) {
                    EmptyState(
                        message = when {
                            uiState.isScanning -> "正在搜索附近设备…"
                            uiState.errorMessage != null -> uiState.errorMessage!!
                            else -> "暂无设备，请点击扫描"
                        },
                        icon = if (uiState.errorMessage != null && !uiState.isScanning) {
                            Icons.Outlined.ErrorOutline
                        } else {
                            Icons.AutoMirrored.Outlined.BluetoothSearching
                        },
                        modifier = Modifier
                            .weight(weight = 1f)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(weight = 1f),
                        contentPadding = PaddingValues(
                            start = AppDimens.PagePadding,
                            end = AppDimens.PagePadding,
                            bottom = AppDimens.PagePadding
                        ),
                        verticalArrangement = Arrangement.spacedBy(space = AppDimens.ListItemGap)
                    ) {
                        items(
                            items = uiState.devices,
                            key = { it.macAddress }
                        ) { device ->
                            DeviceListItem(
                                title = device.displayName,
                                subtitle = device.macAddress,
                                rssi = device.rssi,
                                onSelect = {
                                    viewModel.stopScan()
                                    onDeviceSelected(device)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
