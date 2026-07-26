package github.leavesczy.ambiq.ota.ui.ota

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import github.leavesczy.ambiq.ota.ble.ScannedDevice
import github.leavesczy.ambiq.ota.ui.components.PrimaryActionButton
import github.leavesczy.ambiq.ota.ui.components.SecondaryActionButton
import github.leavesczy.ambiq.ota.ui.components.SectionCard
import github.leavesczy.ambiq.ota.ui.components.StatusChip
import github.leavesczy.ambiq.ota.ui.theme.AppDimens
import github.leavesczy.ambiq.ota.ui.theme.AtmosphereScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtaScreen(
    device: ScannedDevice,
    onBack: () -> Unit,
    viewModel: OtaViewModel = viewModel(key = "ota_${device.macAddress}")
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(device.macAddress) {
        onDispose {
            viewModel.onLeaveScreen()
        }
    }

    fun navigateBack() {
        if (uiState.isRunning) {
            return
        }
        onBack()
    }

    BackHandler {
        navigateBack()
    }

    val pickFirmwareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        viewModel.onFirmwarePicked(uri = uri)
    }

    LaunchedEffect(uiState.toastMessage) {
        val message = uiState.toastMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.consumeToast()
    }

    val scheme = MaterialTheme.colorScheme
    val phaseColor by animateColorAsState(
        targetValue = uiState.phase.accentColor(scheme = scheme),
        label = "phaseColor"
    )
    val animatedProgress by animateFloatAsState(
        targetValue = uiState.progressPercent / 100f,
        label = "progress"
    )

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
                                text = "固件升级",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = device.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = ::navigateBack,
                            enabled = !uiState.isRunning
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = AppDimens.PagePadding,
                        end = AppDimens.PagePadding,
                        top = 4.dp,
                        bottom = 28.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(AppDimens.SectionGap)
            ) {
                SectionCard(title = "目标设备") {
                    Text(
                        text = device.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(AppDimens.TextLineGap))
                    Text(
                        text = device.macAddress,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = scheme.onSurfaceVariant
                    )
                    if (uiState.statusMessage.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(AppDimens.CardInnerGap))
                        StatusChip(
                            label = uiState.statusMessage,
                            color = when {
                                uiState.isSucceeded -> scheme.tertiary
                                uiState.errorDetail != null && !uiState.isRunning -> scheme.error
                                else -> scheme.primary
                            }
                        )
                    }
                }
                SectionCard(title = "固件文件") {
                    Text(
                        text = uiState.firmwareName ?: "未选择固件",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (uiState.firmwareName != null) {
                            FontWeight.Medium
                        } else {
                            FontWeight.Normal
                        },
                        color = if (uiState.firmwareName != null) {
                            scheme.onSurface
                        } else {
                            scheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.height(AppDimens.TextLineGap))
                    Text(
                        text = buildString {
                            append("Ambiq packed 固件（update_binary_*.bin，含 48 字节头）")
                            uiState.firmwareSizeBytes?.let { size ->
                                append(" · ")
                                append(formatFileSize(bytes = size))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AppDimens.CardInnerGap))
                    SecondaryActionButton(
                        label = if (uiState.firmwareName == null) "选择固件" else "重新选择",
                        onClick = {
                            pickFirmwareLauncher.launch(
                                arrayOf("*/*", "application/octet-stream")
                            )
                        },
                        enabled = uiState.canPickFirmware
                    )
                }
                SectionCard(title = "升级进度") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = uiState.phase.displayLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = phaseColor,
                            modifier = Modifier.weight(1f)
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${uiState.progressPercent}%",
                                style = MaterialTheme.typography.headlineSmall,
                                color = phaseColor
                            )
                            if (uiState.isRunning || uiState.elapsedMs > 0) {
                                Text(
                                    text = "耗时 ${formatElapsed(uiState.elapsedMs)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(AppDimens.CardInnerGap))
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(AppDimens.ProgressHeight)
                            .clip(RoundedCornerShape(AppDimens.RadiusSm)),
                        color = phaseColor,
                        trackColor = scheme.surfaceContainerHighest
                    )
                    if (uiState.errorDetail != null) {
                        Spacer(modifier = Modifier.height(AppDimens.CardInnerGap))
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = scheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.errorDetail!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.error
                            )
                        }
                    }
                    if (uiState.isSucceeded) {
                        Spacer(modifier = Modifier.height(AppDimens.CardInnerGap))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = scheme.tertiary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "升级成功，设备即将重启",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.tertiary
                            )
                        }
                    }
                }
                if (uiState.isRunning) {
                    SecondaryActionButton(
                        label = "取消升级",
                        onClick = viewModel::cancelOta
                    )
                } else {
                    PrimaryActionButton(
                        label = "开始 OTA 升级",
                        enabled = uiState.canStart,
                        onClick = { viewModel.startOta(macAddress = device.macAddress) }
                    )
                }
            }
        }
    }
}
