package com.example.testppppp.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.testppppp.audio.AudioCaptureStatus
import com.example.testppppp.audio.CalibrationSample
import com.example.testppppp.audio.TapDetectionConfig
import com.example.testppppp.audio.TapDetectionResult
import com.example.testppppp.audio.calibration.CalibrationRepository
import com.example.testppppp.audio.calibration.CalibrationSession
import com.example.testppppp.audio.classification.TapDirectionClassifier
import com.example.testppppp.audio.detection.StreamingTapDetector
import com.example.testppppp.audio.detection.TapDetectionPipeline
import com.example.testppppp.audio.input.DualChannelAudioRecorder
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
            true
        } else {
            if (result.triggered) {
                appendLog("检测命中但处于测试冷却中，已抑制重复触发")
            }
            false
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
