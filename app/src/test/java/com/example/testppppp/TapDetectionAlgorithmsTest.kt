package com.example.testppppp

import com.example.testppppp.audio.CalibrationData
import com.example.testppppp.audio.CalibrationSample
import com.example.testppppp.audio.DirectionStats
import com.example.testppppp.audio.SectorDirection
import com.example.testppppp.audio.StereoAudioFrame
import com.example.testppppp.audio.TapDetectionConfig
import com.example.testppppp.audio.calibration.CalibrationSession
import com.example.testppppp.audio.classification.TapDirectionClassifier
import com.example.testppppp.audio.detection.StreamingTapDetector
import com.example.testppppp.audio.detection.TapDetectionPipeline
import com.example.testppppp.audio.dsp.GccPhatProcessor
import com.example.testppppp.audio.dsp.SignalProcessing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TapDetectionAlgorithmsTest {
    @Test
    fun `local peak detector finds transient peaks`() {
        val envelope = floatArrayOf(
            0f, 0.1f, 0.3f, 0.8f, 0.4f, 0.1f,
            0f, 0.05f, 0.2f, 0.9f, 0.5f, 0.1f, 0f,
        )

        val peaks = SignalProcessing.detectLocalPeaks(
            envelope = envelope,
            threshold = 0.25f,
            minPeakDistanceSamples = 3,
            sampleRateHz = 1_000,
        )

        assertEquals(2, peaks.size)
        assertEquals(3, peaks[0].sampleIndex)
        assertEquals(9, peaks[1].sampleIndex)
        assertTrue(peaks.all { it.widthMs > 0f })
    }

    @Test
    fun `gcc phat estimates delay for shifted impulses`() {
        val config = TapDetectionConfig(
            sampleRateHz = 8_000,
            microphoneDistanceMeters = 0.25f,
            gccMultiBandEnabled = false,
            gccApplyHannWindow = false,
        )
        val left = FloatArray(256)
        val right = FloatArray(256)
        left[80] = 1f
        right[84] = 1f

        val result = GccPhatProcessor(config).estimateDelay(left, right)
        val expectedDelay = 4.0 / config.sampleRateHz

        assertTrue(abs(abs(result.delaySeconds) - expectedDelay) < 1.5 / config.sampleRateHz)
        assertTrue(result.peakRatio > 1.0)
    }

    @Test
    fun `classifier rejects low confidence near boundary`() {
        val config = TapDetectionConfig(testingConfidenceThreshold = 0.5, boundaryMarginSeconds = 0.00005)
        val classifier = TapDirectionClassifier(config)
        classifier.updateCalibration(
            CalibrationData(
                directions = mapOf(
                    SectorDirection.TOP to DirectionStats(-0.00020, 0.00002, 1.35, 0.08, 1.30, 0.08, 6),
                    SectorDirection.RIGHT to DirectionStats(0.0, 0.00002, 1.00, 0.05, 1.00, 0.05, 6),
                    SectorDirection.BOTTOM to DirectionStats(0.00020, 0.00002, 0.72, 0.08, 0.76, 0.08, 6),
                ),
                rawSamples = mapOf(
                    SectorDirection.TOP to listOf(
                        CalibrationSample(-0.00022, 1.36, 1.31, 1.33, 1.29, 0.70),
                        CalibrationSample(-0.00019, 1.34, 1.28, 1.30, 1.27, 0.74),
                    ),
                    SectorDirection.RIGHT to listOf(
                        CalibrationSample(0.00000, 1.01, 0.99, 1.02, 0.98, 0.88),
                        CalibrationSample(0.00001, 0.98, 1.02, 1.01, 1.03, 0.84),
                    ),
                    SectorDirection.BOTTOM to listOf(
                        CalibrationSample(0.00018, 0.74, 0.77, 0.75, 0.79, 0.72),
                        CalibrationSample(0.00021, 0.71, 0.75, 0.73, 0.76, 0.76),
                    ),
                ),
            ),
        )

        val result = classifier.classify(
            delaySeconds = -0.00010,
            qualityScore = 35.0,
            lagConfidence = 0.12,
            gccResult = com.example.testppppp.audio.GccPhatResult(
                delaySeconds = -0.00010,
                primaryPeak = 1.1,
                secondaryPeak = 1.0,
                peakRatio = 1.1,
            ),
            channelEnergyRatio = 1.02,
            channelPeakRatio = 1.01,
            firstPeakChannelRatio = 1.00,
            secondPeakChannelRatio = 1.02,
        )

        assertNotNull(result)
        assertFalse(result!!.triggered)
        assertNotNull(result.rejectionReason)
    }

    @Test
    fun `classifier reports missing calibration reason`() {
        val classifier = TapDirectionClassifier(TapDetectionConfig())

        val result = classifier.classify(
            delaySeconds = 0.0,
            qualityScore = 80.0,
            lagConfidence = 1.0,
            gccResult = com.example.testppppp.audio.GccPhatResult(
                delaySeconds = 0.0,
                primaryPeak = 2.0,
                secondaryPeak = 1.0,
                peakRatio = 2.0,
            ),
            channelEnergyRatio = 1.0,
            channelPeakRatio = 1.0,
            firstPeakChannelRatio = 1.0,
            secondPeakChannelRatio = 1.0,
        )

        assertNotNull(result)
        assertFalse(result!!.triggered)
        assertEquals("尚无标定数据，无法分类", result.rejectionReason)
    }

    @Test
    fun `classifier uses knn to keep right samples on right`() {
        val classifier = TapDirectionClassifier(TapDetectionConfig(testingConfidenceThreshold = 0.1, testingAcceptedQualityScore = 20.0))
        classifier.updateCalibration(
            CalibrationData(
                directions = mapOf(
                    SectorDirection.TOP to DirectionStats(-0.00018, 0.00003, 1.35, 0.08, 1.28, 0.06, 4),
                    SectorDirection.RIGHT to DirectionStats(0.0, 0.00002, 1.00, 0.04, 1.00, 0.04, 4),
                    SectorDirection.BOTTOM to DirectionStats(0.00018, 0.00003, 0.74, 0.08, 0.78, 0.06, 4),
                ),
                rawSamples = mapOf(
                    SectorDirection.TOP to listOf(
                        CalibrationSample(-0.00020, 1.38, 1.30, 1.34, 1.27, 0.76),
                        CalibrationSample(-0.00016, 1.33, 1.24, 1.28, 1.22, 0.72),
                    ),
                    SectorDirection.RIGHT to listOf(
                        CalibrationSample(-0.00001, 1.02, 1.01, 1.08, 0.96, 0.90),
                        CalibrationSample(0.00001, 0.98, 1.00, 0.95, 1.05, 0.88),
                    ),
                    SectorDirection.BOTTOM to listOf(
                        CalibrationSample(0.00016, 0.78, 0.80, 0.79, 0.81, 0.70),
                        CalibrationSample(0.00020, 0.72, 0.76, 0.74, 0.77, 0.74),
                    ),
                ),
            ),
        )

        val result = classifier.classify(
            delaySeconds = 0.000005,
            qualityScore = 75.0,
            lagConfidence = 0.82,
            gccResult = com.example.testppppp.audio.GccPhatResult(
                delaySeconds = 0.000005,
                primaryPeak = 1.8,
                secondaryPeak = 1.0,
                peakRatio = 1.8,
            ),
            channelEnergyRatio = 1.00,
            channelPeakRatio = 1.01,
            firstPeakChannelRatio = 1.06,
            secondPeakChannelRatio = 0.97,
        )

        assertNotNull(result)
        assertTrue(result!!.triggered)
        assertEquals(SectorDirection.RIGHT, result.sector)
        assertTrue(result.rightVotes >= result.topVotes)
        assertTrue(result.rightVotes >= result.bottomVotes)
    }

    @Test
    fun `calibration session aggregates all directions`() {
        val session = CalibrationSession(targetSamplesPerDirection = 2)
        session.start()

        SectorDirection.ordered.forEachIndexed { index, _ ->
            session.addSample(
                CalibrationSample(
                    delaySeconds = index * 0.0001,
                    channelEnergyRatio = 1.0 + index * 0.1,
                    channelPeakRatio = 1.0 + index * 0.08,
                ),
            )
            session.addSample(
                CalibrationSample(
                    delaySeconds = index * 0.0001 + 0.00001,
                    channelEnergyRatio = 1.02 + index * 0.1,
                    channelPeakRatio = 1.01 + index * 0.08,
                ),
            )
        }

        assertTrue(session.isComplete())
        val calibration = session.toCalibrationData()
        assertEquals(SectorDirection.ordered.size, calibration.directions.size)
        assertTrue(calibration.isComplete())
        assertTrue(calibration.directions.values.all { it.meanEnergyRatio > 0.0 })
        assertTrue(calibration.directions.values.all { it.meanPeakRatio > 0.0 })
        assertTrue(calibration.rawSamples.values.all { it.size == 2 })
    }

    @Test
    fun `pipeline produces tdoa for double peak tap`() {
        val config = TapDetectionConfig(
            windowMs = 180,
            absolutePeakThreshold = 0.001f,
            snrThreshold = 1.0f,
            minNearFieldRatio = 1.0f,
            minTransientScore = 1.0f,
            minStereoConsistency = 0.0f,
            minPeakSharpness = 1.0f,
            envelopeSmoothingMs = 1,
            maxPeakWidthMs = 30f,
            minTrailingPeakRatio = 0.2f,
            testingAcceptedQualityScore = 20.0,
        )
        val classifier = TapDirectionClassifier(config)
        val pipeline = TapDetectionPipeline(config, classifier)
        val left = FloatArray(config.eventWindowSizeSamples)
        val right = FloatArray(config.eventWindowSizeSamples)
        addPulse(left, 220, amplitude = 1f)
        addPulse(left, 1_100, amplitude = 0.88f)
        addPulse(right, 223, amplitude = 1f)
        addPulse(right, 1_103, amplitude = 0.88f)

        val result = pipeline.process(
            StereoAudioFrame(
                left = left,
                right = right,
                sampleRateHz = config.sampleRateHz,
                frameIndex = 0,
                startSampleIndex = 0,
            ),
        )

        assertNotNull(result.delaySeconds)
        assertNotNull(result.debugInfo.chosenPair)
        assertTrue(result.debugInfo.snr >= config.snrThreshold)
        assertTrue(result.debugInfo.qualityScore > 0.0)
    }

    @Test
    fun `streaming detector emits one event from sliding windows`() {
        val config = TapDetectionConfig(
            windowMs = 100,
            hopMs = 20,
            eventPreTriggerMs = 30,
            eventPostTriggerMs = 260,
            absolutePeakThreshold = 0.001f,
            snrThreshold = 1.0f,
            minNearFieldRatio = 1.0f,
            minTransientScore = 1.0f,
            minStereoConsistency = 0.0f,
            minPeakSharpness = 1.0f,
            envelopeSmoothingMs = 1,
            maxPeakWidthMs = 30f,
            minTrailingPeakRatio = 0.2f,
            testingAcceptedQualityScore = 20.0,
        )
        val classifier = TapDirectionClassifier(config)
        val pipeline = TapDetectionPipeline(config, classifier)
        val detector = StreamingTapDetector(config, pipeline)

        val total = FloatArray(config.sampleRateHz / 2)
        val totalRight = FloatArray(config.sampleRateHz / 2)
        addPulse(total, 1_500, amplitude = 1f)
        addPulse(total, 3_900, amplitude = 0.82f)
        addPulse(totalRight, 1_503, amplitude = 1f)
        addPulse(totalRight, 3_903, amplitude = 0.82f)

        var emittedResult: com.example.testppppp.audio.TapDetectionResult? = null
        var frameIndex = 0L
        val hop = config.hopSizeSamples
        val scan = config.windowSizeSamples
        var start = 0
        while (start + scan <= total.size) {
            val result = detector.process(
                StereoAudioFrame(
                    left = total.copyOfRange(start, start + scan),
                    right = totalRight.copyOfRange(start, start + scan),
                    sampleRateHz = config.sampleRateHz,
                    frameIndex = frameIndex++,
                    startSampleIndex = start.toLong(),
                ),
            )
            if (result != null) {
                emittedResult = result
                break
            }
            start += hop
        }

        assertNotNull(emittedResult)
        assertNotNull(emittedResult!!.delaySeconds)
        assertNotNull(emittedResult!!.debugInfo.chosenPair)
    }

    @Test
    fun `broader peaks are not rejected only by sharpness`() {
        val config = TapDetectionConfig(
            absolutePeakThreshold = 0.001f,
            snrThreshold = 1.0f,
            minNearFieldRatio = 1.0f,
            minTransientScore = 1.0f,
            minStereoConsistency = 0.0f,
            minPeakSharpness = 1.05f,
            maxPeakWidthMs = 40f,
            minTrailingPeakRatio = 0.2f,
            testingAcceptedQualityScore = 20.0,
        )
        val classifier = TapDirectionClassifier(config)
        val pipeline = TapDetectionPipeline(config, classifier)
        val left = FloatArray(config.eventWindowSizeSamples)
        val right = FloatArray(config.eventWindowSizeSamples)
        addWidePulse(left, 260, amplitude = 1f)
        addWidePulse(left, 2_300, amplitude = 0.8f)
        addWidePulse(right, 263, amplitude = 1f)
        addWidePulse(right, 2_303, amplitude = 0.8f)

        val result = pipeline.process(
            StereoAudioFrame(
                left = left,
                right = right,
                sampleRateHz = config.sampleRateHz,
                frameIndex = 0,
                startSampleIndex = 0,
            ),
        )

        assertNotNull(result.debugInfo.chosenPair)
        assertFalse(result.debugInfo.failureReasons.contains("峰尖锐度不足"))
    }

    private fun addPulse(signal: FloatArray, center: Int, amplitude: Float) {
        val shape = floatArrayOf(0.1f, 0.2f, 0.45f, 0.7f, 1f, 0.7f, 0.45f, 0.2f, 0.1f)
        shape.forEachIndexed { offset, value ->
            val index = center + offset - (shape.size / 2)
            if (index in signal.indices) {
                signal[index] = amplitude * value
            }
        }
    }

    private fun addWidePulse(signal: FloatArray, center: Int, amplitude: Float) {
        val shape = floatArrayOf(0.08f, 0.16f, 0.30f, 0.48f, 0.66f, 0.84f, 1f, 0.84f, 0.66f, 0.48f, 0.30f, 0.16f, 0.08f)
        shape.forEachIndexed { offset, value ->
            val index = center + offset - (shape.size / 2)
            if (index in signal.indices) {
                signal[index] = amplitude * value
            }
        }
    }
}
