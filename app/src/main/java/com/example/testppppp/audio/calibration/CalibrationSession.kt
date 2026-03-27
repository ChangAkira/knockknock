package com.example.testppppp.audio.calibration

import com.example.testppppp.audio.CalibrationData
import com.example.testppppp.audio.CalibrationSample
import com.example.testppppp.audio.DirectionStats
import com.example.testppppp.audio.SectorDirection
import kotlin.math.sqrt

data class CalibrationProgress(
    val isRunning: Boolean,
    val currentDirection: SectorDirection?,
    val targetSamplesPerDirection: Int,
    val collectedCounts: Map<SectorDirection, Int>,
) {
    companion object {
        fun idle(targetSamplesPerDirection: Int): CalibrationProgress {
            return CalibrationProgress(
                isRunning = false,
                currentDirection = null,
                targetSamplesPerDirection = targetSamplesPerDirection,
                collectedCounts = SectorDirection.ordered.associateWith { 0 },
            )
        }
    }
}

class CalibrationSession(
    private val targetSamplesPerDirection: Int,
) {
    private val samples = SectorDirection.ordered.associateWith { mutableListOf<CalibrationSample>() }.toMutableMap()
    private var currentDirectionIndex: Int = -1

    fun start() {
        samples.values.forEach { it.clear() }
        currentDirectionIndex = 0
    }

    fun cancel() {
        currentDirectionIndex = -1
        samples.values.forEach { it.clear() }
    }

    fun isRunning(): Boolean = currentDirectionIndex in SectorDirection.ordered.indices

    fun currentDirection(): SectorDirection? = SectorDirection.ordered.getOrNull(currentDirectionIndex)

    fun addSample(sample: CalibrationSample): Boolean {
        val direction = currentDirection() ?: return false
        val directionSamples = samples.getValue(direction)
        if (directionSamples.size >= targetSamplesPerDirection) {
            return false
        }
        directionSamples += sample
        if (directionSamples.size >= targetSamplesPerDirection) {
            currentDirectionIndex++
            if (currentDirectionIndex >= SectorDirection.ordered.size) {
                currentDirectionIndex = -1
            }
        }
        return true
    }

    fun progress(): CalibrationProgress {
        return CalibrationProgress(
            isRunning = isRunning(),
            currentDirection = currentDirection(),
            targetSamplesPerDirection = targetSamplesPerDirection,
            collectedCounts = SectorDirection.ordered.associateWith { samples.getValue(it).size },
        )
    }

    fun isComplete(): Boolean {
        return SectorDirection.ordered.all { samples.getValue(it).size >= targetSamplesPerDirection }
    }

    fun toCalibrationData(): CalibrationData {
        val stats = SectorDirection.ordered.associateWith { direction ->
            val values = samples.getValue(direction)
            val meanDelay = values.map(CalibrationSample::delaySeconds).average()
            val meanEnergy = values.map(CalibrationSample::channelEnergyRatio).average()
            val meanPeak = values.map(CalibrationSample::channelPeakRatio).average()
            val meanLagConfidence = values.map(CalibrationSample::lagConfidence).average()
            DirectionStats(
                meanDelaySeconds = meanDelay,
                standardDeviationSeconds = sqrt(variance(values) { sample ->
                    (sample.delaySeconds - meanDelay) * (sample.delaySeconds - meanDelay)
                }).coerceAtLeast(1e-6),
                meanEnergyRatio = meanEnergy,
                standardDeviationEnergyRatio = sqrt(variance(values) { sample ->
                    (sample.channelEnergyRatio - meanEnergy) * (sample.channelEnergyRatio - meanEnergy)
                }).coerceAtLeast(1e-6),
                meanPeakRatio = meanPeak,
                standardDeviationPeakRatio = sqrt(variance(values) { sample ->
                    (sample.channelPeakRatio - meanPeak) * (sample.channelPeakRatio - meanPeak)
                }).coerceAtLeast(1e-6),
                sampleCount = values.size,
                meanLagConfidence = meanLagConfidence,
                standardDeviationLagConfidence = sqrt(variance(values) { sample ->
                    (sample.lagConfidence - meanLagConfidence) * (sample.lagConfidence - meanLagConfidence)
                }).coerceAtLeast(1e-6),
            )
        }
        return CalibrationData(
            directions = stats,
            rawSamples = SectorDirection.ordered.associateWith { direction ->
                samples.getValue(direction).toList()
            },
        )
    }

    private fun variance(
        values: List<CalibrationSample>,
        extractor: (CalibrationSample) -> Double,
    ): Double {
        if (values.size <= 1) {
            return 1e-6
        }
        return values.sumOf(extractor) / values.size
    }
}
