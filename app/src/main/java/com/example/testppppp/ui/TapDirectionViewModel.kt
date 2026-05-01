package com.example.testppppp.ui

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.testppppp.audio.AudioCaptureStatus
import com.example.testppppp.audio.CalibrationSample
import com.example.testppppp.audio.SectorDirection
import com.example.testppppp.audio.TapDetectionConfig
import com.example.testppppp.audio.TapDetectionResult
import com.example.testppppp.audio.calibration.CalibrationRepository
import com.example.testppppp.audio.calibration.CalibrationSession
import com.example.testppppp.audio.classification.TapDirectionClassifier
import com.example.testppppp.audio.detection.StreamingTapDetector
import com.example.testppppp.audio.detection.TapDetectionPipeline
import com.example.testppppp.audio.input.DualChannelAudioRecorder
import com.example.testppppp.remote.InteractionMode
import com.example.testppppp.remote.InteractionModePreferences
import com.example.testppppp.remote.RemoteCommandClient
import com.example.testppppp.remote.RemoteDemoPreferences
import com.example.testppppp.remote.RemoteDemoSettings
import com.example.testppppp.remote.RemotePcAction
import com.example.testppppp.remote.RemoteSendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class TapDirectionViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val config = TapDetectionConfig()
    private val calibrationRepository = CalibrationRepository(application)
    private val remoteDemoPreferences = RemoteDemoPreferences(application)
    private val interactionModePreferences = InteractionModePreferences(application)
    private val classifier = TapDirectionClassifier(config)
    private val pipeline = TapDetectionPipeline(config, classifier)
    private val streamingDetector = StreamingTapDetector(config, pipeline)
    private val recorder = DualChannelAudioRecorder(config)
    private val calibrationSession = CalibrationSession(config.calibrationSamplesPerDirection)
    private val isProcessingFrame = AtomicBoolean(false)
    private var lastCalibrationAcceptedAtMs: Long = 0L
    private var lastTestingTriggeredAtMs: Long = 0L

    private val _uiState = MutableStateFlow(
        TapDirectionUiState(
            permissionGranted = false,
            calibrationProgress = calibrationSession.progress(),
        ),
    )
    val uiState: StateFlow<TapDirectionUiState> = _uiState.asStateFlow()

    init {
        reloadCalibration()
        _uiState.update {
            it.copy(
                remoteDemo = remoteDemoPreferences.load(),
                interactionMode = interactionModePreferences.load(),
            )
        }
    }

    fun setInteractionMode(mode: InteractionMode) {
        val current = _uiState.value.interactionMode
        if (mode == current) return
        interactionModePreferences.save(mode)
        pulseVibrate()
        _uiState.update {
            it.copy(
                interactionMode = mode,
                modeIndicatorPulseGeneration = it.modeIndicatorPulseGeneration + 1L,
            )
        }
    }

    fun dismissMode2Input() {
        _uiState.update { it.copy(mode2PendingInput = null) }
    }

    fun submitMode2Input(userText: String) {
        val pending = _uiState.value.mode2PendingInput ?: return
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) {
            appendLog("模式2：未输入文本，已取消发送")
            dismissMode2Input()
            return
        }
        val remote = _uiState.value.remoteDemo
        if (!remote.enabled) {
            appendLog("模式2：远程未启用")
            dismissMode2Input()
            return
        }
        val sectorKey = pending.sector.name
        _uiState.update { it.copy(mode2PendingInput = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val sendResult = RemoteCommandClient.postLlm(
                baseUrl = remote.baseUrl,
                spatialSector = sectorKey,
                userText = trimmed,
                authToken = remote.authToken,
            )
            val msg = when (sendResult) {
                is RemoteSendResult.Success ->
                    "模式2 Ollama HTTP ${sendResult.httpCode} ${sendResult.bodySnippet}"
                is RemoteSendResult.Failure ->
                    "模式2 请求失败: ${sendResult.message}"
            }
            _uiState.update { it.copy(remoteLastMessage = msg) }
            appendLog(msg)
        }
    }

    fun setRemoteDemoEnabled(enabled: Boolean) {
        val next = _uiState.value.remoteDemo.copy(enabled = enabled)
        persistRemoteDemo(next)
    }

    fun setRemoteBaseUrl(url: String) {
        val next = _uiState.value.remoteDemo.copy(baseUrl = url)
        persistRemoteDemo(next)
    }

    fun setRemoteAuthToken(token: String) {
        val next = _uiState.value.remoteDemo.copy(authToken = token)
        persistRemoteDemo(next)
    }

    fun testRemoteConnection() {
        val settings = _uiState.value.remoteDemo
        viewModelScope.launch(Dispatchers.IO) {
            val result = RemoteCommandClient.getHealth(settings.baseUrl, settings.authToken)
            val msg = when (result) {
                is RemoteSendResult.Success -> "连接成功 HTTP ${result.httpCode} ${result.bodySnippet}"
                is RemoteSendResult.Failure -> "连接失败: ${result.message}"
            }
            _uiState.update { it.copy(remoteLastMessage = msg) }
            appendLog("跨设备测试: $msg")
        }
    }

    private fun persistRemoteDemo(settings: RemoteDemoSettings) {
        remoteDemoPreferences.save(settings)
        _uiState.update { it.copy(remoteDemo = settings) }
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                permissionGranted = granted,
                statusMessage = if (granted) "已获得录音权限，可开始实时检测" else "等待录音权限",
            )
        }
    }

    fun startListening() {
        val state = _uiState.value
        if (!state.permissionGranted) {
            appendLog("录音权限未授予，无法启动采集")
            return
        }
        if (state.audioCaptureStatus is AudioCaptureStatus.Running || state.audioCaptureStatus is AudioCaptureStatus.Starting) {
            return
        }

        streamingDetector.reset()

        recorder.start(
            scope = viewModelScope,
            onStatus = ::handleAudioStatus,
            onFrame = onFrame@{ frame ->
                if (!isProcessingFrame.compareAndSet(false, true)) {
                    return@onFrame
                }
                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        val result = streamingDetector.process(frame)
                        if (result != null) {
                            handleDetectionResult(result)
                        }
                    } finally {
                        isProcessingFrame.set(false)
                    }
                }
            },
        )
    }

    fun stopListening() {
        viewModelScope.launch {
            recorder.stop()
            streamingDetector.reset()
            _uiState.update {
                it.copy(
                    audioCaptureStatus = AudioCaptureStatus.Idle,
                    statusMessage = "实时检测已停止",
                )
            }
        }
    }

    fun startCalibration() {
        calibrationSession.start()
        lastCalibrationAcceptedAtMs = 0L
        _uiState.update {
            it.copy(
                calibrationProgress = calibrationSession.progress(),
                statusMessage = "标定已开始，请按当前方向连续敲击桌面，只有高质量样本会被收录",
            )
        }
        appendLog("开始三区域标定，当前方向：${calibrationSession.currentDirection()?.displayName}")
    }

    fun cancelCalibration() {
        calibrationSession.cancel()
        _uiState.update {
            it.copy(
                calibrationProgress = calibrationSession.progress(),
                statusMessage = "标定已取消",
            )
        }
        appendLog("标定已取消")
    }

    fun clearCalibration() {
        calibrationRepository.clear()
        classifier.updateCalibration(null)
        _uiState.update {
            it.copy(
                hasStoredCalibration = false,
                statusMessage = "已清除本地标定 JSON",
            )
        }
        appendLog("已清除本地标定 JSON")
    }

    private fun handleAudioStatus(status: AudioCaptureStatus) {
        val statusMessage = when (status) {
            AudioCaptureStatus.Idle -> "采集空闲"
            AudioCaptureStatus.Starting -> "正在初始化双通道采集"
            AudioCaptureStatus.Running -> "双通道实时检测中"
            is AudioCaptureStatus.Unsupported -> status.reason
            is AudioCaptureStatus.Error -> status.reason
        }

        _uiState.update {
            it.copy(
                audioCaptureStatus = status,
                statusMessage = statusMessage,
            )
        }
        appendLog(statusMessage)
    }

    private fun handleDetectionResult(result: TapDetectionResult) {
        val now = System.currentTimeMillis()
        val hasCandidate = result.delaySeconds != null && result.debugInfo.chosenPair != null
        if (hasCandidate) {
            val delaySeconds = result.delaySeconds!!
            appendLog(
                "dt=${"%.2f".format(delaySeconds * 1_000_000)}us, " +
                    "conf=${"%.3f".format(result.confidence)}, " +
                    "trigger=${result.triggered}, " +
                    "quality=${"%.1f".format(result.debugInfo.qualityScore)}, " +
                    "energyRatio=${"%.3f".format(result.debugInfo.channelEnergyRatio)}, " +
                    "peakRatio=${"%.3f".format(result.debugInfo.combinedPeakChannelRatio)}, " +
                    "knnVotes=[top:${result.classification?.topVotes ?: 0}, " +
                    "right:${result.classification?.rightVotes ?: 0}, " +
                    "bottom:${result.classification?.bottomVotes ?: 0}], " +
                    "knnScore=[top:${"%.3f".format(result.classification?.topScore ?: 0.0)}, " +
                    "right:${"%.3f".format(result.classification?.rightScore ?: 0.0)}, " +
                    "bottom:${"%.3f".format(result.classification?.bottomScore ?: 0.0)}], " +
                    "stage=${result.classification?.stageDecision ?: "N/A"}, " +
                    "sector=${result.sector?.displayName ?: "N/A"}",
            )
        }

        var calibrationAccepted = false
        var calibrationFeedback: String? = null
        if (calibrationSession.isRunning()) {
            calibrationFeedback = when {
                !hasCandidate -> "标定样本未收录：${result.debugInfo.failureReasons.firstOrNull() ?: "未检测到稳定双击"}"
                now - lastCalibrationAcceptedAtMs < config.calibrationCooldownMs ->
                    "标定样本未收录：冷却中，请稍等再敲击"
                result.debugInfo.qualityScore < config.calibrationAcceptedQualityScore ->
                    "标定样本未收录：质量分 ${"%.1f".format(result.debugInfo.qualityScore)} < ${"%.1f".format(config.calibrationAcceptedQualityScore)}"
                else -> {
                    calibrationAccepted = calibrationSession.addSample(
                        CalibrationSample(
                            delaySeconds = result.delaySeconds!!,
                            channelEnergyRatio = result.debugInfo.channelEnergyRatio.toDouble(),
                            channelPeakRatio = result.debugInfo.combinedPeakChannelRatio.toDouble(),
                            firstPeakChannelRatio = result.debugInfo.firstPeakChannelRatio.toDouble(),
                            secondPeakChannelRatio = result.debugInfo.secondPeakChannelRatio.toDouble(),
                            lagConfidence = result.debugInfo.lagConfidence,
                        ),
                    )
                    if (calibrationAccepted) {
                        lastCalibrationAcceptedAtMs = now
                        "标定采样成功：dt=${"%.2f".format(result.delaySeconds * 1_000_000)}us, energy=${"%.3f".format(result.debugInfo.channelEnergyRatio)}, peak=${"%.3f".format(result.debugInfo.combinedPeakChannelRatio)}, first=${"%.3f".format(result.debugInfo.firstPeakChannelRatio)}, second=${"%.3f".format(result.debugInfo.secondPeakChannelRatio)} -> ${calibrationSession.progress().collectedCounts}"
                    } else {
                        "标定样本未收录：当前方向样本已满"
                    }
                }
            }
            appendLog(calibrationFeedback)

            if (calibrationSession.isComplete()) {
                val data = calibrationSession.toCalibrationData()
                calibrationRepository.save(data)
                classifier.updateCalibration(data)
                appendLog("三区域标定完成，JSON 已保存到应用私有存储")
            }
        }

        val effectiveTriggered = if (result.triggered && now - lastTestingTriggeredAtMs >= config.testingCooldownMs) {
            lastTestingTriggeredAtMs = now
            appendLog(
                "单次高置信触发：sector=${result.sector?.displayName}, " +
                    "confidence=${"%.3f".format(result.confidence)}",
            )
            true
        } else {
            if (result.triggered) {
                appendLog("检测命中但处于测试冷却中，已抑制重复触发")
            }
            false
        }

        if (effectiveTriggered && result.sector != null) {
            val remote = _uiState.value.remoteDemo
            val mode = _uiState.value.interactionMode
            if (remote.enabled) {
                when (mode) {
                    InteractionMode.MODE1_PPT -> {
                        val pcAction = mode1RemoteAction(result.sector)
                        viewModelScope.launch(Dispatchers.IO) {
                            val sendResult = RemoteCommandClient.postCommand(
                                baseUrl = remote.baseUrl,
                                action = pcAction,
                                authToken = remote.authToken,
                            )
                            val msg = when (sendResult) {
                                is RemoteSendResult.Success ->
                                    "跨设备已发送: ${pcAction.jsonName} HTTP ${sendResult.httpCode}"
                                is RemoteSendResult.Failure ->
                                    "跨设备发送失败 (${pcAction.jsonName}): ${sendResult.message}"
                            }
                            _uiState.update { it.copy(remoteLastMessage = msg) }
                            appendLog(msg)
                        }
                    }
                    InteractionMode.MODE2_LLM -> {
                        _uiState.update {
                            it.copy(
                                mode2PendingInput = Mode2PendingInput(
                                    sector = result.sector,
                                    confidence = result.confidence,
                                ),
                            )
                        }
                        appendLog("模式2：请补充文本后将请求发送至 Ollama（扇区=${result.sector.displayName}）")
                    }
                }
            }
        }

        val calibrationProgress = if (calibrationSession.isComplete()) {
            calibrationSession.cancel()
            calibrationSession.progress()
        } else {
            calibrationSession.progress()
        }

        _uiState.update {
            it.copy(
                latestDetection = LatestDetectionSummary(
                    triggered = effectiveTriggered,
                    delayMicroseconds = result.delaySeconds?.times(1_000_000.0),
                    sector = result.sector,
                    confidence = result.confidence,
                    classification = result.classification,
                    debugInfo = result.debugInfo,
                ),
                calibrationProgress = calibrationProgress,
                hasStoredCalibration = classifierHasCalibration(),
                statusMessage = when {
                    calibrationAccepted -> "标定样本已收录：${calibrationSession.progress().collectedCounts}"
                    calibrationFeedback != null -> calibrationFeedback
                    effectiveTriggered -> "检测到有效敲击：${result.sector?.displayName ?: "未知"}"
                    result.classification?.rejectionReason != null -> "已拒识：${result.classification.rejectionReason}"
                    hasCandidate && !it.hasStoredCalibration -> "检测到候选敲击，等待标定数据"
                    hasCandidate -> "检测到候选敲击，但当前未触发"
                    else -> _uiState.value.statusMessage
                },
            )
        }
    }

    private fun reloadCalibration() {
        val stored = calibrationRepository.load()
        classifier.updateCalibration(stored)
        _uiState.update {
            it.copy(
                hasStoredCalibration = stored != null,
                calibrationProgress = calibrationSession.progress(),
                statusMessage = if (stored != null) {
                    "已加载三区域标定 JSON，可直接分类"
                } else {
                    "尚无标定数据，检测结果只输出延迟与调试信息"
                },
            )
        }
    }

    private fun classifierHasCalibration(): Boolean = calibrationRepository.load() != null

    /**
     * 模式1 固定映射：上→上一页，右→显示桌面，下→下一页。
     */
    private fun mode1RemoteAction(sector: SectorDirection): RemotePcAction =
        when (sector) {
            SectorDirection.TOP -> RemotePcAction.PPT_PREVIOUS
            SectorDirection.RIGHT -> RemotePcAction.SHOW_DESKTOP
            SectorDirection.BOTTOM -> RemotePcAction.PPT_NEXT
        }

    @Suppress("DEPRECATION")
    private fun pulseVibrate() {
        val app = getApplication<Application>()
        val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        } else {
            vibrator.vibrate(45)
        }
    }

    private fun appendLog(message: String) {
        Log.d("TapDirection", message)
        val timestamp = System.currentTimeMillis()
        _uiState.update { state ->
            val updatedLogs = listOf(DetectionLogEntry(message, timestamp)) + state.logs
            state.copy(logs = updatedLogs.take(config.maxLogEntries))
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            recorder.stop()
        }
    }
}
