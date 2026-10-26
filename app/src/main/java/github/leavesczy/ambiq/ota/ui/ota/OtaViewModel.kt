package github.leavesczy.ambiq.ota.ui.ota

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import github.leavesczy.ambiq.ota.firmware.FirmwareStore
import github.leavesczy.ambiq.ota.ota.OtaClient
import github.leavesczy.ambiq.ota.ota.OtaClientListener
import github.leavesczy.ambiq.ota.ota.OtaError
import github.leavesczy.ambiq.ota.ota.OtaPhase
import github.leavesczy.ambiq.ota.ota.OtaProgress
import github.leavesczy.ambiq.ota.ota.OtaResult
import github.leavesczy.ambiq.ota.ota.logText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OTA 升级页：固件选择、直接驱动 GATT 会话、进度与耗时。
 *
 * 进度来自 [OtaClientListener.onProgress]；成败与取消以 [OtaResult] 为准。
 */
class OtaViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(OtaUiState())
    val uiState: StateFlow<OtaUiState> = _uiState.asStateFlow()

    private val otaClient = OtaClient(context = application.applicationContext)
    private val otaRunning = AtomicBoolean(false)
    private var otaJob: Job? = null
    private var elapsedJob: Job? = null
    private var otaStartedAtMs: Long = 0L

    init {
        otaClient.setListener(
            listener = object : OtaClientListener {
                override fun onProgress(progress: OtaProgress) {
                    _uiState.update { state ->
                        state.copy(
                            phase = progress.phase,
                            progressPercent = progress.percent,
                            errorDetail = when (progress.phase) {
                                OtaPhase.Success -> null
                                else -> state.errorDetail
                            }
                        )
                    }
                }
            }
        )
    }

    fun onFirmwarePicked(uri: Uri?) {
        if (uri == null || _uiState.value.isRunning) {
            return
        }
        viewModelScope.launch {
            try {
                val cached = withContext(context = Dispatchers.IO) {
                    FirmwareStore.cacheFromUri(
                        context = getApplication(),
                        uri = uri
                    )
                }
                _uiState.update {
                    it.copy(
                        firmwareName = cached.displayName,
                        firmwarePath = cached.absolutePath,
                        firmwareSizeBytes = cached.sizeBytes,
                        statusMessage = "",
                        errorDetail = null,
                        isSucceeded = false,
                        phase = OtaPhase.Idle,
                        progressPercent = 0.0,
                        elapsedMs = 0L
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(toastMessage = e.message ?: "选择固件失败")
                }
            }
        }
    }

    fun startOta(macAddress: String) {
        val path = _uiState.value.firmwarePath ?: return
        val trimmedMac = macAddress.trim()
        if (trimmedMac.isEmpty() || _uiState.value.isRunning) {
            return
        }
        if (!otaRunning.compareAndSet(false, true)) {
            return
        }
        val firmwareFile = File(path)
        if (!firmwareFile.isFile) {
            otaRunning.set(false)
            _uiState.update {
                it.copy(
                    toastMessage = "固件文件不存在",
                    errorDetail = "file not found: $path"
                )
            }
            return
        }
        otaStartedAtMs = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                isRunning = true,
                isSucceeded = false,
                progressPercent = 0.0,
                phase = OtaPhase.Preparing,
                errorDetail = null,
                statusMessage = "正在传输固件…",
                elapsedMs = 0L
            )
        }
        startElapsedTicker()
        otaJob = viewModelScope.launch {
            try {
                when (
                    val result = otaClient.startOta(
                        macAddress = trimmedMac,
                        firmwareFile = firmwareFile
                    )
                ) {
                    is OtaResult.Success -> {
                        stopElapsedTicker()
                        _uiState.update {
                            it.copy(
                                isRunning = false,
                                isSucceeded = true,
                                phase = OtaPhase.Success,
                                progressPercent = 100.0,
                                statusMessage = "升级成功，设备即将重启",
                                errorDetail = null
                            )
                        }
                    }

                    is OtaResult.Cancelled -> {
                        applyTerminalError(error = OtaError.Cancelled)
                    }

                    is OtaResult.Failed -> {
                        applyTerminalError(error = result.error)
                    }
                }
            } finally {
                otaRunning.set(false)
            }
        }
    }

    fun cancelOta() {
        if (_uiState.value.isRunning) {
            otaClient.cancelOta()
        }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    /**
     * 离开升级页：取消进行中的会话并清空 UI，避免按设备 key 复用 ViewModel 时残留进度。
     */
    fun onLeaveScreen() {
        if (_uiState.value.isRunning) {
            otaClient.cancelOta()
        }
        otaJob?.cancel()
        otaJob = null
        stopElapsedTicker()
        otaRunning.set(false)
        _uiState.value = OtaUiState()
    }

    override fun onCleared() {
        stopElapsedTicker()
        otaJob?.cancel()
        otaClient.close()
        super.onCleared()
    }

    private fun applyTerminalError(error: OtaError) {
        stopElapsedTicker()
        val cancelled = error is OtaError.Cancelled
        _uiState.update {
            it.copy(
                isRunning = false,
                isSucceeded = false,
                phase = if (cancelled) OtaPhase.Cancelled else OtaPhase.Failed,
                statusMessage = if (cancelled) "升级已取消" else "升级失败",
                errorDetail = error.logText()
            )
        }
    }

    private fun startElapsedTicker() {
        stopElapsedTicker()
        elapsedJob = viewModelScope.launch {
            while (isActive) {
                _uiState.update {
                    it.copy(elapsedMs = System.currentTimeMillis() - otaStartedAtMs)
                }
                delay(timeMillis = 200)
            }
        }
    }

    private fun stopElapsedTicker() {
        elapsedJob?.cancel()
        elapsedJob = null
    }

}
