package com.example.testppppp.audio

enum class SectorDirection(val displayName: String) {
    TOP("Top"),
    RIGHT("Right"),
    BOTTOM("Bottom");

    companion object {
        val ordered = listOf(TOP, RIGHT, BOTTOM)
    }
}

data class StereoAudioFrame(
    val left: FloatArray,
    val right: FloatArray,
    val sampleRateHz: Int,
    val frameIndex: Long,
    val startSampleIndex: Long,
)

data class PeakInfo(
    val sampleIndex: Int,
    val timeMs: Float,
    val amplitude: Float,
    val widthMs: Float,
)

data class PeakPairInfo(
    val first: PeakInfo,
    val second: PeakInfo,
    val intervalMs: Float,
    val amplitudeDiff: Float,
    val trailingPeakRatio: Float,
)

data class DetectionQualityMetrics(
    val absolutePeak: Float,
    val snr: Float,
    val nearFieldRatio: Float,
    val transientScore: Float,
    val stereoConsistency: Float,
    val peakSharpness: Float,
    val trailingPeakRatio: Float,
    val peakBalanceRatio: Float,
    val gapScore: Float,
    val channelEnergyRatio: Float,
    val combinedPeakChannelRatio: Float,
    val firstPeakChannelRatio: Float,
    val secondPeakChannelRatio: Float,
    val qualityScore: Double,
)

data class TapCandidate(
    val pair: PeakPairInfo,
    val representativeWidthMs: Float,
    val quality: DetectionQualityMetrics,
    val failureReasons: List<String>,
)

data class GccPhatResult(
    val delaySeconds: Double,
    val primaryPeak: Double,
    val secondaryPeak: Double,
    val peakRatio: Double,
)

data class DirectionStats(
    val meanDelaySeconds: Double,
    val standardDeviationSeconds: Double,
    val meanEnergyRatio: Double,
    val standardDeviationEnergyRatio: Double,
    val meanPeakRatio: Double,
    val standardDeviationPeakRatio: Double,
    val sampleCount: Int,
    val meanLagConfidence: Double = 0.0,
    val standardDeviationLagConfidence: Double = 1.0,
)

data class CalibrationSample(
    val delaySeconds: Double,
    val channelEnergyRatio: Double,
    val channelPeakRatio: Double,
    val firstPeakChannelRatio: Double = channelPeakRatio,
    val secondPeakChannelRatio: Double = channelPeakRatio,
    val lagConfidence: Double = 0.0,
)

data class CalibrationData(
    val directions: Map<SectorDirection, DirectionStats>,
    val rawSamples: Map<SectorDirection, List<CalibrationSample>> = emptyMap(),
) {
    fun isComplete(): Boolean = directions.keys.containsAll(SectorDirection.ordered)
}

data class ClassificationResult(
    val sector: SectorDirection?,
    val confidence: Double,
    val geometryConfidence: Double,
    val signalQualityConfidence: Double,
    val gccSharpnessConfidence: Double,
    val topScore: Double,
    val rightScore: Double,
    val bottomScore: Double,
    val triggered: Boolean,
    val rejectionReason: String?,
    val stageDecision: String = "",
    val topVotes: Int = 0,
    val rightVotes: Int = 0,
    val bottomVotes: Int = 0,
    val topAverageDistance: Double = 0.0,
    val rightAverageDistance: Double = 0.0,
    val bottomAverageDistance: Double = 0.0,
)

data class WaveformSnapshot(
    val left: FloatArray,
    val right: FloatArray,
    val envelope: FloatArray,
    val peakIndices: List<Int>,
)

data class DetectionDebugInfo(
    val peaks: List<PeakInfo>,
    val chosenPair: PeakPairInfo?,
    val snr: Float,
    val absolutePeak: Float,
    val nearFieldRatio: Float,
    val transientScore: Float,
    val stereoConsistency: Float,
    val peakSharpness: Float,
    val trailingPeakRatio: Float,
    val gapScore: Float,
    val channelEnergyRatio: Float,
    val combinedPeakChannelRatio: Float,
    val firstPeakChannelRatio: Float,
    val secondPeakChannelRatio: Float,
    val qualityScore: Double,
    val lagConfidence: Double,
    val peakRatio: Double,
    val primaryPeak: Double,
    val secondaryPeak: Double,
    val rejectionReason: String?,
    val failureReasons: List<String>,
    val waveformSnapshot: WaveformSnapshot,
)

data class TapDetectionResult(
    val triggered: Boolean,
    val delaySeconds: Double?,
    val sector: SectorDirection?,
    val confidence: Double,
    val debugInfo: DetectionDebugInfo,
    val classification: ClassificationResult?,
)

sealed interface AudioCaptureStatus {
    data object Idle : AudioCaptureStatus
    data object Starting : AudioCaptureStatus
    data object Running : AudioCaptureStatus
    data class Unsupported(val reason: String) : AudioCaptureStatus
    data class Error(val reason: String) : AudioCaptureStatus
}
