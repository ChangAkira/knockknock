package com.example.testppppp.ui

import com.example.testppppp.remote.InteractionMode
import com.example.testppppp.remote.RemoteDemoSettings
import com.example.testppppp.audio.AudioCaptureStatus
import com.example.testppppp.audio.ClassificationResult
import com.example.testppppp.audio.DetectionDebugInfo
import com.example.testppppp.audio.SectorDirection
import com.example.testppppp.audio.calibration.CalibrationProgress

data class DetectionLogEntry(
    val message: String,
    val timestampMs: Long,
)

data class Mode2PendingInput(
    val sector: SectorDirection,
    val confidence: Double,
)

data class LatestDetectionSummary(
    val triggered: Boolean,
    val delayMicroseconds: Double?,
    val sector: SectorDirection?,
    val confidence: Double,
    val classification: ClassificationResult?,
    val debugInfo: DetectionDebugInfo?,
)

data class TapDirectionUiState(
    val permissionGranted: Boolean = false,
    val audioCaptureStatus: AudioCaptureStatus = AudioCaptureStatus.Idle,
    val latestDetection: LatestDetectionSummary? = null,
    val logs: List<DetectionLogEntry> = emptyList(),
    val calibrationProgress: CalibrationProgress = CalibrationProgress.idle(targetSamplesPerDirection = 12),
    val hasStoredCalibration: Boolean = false,
    val statusMessage: String = "等待录音权限",
    val remoteDemo: RemoteDemoSettings = RemoteDemoSettings(),
    val remoteLastMessage: String? = null,
    val interactionMode: InteractionMode = InteractionMode.MODE1_PPT,
    /** 每次切换模式时递增，用于触发模式标示缩放动画 */
    val modeIndicatorPulseGeneration: Long = 0L,
    val mode2PendingInput: Mode2PendingInput? = null,
)
