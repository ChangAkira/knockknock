package com.example.testppppp.audio.calibration

import android.content.Context
import com.example.testppppp.audio.CalibrationData
import com.example.testppppp.audio.CalibrationSample
import com.example.testppppp.audio.DirectionStats
import com.example.testppppp.audio.SectorDirection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CalibrationRepository(
    context: Context,
) {
    private val calibrationFile = File(context.filesDir, "tap_calibration.json")

    fun load(): CalibrationData? {
        if (!calibrationFile.exists()) {
            return null
        }

        val root = JSONObject(calibrationFile.readText())
        val directionsJson = root.optJSONObject("directions") ?: return null
        val directions = mutableMapOf<SectorDirection, DirectionStats>()
        val rawSamplesJson = root.optJSONObject("rawSamples")
        val rawSamples = mutableMapOf<SectorDirection, List<CalibrationSample>>()

        SectorDirection.ordered.forEach { direction ->
            val statsJson = directionsJson.optJSONObject(direction.name) ?: return@forEach
            directions[direction] = DirectionStats(
                meanDelaySeconds = statsJson.optDouble("meanDelaySeconds", 0.0),
                standardDeviationSeconds = statsJson.optDouble("standardDeviationSeconds", 0.0),
                meanEnergyRatio = statsJson.optDouble("meanEnergyRatio", 1.0),
                standardDeviationEnergyRatio = statsJson.optDouble("standardDeviationEnergyRatio", 1.0),
                meanPeakRatio = statsJson.optDouble("meanPeakRatio", 1.0),
                standardDeviationPeakRatio = statsJson.optDouble("standardDeviationPeakRatio", 1.0),
                sampleCount = statsJson.optInt("sampleCount", 0),
                meanLagConfidence = statsJson.optDouble("meanLagConfidence", 0.0),
                standardDeviationLagConfidence = statsJson.optDouble("standardDeviationLagConfidence", 1.0),
            )

            val sampleArray = rawSamplesJson?.optJSONArray(direction.name)
            rawSamples[direction] = sampleArray?.toCalibrationSamples().orEmpty()
        }

        return CalibrationData(
            directions = directions,
            rawSamples = rawSamples,
        )
    }

    fun save(data: CalibrationData) {
        val directionsJson = JSONObject()
        val rawSamplesJson = JSONObject()
        data.directions.forEach { (direction, stats) ->
            directionsJson.put(
                direction.name,
                JSONObject()
                    .put("meanDelaySeconds", stats.meanDelaySeconds)
                    .put("standardDeviationSeconds", stats.standardDeviationSeconds)
                    .put("meanEnergyRatio", stats.meanEnergyRatio)
                    .put("standardDeviationEnergyRatio", stats.standardDeviationEnergyRatio)
                    .put("meanPeakRatio", stats.meanPeakRatio)
                    .put("standardDeviationPeakRatio", stats.standardDeviationPeakRatio)
                    .put("meanLagConfidence", stats.meanLagConfidence)
                    .put("standardDeviationLagConfidence", stats.standardDeviationLagConfidence)
                    .put("sampleCount", stats.sampleCount),
            )
        }
        data.rawSamples.forEach { (direction, samples) ->
            val sampleArray = JSONArray()
            samples.forEach { sample ->
                sampleArray.put(
                    JSONObject()
                        .put("delaySeconds", sample.delaySeconds)
                        .put("channelEnergyRatio", sample.channelEnergyRatio)
                        .put("channelPeakRatio", sample.channelPeakRatio)
                        .put("firstPeakChannelRatio", sample.firstPeakChannelRatio)
                        .put("secondPeakChannelRatio", sample.secondPeakChannelRatio)
                        .put("lagConfidence", sample.lagConfidence),
                )
            }
            rawSamplesJson.put(direction.name, sampleArray)
        }

        calibrationFile.parentFile?.mkdirs()
        calibrationFile.writeText(
            JSONObject()
                .put("directions", directionsJson)
                .put("rawSamples", rawSamplesJson)
                .toString(2),
        )
    }

    fun clear() {
        if (calibrationFile.exists()) {
            calibrationFile.delete()
        }
    }

    private fun JSONArray.toCalibrationSamples(): List<CalibrationSample> {
        val samples = mutableListOf<CalibrationSample>()
        for (index in 0 until length()) {
            val sampleJson = optJSONObject(index) ?: continue
            samples += CalibrationSample(
                delaySeconds = sampleJson.optDouble("delaySeconds", 0.0),
                channelEnergyRatio = sampleJson.optDouble("channelEnergyRatio", 1.0),
                channelPeakRatio = sampleJson.optDouble("channelPeakRatio", 1.0),
                firstPeakChannelRatio = sampleJson.optDouble("firstPeakChannelRatio", sampleJson.optDouble("channelPeakRatio", 1.0)),
                secondPeakChannelRatio = sampleJson.optDouble("secondPeakChannelRatio", sampleJson.optDouble("channelPeakRatio", 1.0)),
                lagConfidence = sampleJson.optDouble("lagConfidence", 0.0),
            )
        }
        return samples
    }
}
