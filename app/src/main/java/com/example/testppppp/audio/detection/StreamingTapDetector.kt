package com.example.testppppp.audio.detection

import com.example.testppppp.audio.PeakInfo
import com.example.testppppp.audio.StereoAudioFrame
import com.example.testppppp.audio.TapDetectionConfig
import com.example.testppppp.audio.TapDetectionResult
import com.example.testppppp.audio.dsp.SignalProcessing

class StreamingTapDetector(
    private val config: TapDetectionConfig,
    private val pipeline: TapDetectionPipeline,
) {
    private val historyCapacitySamples = config.eventWindowSizeSamples + config.windowSizeSamples * 3
    private var historyLeft = FloatArray(historyCapacitySamples)
    private var historyRight = FloatArray(historyCapacitySamples)
    private var historySampleCount = 0
    private var historyStartSampleIndex = 0L
    private var nextExpectedSampleIndex: Long? = null
    private var pendingTriggerSampleIndex: Long? = null
    private var lastCompletedTriggerSampleIndex = Long.MIN_VALUE

    fun process(frame: StereoAudioFrame): TapDetectionResult? {
        appendFrame(frame)

        if (pendingTriggerSampleIndex == null) {
            pendingTriggerSampleIndex = detectTrigger(frame)
        }

        val triggerSampleIndex = pendingTriggerSampleIndex ?: return null
        val eventStartSampleIndex = triggerSampleIndex - config.eventPreTriggerSamples
        val eventEndSampleIndex = eventStartSampleIndex + config.eventWindowSizeSamples
        val latestAvailableSampleIndex = historyStartSampleIndex + historySampleCount

        if (eventStartSampleIndex < historyStartSampleIndex || latestAvailableSampleIndex < eventEndSampleIndex) {
            return null
        }

        val localStart = (eventStartSampleIndex - historyStartSampleIndex).toInt()
        val localEnd = localStart + config.eventWindowSizeSamples
        val result = pipeline.process(
            StereoAudioFrame(
                left = historyLeft.copyOfRange(localStart, localEnd),
                right = historyRight.copyOfRange(localStart, localEnd),
                sampleRateHz = config.sampleRateHz,
                frameIndex = frame.frameIndex,
                startSampleIndex = eventStartSampleIndex,
            ),
        )

        pendingTriggerSampleIndex = null
        lastCompletedTriggerSampleIndex = triggerSampleIndex
        return result
    }

    fun reset() {
        historySampleCount = 0
        historyStartSampleIndex = 0L
        nextExpectedSampleIndex = null
        pendingTriggerSampleIndex = null
        lastCompletedTriggerSampleIndex = Long.MIN_VALUE
    }

    private fun appendFrame(frame: StereoAudioFrame) {
        val expected = nextExpectedSampleIndex
        val appendStart = when {
            expected == null -> 0
            frame.startSampleIndex >= expected -> 0
            else -> (expected - frame.startSampleIndex).toInt().coerceAtMost(frame.left.size)
        }

        if (expected != null && frame.startSampleIndex > expected) {
            reset()
        }

        val appendedSamples = frame.left.size - appendStart
        if (appendedSamples <= 0) {
            nextExpectedSampleIndex = frame.startSampleIndex + frame.left.size
            return
        }

        ensureCapacity(appendedSamples)
        val destinationStart = historySampleCount
        System.arraycopy(frame.left, appendStart, historyLeft, destinationStart, appendedSamples)
        System.arraycopy(frame.right, appendStart, historyRight, destinationStart, appendedSamples)
        if (historySampleCount == 0) {
            historyStartSampleIndex = frame.startSampleIndex + appendStart
        }
        historySampleCount += appendedSamples
        nextExpectedSampleIndex = frame.startSampleIndex + frame.left.size
    }

    private fun detectTrigger(frame: StereoAudioFrame): Long? {
        val filteredLeft = SignalProcessing.bandPass(
            signal = frame.left,
            sampleRateHz = frame.sampleRateHz,
            lowCutHz = config.bandPassLowHz,
            highCutHz = config.bandPassHighHz,
        )
        val filteredRight = SignalProcessing.bandPass(
            signal = frame.right,
            sampleRateHz = frame.sampleRateHz,
            lowCutHz = config.bandPassLowHz,
            highCutHz = config.bandPassHighHz,
        )
        val merged = SignalProcessing.mergeChannels(filteredLeft, filteredRight)
        val envelope = SignalProcessing.envelope(
            signal = merged,
            smoothingWindowSamples = (config.envelopeSmoothingMs * frame.sampleRateHz / 1_000).coerceAtLeast(1),
        )
        val peaks = SignalProcessing.detectLocalPeaks(
            envelope = envelope,
            threshold = config.absolutePeakThreshold,
            minPeakDistanceSamples = (config.minPeakDistanceMs * frame.sampleRateHz / 1_000).coerceAtLeast(1),
            sampleRateHz = frame.sampleRateHz,
        )
        if (peaks.isEmpty()) {
            return null
        }

        val recentStart = (frame.left.size - config.triggerLookbackSamples).coerceAtLeast(0)
        val triggerPeak = peaks
            .filter { it.sampleIndex >= recentStart }
            .maxByOrNull(PeakInfo::amplitude)
            ?: return null

        val triggerSampleIndex = frame.startSampleIndex + triggerPeak.sampleIndex
        if (triggerSampleIndex - historyStartSampleIndex < config.eventPreTriggerSamples) {
            return null
        }
        if (triggerSampleIndex <= lastCompletedTriggerSampleIndex + config.eventTriggerCooldownSamples) {
            return null
        }
        return triggerSampleIndex
    }

    private fun ensureCapacity(neededSamples: Int) {
        if (historySampleCount + neededSamples <= historyCapacitySamples) {
            return
        }

        val samplesToDrop = (historySampleCount + neededSamples - historyCapacitySamples)
            .coerceAtMost(historySampleCount)
        if (samplesToDrop > 0) {
            val remaining = historySampleCount - samplesToDrop
            System.arraycopy(historyLeft, samplesToDrop, historyLeft, 0, remaining)
            System.arraycopy(historyRight, samplesToDrop, historyRight, 0, remaining)
            historySampleCount = remaining
            historyStartSampleIndex += samplesToDrop
        }
    }
}
