package com.example.testppppp.audio.dsp

import com.example.testppppp.audio.GccPhatResult
import com.example.testppppp.audio.TapDetectionConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.sqrt

class GccPhatProcessor(
    private val config: TapDetectionConfig,
) {
    fun estimateDelay(left: FloatArray, right: FloatArray): GccPhatResult {
        require(left.size == right.size) { "GCC requires equal-length channels" }
        if (!config.gccMultiBandEnabled) {
            return estimateDelaySingleBand(left, right)
        }
        val edges = config.gccSubBandEdgesHz
        if (edges.size < 2) {
            return estimateDelaySingleBand(left, right)
        }
        val bandResults = mutableListOf<GccPhatResult>()
        val bandWeights = mutableListOf<Double>()
        for (index in 0 until edges.lastIndex) {
            val low = max(edges[index], config.bandPassLowHz)
            val high = min(edges[index + 1], config.bandPassHighHz)
            if (high - low < config.gccMinSubBandWidthHz) {
                continue
            }
            val leftBand = SignalProcessing.bandPass(left, config.sampleRateHz, low, high)
            val rightBand = SignalProcessing.bandPass(right, config.sampleRateHz, low, high)
            val (normalizedLeft, normalizedRight) = SignalProcessing.normalize(leftBand, rightBand)
            val result = estimateDelaySingleBand(normalizedLeft, normalizedRight)
            val weight = result.peakRatio * result.primaryPeak
            if (weight.isFinite() && weight > 0.0) {
                bandResults += result
                bandWeights += weight
            }
        }
        if (bandResults.isEmpty()) {
            return estimateDelaySingleBand(left, right)
        }
        if (bandResults.size == 1) {
            return bandResults[0]
        }
        return fuseBandResults(bandResults, bandWeights)
    }

    private fun fuseBandResults(
        results: List<GccPhatResult>,
        weights: List<Double>,
    ): GccPhatResult {
        val totalWeight = weights.sum().coerceAtLeast(1e-12)
        val sampleRate = config.sampleRateHz.toDouble()
        var fusedLagSamples = 0.0
        var fusedPeakRatio = 0.0
        var fusedPrimary = 0.0
        var fusedSecondary = 0.0
        for (index in results.indices) {
            val w = weights[index]
            val r = results[index]
            fusedLagSamples += w * (r.delaySeconds * sampleRate)
            fusedPeakRatio += w * r.peakRatio
            fusedPrimary += w * r.primaryPeak
            fusedSecondary += w * r.secondaryPeak
        }
        fusedLagSamples /= totalWeight
        fusedPeakRatio /= totalWeight
        fusedPrimary /= totalWeight
        fusedSecondary /= totalWeight

        // 多频段时延如果不一致，通常意味着反射/桌面传播主导；对 peakRatio 做惩罚，让后续 lagConfidence 更保守。
        val weightedStddevSamples = weightedStddevLagSamples(results, weights, meanLagSamples = fusedLagSamples)
        val penalty = if (weightedStddevSamples <= config.gccLagStddevPenaltyThresholdSamples) {
            1.0
        } else {
            val t = config.gccLagStddevPenaltyThresholdSamples.coerceAtLeast(1e-6)
            val raw = t / weightedStddevSamples
            raw.coerceIn(config.gccLagStddevPenaltyFloor.coerceIn(0.0, 1.0), 1.0)
        }

        return GccPhatResult(
            delaySeconds = fusedLagSamples / sampleRate,
            primaryPeak = fusedPrimary,
            secondaryPeak = fusedSecondary.coerceAtLeast(1e-9),
            peakRatio = fusedPeakRatio * penalty,
        )
    }

    private fun weightedStddevLagSamples(
        results: List<GccPhatResult>,
        weights: List<Double>,
        meanLagSamples: Double,
    ): Double {
        var wSum = 0.0
        var varSum = 0.0
        val sampleRate = config.sampleRateHz.toDouble()
        for (index in results.indices) {
            val w = weights[index]
            if (!w.isFinite() || w <= 0.0) continue
            val lag = results[index].delaySeconds * sampleRate
            val delta = lag - meanLagSamples
            varSum += w * delta * delta
            wSum += w
        }
        if (wSum <= 1e-12) return Double.POSITIVE_INFINITY
        return sqrt(varSum / wSum)
    }

    private fun estimateDelaySingleBand(left: FloatArray, right: FloatArray): GccPhatResult {
        val windowedLeft = if (config.gccApplyHannWindow) applyHann(left) else left
        val windowedRight = if (config.gccApplyHannWindow) applyHann(right) else right
        return gccPhatCore(windowedLeft, windowedRight)
    }

    private fun applyHann(signal: FloatArray): FloatArray {
        val n = signal.size
        if (n <= 1) {
            return signal.copyOf()
        }
        val denominator = (n - 1).coerceAtLeast(1)
        return FloatArray(n) { index ->
            val w = 0.5 * (1.0 - cos(2.0 * PI * index / denominator))
            signal[index] * w.toFloat()
        }
    }

    private fun gccPhatCore(left: FloatArray, right: FloatArray): GccPhatResult {
        val n = FftUtils.nextPowerOfTwo(left.size * 2)
        val leftReal = DoubleArray(n)
        val leftImag = DoubleArray(n)
        val rightReal = DoubleArray(n)
        val rightImag = DoubleArray(n)

        for (index in left.indices) {
            leftReal[index] = left[index].toDouble()
            rightReal[index] = right[index].toDouble()
        }

        FftUtils.fft(leftReal, leftImag, inverse = false)
        FftUtils.fft(rightReal, rightImag, inverse = false)

        val crossReal = DoubleArray(n)
        val crossImag = DoubleArray(n)
        for (index in 0 until n) {
            val real = leftReal[index] * rightReal[index] + leftImag[index] * rightImag[index]
            val imag = leftImag[index] * rightReal[index] - leftReal[index] * rightImag[index]
            val magnitude = sqrt(real * real + imag * imag).coerceAtLeast(1e-12)
            crossReal[index] = real / magnitude
            crossImag[index] = imag / magnitude
        }

        FftUtils.fft(crossReal, crossImag, inverse = true)
        val shiftedCorrelation = DoubleArray(n)
        for (index in 0 until n) {
            shiftedCorrelation[index] = crossReal[(index + n / 2) % n]
        }

        val center = n / 2
        val searchStart = center - config.maxLagSamples
        val searchEnd = center + config.maxLagSamples
        var primaryIndex = center
        var primaryValue = Double.NEGATIVE_INFINITY
        var secondaryValue = Double.NEGATIVE_INFINITY

        for (index in searchStart..searchEnd) {
            val value = abs(shiftedCorrelation[index])
            if (value > primaryValue) {
                secondaryValue = primaryValue
                primaryValue = value
                primaryIndex = index
            } else if (abs(index - primaryIndex) > 2 && value > secondaryValue) {
                secondaryValue = value
            }
        }

        val delta = interpolateParabola(shiftedCorrelation, primaryIndex)
        val lagSamples = (primaryIndex - center) + delta
        val peakRatio = primaryValue / secondaryValue.coerceAtLeast(1e-9)

        return GccPhatResult(
            delaySeconds = lagSamples / config.sampleRateHz,
            primaryPeak = primaryValue,
            secondaryPeak = secondaryValue.coerceAtLeast(1e-9),
            peakRatio = peakRatio,
        )
    }

    private fun interpolateParabola(values: DoubleArray, index: Int): Double {
        if (index <= 0 || index >= values.lastIndex) {
            return 0.0
        }
        val left = values[index - 1]
        val center = values[index]
        val right = values[index + 1]
        val denominator = left - 2.0 * center + right
        if (abs(denominator) < 1e-12) {
            return 0.0
        }
        return 0.5 * (left - right) / denominator
    }
}
