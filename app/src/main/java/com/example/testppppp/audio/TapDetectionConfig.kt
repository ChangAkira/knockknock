package com.example.testppppp.audio

data class TapDetectionConfig(
    val sampleRateHz: Int = 16_000,
    val windowMs: Int = 100,
    val hopMs: Int = 30,
    val eventPreTriggerMs: Int = 40,
    val eventPostTriggerMs: Int = 360,
    val triggerLookbackMs: Int = 45,
    val eventTriggerCooldownMs: Int = 220,
    val minPeakDistanceMs: Int = 35,
    val envelopeSmoothingMs: Int = 3,
    val peakWindowMs: Int = 18,
    val backgroundWindowMs: Int = 36,
    val bandPassLowHz: Float = 100f,
    val bandPassHighHz: Float = 4_000f,
    val absolutePeakThreshold: Float = 0.010f,
    val snrThreshold: Float = 6.0f,
    val minNearFieldRatio: Float = 2.2f,
    val minTransientScore: Float = 4.8f,
    val minStereoConsistency: Float = 0.10f,
    val maxPeakWidthMs: Float = 24f,
    val minPeakSharpness: Float = 1.05f,
    val minPeakPairGapMs: Float = 35f,
    val maxPeakPairGapMs: Float = 360f,
    val minTrailingPeakRatio: Float = 0.24f,
    val idealTrailingPeakRatio: Float = 0.62f,
    val maxTrailingPeakRatio: Float = 1.55f,
    val minPeakBalanceRatio: Float = 0.30f,
    val idealGapMs: Float = 165f,
    val idealGapToleranceMs: Float = 190f,
    val minGapScore: Float = 0.45f,
    val microphoneDistanceMeters: Float = 0.12f,
    val soundSpeedMetersPerSecond: Float = 343f,
    val testingConfidenceThreshold: Double = 0.18,
    val boundaryMarginSeconds: Double = 0.00003,
    val minPeakRatio: Double = 1.04,
    val lowLagConfidenceHint: Double = 0.08,
    val lagConfidenceMultiplier: Double = 3.2,
    val gccApplyHannWindow: Boolean = true,
    val gccMultiBandEnabled: Boolean = true,
    val gccMinSubBandWidthHz: Float = 80f,
    val gccSubBandEdgesHz: List<Float> = listOf(100f, 1_400f, 2_700f, 4_000f),
    val gccEventWindowMs: Int = 70,
    val gccLagStddevPenaltyThresholdSamples: Double = 1.2,
    val gccLagStddevPenaltyFloor: Double = 0.55,
    val calibrationSamplesPerDirection: Int = 12,
    val calibrationAcceptedQualityScore: Double = 56.0,
    val testingAcceptedQualityScore: Double = 52.0,
    val calibrationCooldownMs: Long = 900L,
    val testingCooldownMs: Long = 900L,
    val statModelEnabled: Boolean = true,
    val statModelDistanceRejectThreshold: Double = 6.5,
    val statModelMarginReject: Double = 0.10,
    val maxLogEntries: Int = 120,
) {
    val windowSizeSamples: Int = sampleRateHz * windowMs / 1_000
    val hopSizeSamples: Int = sampleRateHz * hopMs / 1_000
    val eventPreTriggerSamples: Int = sampleRateHz * eventPreTriggerMs / 1_000
    val eventPostTriggerSamples: Int = sampleRateHz * eventPostTriggerMs / 1_000
    val eventWindowSizeSamples: Int = eventPreTriggerSamples + eventPostTriggerSamples
    val triggerLookbackSamples: Int = sampleRateHz * triggerLookbackMs / 1_000
    val eventTriggerCooldownSamples: Int = sampleRateHz * eventTriggerCooldownMs / 1_000
    val maxLagSamples: Int = ((microphoneDistanceMeters / soundSpeedMetersPerSecond) * sampleRateHz)
        .toInt()
        .coerceAtLeast(1)
}
