package com.example.testppppp.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object FftUtils {
    fun nextPowerOfTwo(value: Int): Int {
        var power = 1
        while (power < value) {
            power = power shl 1
        }
        return power
    }

    fun fft(real: DoubleArray, imag: DoubleArray, inverse: Boolean) {
        val size = real.size
        require(size == imag.size && size > 0 && size and (size - 1) == 0) {
            "FFT input size must be a power of two"
        }

        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit

            if (i < j) {
                swap(real, i, j)
                swap(imag, i, j)
            }
        }

        var len = 2
        while (len <= size) {
            val angle = (2.0 * PI / len) * if (inverse) 1 else -1
            val wLenCos = cos(angle)
            val wLenSin = sin(angle)
            var start = 0
            while (start < size) {
                var wReal = 1.0
                var wImag = 0.0
                for (offset in 0 until len / 2) {
                    val evenIndex = start + offset
                    val oddIndex = evenIndex + len / 2

                    val uReal = real[evenIndex]
                    val uImag = imag[evenIndex]
                    val vReal = real[oddIndex] * wReal - imag[oddIndex] * wImag
                    val vImag = real[oddIndex] * wImag + imag[oddIndex] * wReal

                    real[evenIndex] = uReal + vReal
                    imag[evenIndex] = uImag + vImag
                    real[oddIndex] = uReal - vReal
                    imag[oddIndex] = uImag - vImag

                    val nextReal = wReal * wLenCos - wImag * wLenSin
                    val nextImag = wReal * wLenSin + wImag * wLenCos
                    wReal = nextReal
                    wImag = nextImag
                }
                start += len
            }
            len = len shl 1
        }

        if (inverse) {
            for (index in real.indices) {
                real[index] /= size
                imag[index] /= size
            }
        }
    }

    private fun swap(values: DoubleArray, first: Int, second: Int) {
        val tmp = values[first]
        values[first] = values[second]
        values[second] = tmp
    }
}
