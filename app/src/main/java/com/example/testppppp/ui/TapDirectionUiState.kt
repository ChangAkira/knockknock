package com.example.testppppp.ui

import com.example.testppppp.audio.AudioCaptureStatus
import com.example.testppppp.audio.ClassificationResult
import com.example.testppppp.audio.DetectionDebugInfo
import com.example.testppppp.audio.SectorDirection
import com.example.testppppp.audio.calibration.CalibrationProgress

data class DetectionLogEntry(
    val message: String,
    val timestampMs: Long,
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
)
