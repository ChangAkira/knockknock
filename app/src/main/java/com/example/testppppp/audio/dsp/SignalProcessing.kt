package com.example.testppppp.audio.dsp

import com.example.testppppp.audio.PeakInfo
import kotlin.math.abs
import kotlin.math.max

object SignalProcessing {
    fun bandPass(
        signal: FloatArray,
        sampleRateHz: Int,
        lowCutHz: Float,
        highCutHz: Float,
    ): FloatArray {
        val highPassed = highPass(signal, sampleRateHz, lowCutHz)
        return lowPass(highPassed, sampleRateHz, highCutHz)
    }

    fun normalize(left: FloatArray, right: FloatArray): Pair<FloatArray, FloatArray> {
        val maxLeft = left.maxOfOrNull { abs(it) } ?: 0f
        val maxRight = right.maxOfOrNull { abs(it) } ?: 0f
        val scale = max(maxLeft, maxRight).coerceAtLeast(1e-6f)
        return Pair(
            FloatArray(left.size) { index -> left[index] / scale },
            FloatArray(right.size) { index -> right[index] / scale },
        )
    }

    fun envelope(signal: FloatArray, smoothingWindowSamples: Int): FloatArray {
        val absSignal = FloatArray(signal.size) { index -> abs(signal[index]) }
        if (smoothingWindowSamples <= 1) {
            return absSignal
        }

        val output = FloatArray(signal.size)
        var accumulator = 0f
        for (index in absSignal.indices) {
            accumulator += absSignal[index]
            if (index >= smoothingWindowSamples) {
                accumulator -= absSignal[index - smoothingWindowSamples]
            }
            val denominator = minOf(index + 1, smoothingWindowSamples)
            output[index] = accumulator / denominator
        }
        return output
    }

    fun mergeChannels(left: FloatArray, right: FloatArray): FloatArray {
        return FloatArray(left.size) { index ->
            max(abs(left[index]), abs(right[index]))
        }
    }

    fun detectLocalPeaks(
        envelope: FloatArray,
        threshold: Float,
        minPeakDistanceSamples: Int,
        sampleRateHz: Int,
    ): List<PeakInfo> {
        val peaks = mutableListOf<PeakInfo>()
        var lastPeakIndex = -minPeakDistanceSamples
        for (index in 1 until envelope.lastIndex) {
            val current = envelope[index]
            val isPeak = current >= envelope[index - 1] &&
                current > envelope[index + 1] &&
                current >= threshold &&
                index - lastPeakIndex >= minPeakDistanceSamples
            if (!isPeak) {
                continue
            }

            val widthMs = estimatePeakWidth(envelope, index, sampleRateHz)
            peaks += PeakInfo(
                sampleIndex = index,
                timeMs = index * 1_000f / sampleRateHz,
                amplitude = current,
                widthMs = widthMs,
            )
            lastPeakIndex = index
        }
        return peaks
    }

    fun localSnr(
        signal: FloatArray,
        centerSample: Int,
        peakWindowSamples: Int,
        backgroundWindowSamples: Int,
    ): Float {
        val peakStart = (centerSample - peakWindowSamples / 2).coerceAtLeast(0)
        val peakEnd = (centerSample + peakWindowSamples / 2).coerceAtMost(signal.lastIndex)

        var peakEnergy = 0f
        for (index in peakStart..peakEnd) {
            val value = signal[index]
            peakEnergy += value * value
        }
        peakEnergy /= (peakEnd - peakStart + 1).coerceAtLeast(1)

        val backgroundEnd = (peakStart - 1).coerceAtLeast(0)
        val backgroundStart = (backgroundEnd - backgroundWindowSamples + 1).coerceAtLeast(0)

        var backgroundEnergy = 0f
        var backgroundCount = 0
        if (backgroundEnd >= backgroundStart) {
            for (index in backgroundStart..backgroundEnd) {
                val value = signal[index]
                backgroundEnergy += value * value
                backgroundCount++
            }
        }

        backgroundEnergy = if (backgroundCount > 0) {
            backgroundEnergy / backgroundCount
        } else {
            1e-6f
        }

        return peakEnergy / backgroundEnergy.coerceAtLeast(1e-6f)
    }

    private fun estimatePeakWidth(
        envelope: FloatArray,
        peakIndex: Int,
        sampleRateHz: Int,
    ): Float {
        val halfAmplitude = envelope[peakIndex] * 0.5f
        var left = peakIndex
        while (left > 0 && envelope[left] > halfAmplitude) {
            left--
        }

        var right = peakIndex
        while (right < envelope.lastIndex && envelope[right] > halfAmplitude) {
            right++
        }

        return ((right - left).coerceAtLeast(1) * 1_000f) / sampleRateHz
    }

    private fun lowPass(signal: FloatArray, sampleRateHz: Int, cutoffHz: Float): FloatArray {
        val dt = 1f / sampleRateHz
        val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz.coerceAtLeast(1f))
        val alpha = dt / (rc + dt)
        val output = FloatArray(signal.size)
        if (signal.isEmpty()) {
            return output
        }
        output[0] = signal[0]
        for (index in 1 until signal.size) {
            output[index] = output[index - 1] + alpha * (signal[index] - output[index - 1])
        }
        return output
    }

    private fun highPass(signal: FloatArray, sampleRateHz: Int, cutoffHz: Float): FloatArray {
        val dt = 1f / sampleRateHz
        val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz.coerceAtLeast(1f))
        val alpha = rc / (rc + dt)
        val output = FloatArray(signal.size)
        if (signal.isEmpty()) {
            return output
        }
        output[0] = signal[0]
        for (index in 1 until signal.size) {
            output[index] = alpha * (output[index - 1] + signal[index] - signal[index - 1])
        }
        return output
    }
}
