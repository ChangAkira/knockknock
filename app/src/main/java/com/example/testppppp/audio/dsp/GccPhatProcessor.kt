package com.example.testppppp.audio.dsp

import com.example.testppppp.audio.GccPhatResult
import com.example.testppppp.audio.TapDetectionConfig
import kotlin.math.abs
import kotlin.math.sqrt

class GccPhatProcessor(
    private val config: TapDetectionConfig,
) {
    fun estimateDelay(left: FloatArray, right: FloatArray): GccPhatResult {
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
