package com.example.testppppp.audio.detection

import com.example.testppppp.audio.ClassificationResult
import com.example.testppppp.audio.DetectionDebugInfo
import com.example.testppppp.audio.DetectionQualityMetrics
import com.example.testppppp.audio.GccPhatResult
import com.example.testppppp.audio.PeakInfo
import com.example.testppppp.audio.PeakPairInfo
import com.example.testppppp.audio.StereoAudioFrame
import com.example.testppppp.audio.TapCandidate
import com.example.testppppp.audio.TapDetectionConfig
import com.example.testppppp.audio.TapDetectionResult
import com.example.testppppp.audio.WaveformSnapshot
import com.example.testppppp.audio.classification.TapDirectionClassifier
import com.example.testppppp.audio.dsp.GccPhatProcessor
import com.example.testppppp.audio.dsp.SignalProcessing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TapDetectionPipeline(
    private val config: TapDetectionConfig,
    private val classifier: TapDirectionClassifier,
) {
    private val gccPhatProcessor = GccPhatProcessor(config)

    fun process(frame: StereoAudioFrame): TapDetectionResult {
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
        val smoothingSamples = (config.envelopeSmoothingMs * frame.sampleRateHz / 1_000).coerceAtLeast(1)
        val envelope = SignalProcessing.envelope(merged, smoothingSamples)
        val peaks = SignalProcessing.detectLocalPeaks(
            envelope = envelope,
            threshold = config.absolutePeakThreshold,
            minPeakDistanceSamples = (config.minPeakDistanceMs * frame.sampleRateHz / 1_000).coerceAtLeast(1),
            sampleRateHz = frame.sampleRateHz,
        )

        val selection = selectCandidate(
            peaks = peaks,
            envelope = envelope,
            left = filteredLeft,
            right = filteredRight,
            sampleRateHz = frame.sampleRateHz,
        )
        val (normalizedLeft, normalizedRight) = SignalProcessing.normalize(filteredLeft, filteredRight)

        if (selection.bestValid == null) {
            val bestObserved = selection.bestObserved
            val debugInfo = DetectionDebugInfo(
                peaks = peaks,
                chosenPair = null,
                snr = bestObserved?.quality?.snr ?: 0f,
                absolutePeak = bestObserved?.quality?.absolutePeak ?: 0f,
                nearFieldRatio = bestObserved?.quality?.nearFieldRatio ?: 0f,
                transientScore = bestObserved?.quality?.transientScore ?: 0f,
                stereoConsistency = bestObserved?.quality?.stereoConsistency ?: 0f,
                peakSharpness = bestObserved?.quality?.peakSharpness ?: 0f,
                trailingPeakRatio = bestObserved?.quality?.trailingPeakRatio ?: 0f,
                gapScore = bestObserved?.quality?.gapScore ?: 0f,
                channelEnergyRatio = bestObserved?.quality?.channelEnergyRatio ?: 1f,
                combinedPeakChannelRatio = bestObserved?.quality?.combinedPeakChannelRatio ?: 1f,
                firstPeakChannelRatio = bestObserved?.quality?.firstPeakChannelRatio ?: 1f,
                secondPeakChannelRatio = bestObserved?.quality?.secondPeakChannelRatio ?: 1f,
                qualityScore = bestObserved?.quality?.qualityScore ?: 0.0,
                lagConfidence = 0.0,
                peakRatio = 0.0,
                primaryPeak = 0.0,
                secondaryPeak = 0.0,
                rejectionReason = bestObserved?.failureReasons?.firstOrNull() ?: "No valid double-peak tap candidate",
                failureReasons = bestObserved?.failureReasons ?: listOf("No valid double-peak tap candidate"),
                waveformSnapshot = WaveformSnapshot(
                    left = normalizedLeft,
                    right = normalizedRight,
                    envelope = envelope,
                    peakIndices = peaks.map(PeakInfo::sampleIndex),
                ),
            )
            return TapDetectionResult(
                triggered = false,
                delaySeconds = null,
                sector = null,
                confidence = 0.0,
                debugInfo = debugInfo,
                classification = null,
            )
        }

        val candidate = selection.bestValid
        val gccResult = gccPhatProcessor.estimateDelay(normalizedLeft, normalizedRight)
        val lagConfidence = computeLagConfidence(gccResult)
        val classifierFailureHints = buildLagHints(gccResult, lagConfidence)
        val classification = classifier.classify(
            delaySeconds = gccResult.delaySeconds,
            qualityScore = candidate.quality.qualityScore,
            lagConfidence = lagConfidence,
            gccResult = gccResult,
            channelEnergyRatio = candidate.quality.channelEnergyRatio.toDouble(),
            channelPeakRatio = candidate.quality.combinedPeakChannelRatio.toDouble(),
            firstPeakChannelRatio = candidate.quality.firstPeakChannelRatio.toDouble(),
            secondPeakChannelRatio = candidate.quality.secondPeakChannelRatio.toDouble(),
        )
        val debugInfo = buildDebugInfo(
            peaks = peaks,
            candidate = candidate,
            lagConfidence = lagConfidence,
            gccResult = gccResult,
            classification = classification,
            left = normalizedLeft,
            right = normalizedRight,
            envelope = envelope,
            failureHints = classifierFailureHints,
        )
        return TapDetectionResult(
            triggered = classification?.triggered == true,
            delaySeconds = gccResult.delaySeconds,
            sector = classification?.sector,
            confidence = classification?.confidence ?: 0.0,
            debugInfo = debugInfo,
            classification = classification,
        )
    }

    private fun selectCandidate(
        peaks: List<PeakInfo>,
        envelope: FloatArray,
        left: FloatArray,
        right: FloatArray,
        sampleRateHz: Int,
    ): CandidateSelection {
        if (peaks.size < 2) {
            return CandidateSelection(null, null)
        }

        val peakWindowSamples = (config.peakWindowMs * sampleRateHz / 1_000).coerceAtLeast(1)
        val backgroundWindowSamples = (config.backgroundWindowMs * sampleRateHz / 1_000).coerceAtLeast(1)
        var bestValid: TapCandidate? = null
        var bestValidScore = Double.NEGATIVE_INFINITY
        var bestObserved: TapCandidate? = null
        var bestObservedScore = Double.NEGATIVE_INFINITY

        for (index in 0 until peaks.lastIndex) {
            val first = peaks[index]
            val second = peaks[index + 1]
            val trailingPeakRatio = (second.amplitude / first.amplitude.coerceAtLeast(1e-6f))
            val peakBalanceRatio = min(first.amplitude, second.amplitude) / max(first.amplitude, second.amplitude).coerceAtLeast(1e-6f)
            val intervalMs = second.timeMs - first.timeMs
            val pair = PeakPairInfo(
                first = first,
                second = second,
                intervalMs = intervalMs,
                amplitudeDiff = abs(first.amplitude - second.amplitude),
                trailingPeakRatio = trailingPeakRatio,
            )
            val snrFirst = SignalProcessing.localSnr(
                signal = envelope,
                centerSample = first.sampleIndex,
                peakWindowSamples = peakWindowSamples,
                backgroundWindowSamples = backgroundWindowSamples,
            )
            val snrSecond = SignalProcessing.localSnr(
                signal = envelope,
                centerSample = second.sampleIndex,
                peakWindowSamples = peakWindowSamples,
                backgroundWindowSamples = backgroundWindowSamples,
            )
            val snr = max(snrFirst, snrSecond)
            val representativeWidth = max(first.widthMs, second.widthMs)
            val absolutePeak = max(first.amplitude, second.amplitude)
            val backgroundAverage = min(
                localBackgroundAverage(envelope, first.sampleIndex, backgroundWindowSamples),
                localBackgroundAverage(envelope, second.sampleIndex, backgroundWindowSamples),
            ).coerceAtLeast(1e-4f)
            val nearFieldRatio = absolutePeak / backgroundAverage
            val peakSharpness = min(
                peakSharpness(envelope, first.sampleIndex),
                peakSharpness(envelope, second.sampleIndex),
            )
            val transientScore = peakSharpness * nearFieldRatio
            val stereoConsistency = stereoConsistency(left, right, first.sampleIndex, second.sampleIndex, peakWindowSamples)
            val gapScore = gapScore(intervalMs)
            val channelEnergyRatio = channelEnergyRatio(left, right, first.sampleIndex, second.sampleIndex, peakWindowSamples)
            val firstPeakChannelRatio = localPeakRatio(left, right, first.sampleIndex)
            val secondPeakChannelRatio = localPeakRatio(left, right, second.sampleIndex)
            val combinedPeakChannelRatio = ((firstPeakChannelRatio + secondPeakChannelRatio) / 2f).coerceAtLeast(1e-4f)
            val quality = DetectionQualityMetrics(
                absolutePeak = absolutePeak,
                snr = snr,
                nearFieldRatio = nearFieldRatio,
                transientScore = transientScore,
                stereoConsistency = stereoConsistency,
                peakSharpness = peakSharpness,
                trailingPeakRatio = trailingPeakRatio,
                peakBalanceRatio = peakBalanceRatio,
                gapScore = gapScore,
                channelEnergyRatio = channelEnergyRatio,
                combinedPeakChannelRatio = combinedPeakChannelRatio,
                firstPeakChannelRatio = firstPeakChannelRatio,
                secondPeakChannelRatio = secondPeakChannelRatio,
                qualityScore = qualityScore(
                    absolutePeak = absolutePeak,
                    snr = snr,
                    nearFieldRatio = nearFieldRatio,
                    transientScore = transientScore,
                    stereoConsistency = stereoConsistency,
                    peakSharpness = peakSharpness,
                    trailingPeakRatio = trailingPeakRatio,
                    peakBalanceRatio = peakBalanceRatio,
                    gapScore = gapScore,
                ),
            )
            val failureReasons = buildFailureReasons(
                quality = quality,
                representativeWidth = representativeWidth,
                intervalMs = intervalMs,
            )
            val candidate = TapCandidate(
                pair = pair,
                representativeWidthMs = representativeWidth,
                quality = quality,
                failureReasons = failureReasons,
            )

            if (quality.qualityScore > bestObservedScore) {
                bestObservedScore = quality.qualityScore
                bestObserved = candidate
            }

            if (failureReasons.isEmpty() && quality.qualityScore > bestValidScore) {
                bestValidScore = quality.qualityScore
                bestValid = candidate
            }
        }
        return CandidateSelection(bestValid, bestObserved)
    }

    private fun buildDebugInfo(
        peaks: List<PeakInfo>,
        candidate: TapCandidate,
        lagConfidence: Double,
        gccResult: GccPhatResult,
        classification: ClassificationResult?,
        left: FloatArray,
        right: FloatArray,
        envelope: FloatArray,
        failureHints: List<String>,
    ): DetectionDebugInfo {
        return DetectionDebugInfo(
            peaks = peaks,
            chosenPair = candidate.pair,
            snr = candidate.quality.snr,
            absolutePeak = candidate.quality.absolutePeak,
            nearFieldRatio = candidate.quality.nearFieldRatio,
            transientScore = candidate.quality.transientScore,
            stereoConsistency = candidate.quality.stereoConsistency,
            peakSharpness = candidate.quality.peakSharpness,
            trailingPeakRatio = candidate.quality.trailingPeakRatio,
            gapScore = candidate.quality.gapScore,
            channelEnergyRatio = candidate.quality.channelEnergyRatio,
            combinedPeakChannelRatio = candidate.quality.combinedPeakChannelRatio,
            firstPeakChannelRatio = candidate.quality.firstPeakChannelRatio,
            secondPeakChannelRatio = candidate.quality.secondPeakChannelRatio,
            qualityScore = candidate.quality.qualityScore,
            lagConfidence = lagConfidence,
            peakRatio = gccResult.peakRatio,
            primaryPeak = gccResult.primaryPeak,
            secondaryPeak = gccResult.secondaryPeak,
            rejectionReason = classification?.rejectionReason,
            failureReasons = candidate.failureReasons + failureHints,
            waveformSnapshot = WaveformSnapshot(
                left = left,
                right = right,
                envelope = envelope,
                peakIndices = peaks.map(PeakInfo::sampleIndex),
            ),
        )
    }

    private fun buildFailureReasons(
        quality: DetectionQualityMetrics,
        representativeWidth: Float,
        intervalMs: Float,
    ): List<String> {
        val reasons = mutableListOf<String>()
        if (quality.absolutePeak < config.absolutePeakThreshold) {
            reasons += "近场绝对响度不足"
        }
        if (quality.snr < config.snrThreshold) {
            reasons += "局部信噪比不足"
        }
        if (quality.nearFieldRatio < config.minNearFieldRatio) {
            reasons += "近场能量比不足"
        }
        if (quality.transientScore < config.minTransientScore) {
            reasons += "瞬态程度不足"
        }
        if (quality.stereoConsistency < config.minStereoConsistency) {
            reasons += "双通道一致性不足"
        }
        if (representativeWidth > config.maxPeakWidthMs) {
            reasons += "峰宽过大，敲击不够尖锐"
        }
        if (quality.peakSharpness < hardSharpnessFloor()) {
            reasons += "峰尖锐度不足"
        }
        if (intervalMs < config.minPeakPairGapMs) {
            reasons += "双击间隔偏短"
        }
        if (intervalMs > config.maxPeakPairGapMs) {
            reasons += "双击间隔偏长"
        }
        if (quality.trailingPeakRatio < config.minTrailingPeakRatio) {
            reasons += "第二下过轻，未形成稳定双击"
        }
        if (quality.trailingPeakRatio > config.maxTrailingPeakRatio) {
            reasons += "第二下过重，双峰不稳定"
        }
        if (quality.peakBalanceRatio < config.minPeakBalanceRatio) {
            reasons += "双峰强弱比例失衡，未形成稳定一轻一重"
        }
        return reasons
    }

    private fun qualityScore(
        absolutePeak: Float,
        snr: Float,
        nearFieldRatio: Float,
        transientScore: Float,
        stereoConsistency: Float,
        peakSharpness: Float,
        trailingPeakRatio: Float,
        peakBalanceRatio: Float,
        gapScore: Float,
    ): Double {
        val absolutePeakScore = (absolutePeak / 0.05f).coerceIn(0f, 1f)
        val snrScore = (snr / 12f).coerceIn(0f, 1f)
        val nearFieldScore = (nearFieldRatio / 6f).coerceIn(0f, 1f)
        val transientScoreNormalized = (transientScore / 8f).coerceIn(0f, 1f)
        val stereoScore = (stereoConsistency / 0.6f).coerceIn(0f, 1f)
        val sharpnessScore = ((peakSharpness - 0.9f) / (config.minPeakSharpness + 0.35f - 0.9f))
            .coerceIn(0f, 1f)
        val trailingScore = trailingPeakScore(trailingPeakRatio, peakBalanceRatio)

        return 100.0 * (
            0.15 * absolutePeakScore +
                0.15 * snrScore +
                0.15 * nearFieldScore +
                0.15 * transientScoreNormalized +
                0.10 * stereoScore +
                0.10 * sharpnessScore +
                0.10 * trailingScore +
                0.10 * gapScore.coerceIn(0f, 1f)
            )
    }

    private fun trailingPeakScore(trailingPeakRatio: Float, peakBalanceRatio: Float): Float {
        if (trailingPeakRatio !in config.minTrailingPeakRatio..config.maxTrailingPeakRatio) {
            return 0f
        }
        val normalized = (peakBalanceRatio / config.minPeakBalanceRatio.coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
        return normalized
    }

    private fun gapScore(intervalMs: Float): Float {
        val normalized = 1f - abs(intervalMs - config.idealGapMs) / config.idealGapToleranceMs.coerceAtLeast(1f)
        return max(config.minGapScore, normalized).coerceIn(config.minGapScore, 1f)
    }

    private fun peakSharpness(envelope: FloatArray, peakIndex: Int): Float {
        val exclusionRadius = 1
        val shoulderRadius = 6
        var shoulderSum = 0f
        var shoulderCount = 0

        val leftStart = (peakIndex - shoulderRadius).coerceAtLeast(0)
        val leftEnd = (peakIndex - exclusionRadius - 1).coerceAtLeast(-1)
        if (leftEnd >= leftStart) {
            for (index in leftStart..leftEnd) {
                shoulderSum += envelope[index]
                shoulderCount++
            }
        }

        val rightStart = (peakIndex + exclusionRadius + 1).coerceAtMost(envelope.lastIndex + 1)
        val rightEnd = (peakIndex + shoulderRadius).coerceAtMost(envelope.lastIndex)
        if (rightStart <= rightEnd) {
            for (index in rightStart..rightEnd) {
                shoulderSum += envelope[index]
                shoulderCount++
            }
        }

        if (shoulderCount == 0) {
            return 1f
        }

        val shoulderAverage = (shoulderSum / shoulderCount).coerceAtLeast(1e-4f)
        return envelope[peakIndex] / shoulderAverage
    }

    private fun hardSharpnessFloor(): Float {
        return max(0.92f, config.minPeakSharpness * 0.9f)
    }

    private fun localBackgroundAverage(
        envelope: FloatArray,
        centerSample: Int,
        backgroundWindowSamples: Int,
    ): Float {
        val backgroundEnd = (centerSample - 1).coerceAtLeast(0)
        val backgroundStart = (backgroundEnd - backgroundWindowSamples + 1).coerceAtLeast(0)
        if (backgroundEnd < backgroundStart) {
            return 1e-4f
        }
        var sum = 0f
        var count = 0
        for (index in backgroundStart..backgroundEnd) {
            sum += envelope[index]
            count++
        }
        return (sum / count.coerceAtLeast(1)).coerceAtLeast(1e-4f)
    }

    private fun stereoConsistency(
        left: FloatArray,
        right: FloatArray,
        firstPeakIndex: Int,
        secondPeakIndex: Int,
        peakWindowSamples: Int,
    ): Float {
        val firstEnergy = windowEnergy(left, firstPeakIndex, peakWindowSamples) + windowEnergy(left, secondPeakIndex, peakWindowSamples)
        val secondEnergy = windowEnergy(right, firstPeakIndex, peakWindowSamples) + windowEnergy(right, secondPeakIndex, peakWindowSamples)
        return min(firstEnergy, secondEnergy) / max(firstEnergy, secondEnergy).coerceAtLeast(1e-6f)
    }

    private fun channelEnergyRatio(
        left: FloatArray,
        right: FloatArray,
        firstPeakIndex: Int,
        secondPeakIndex: Int,
        peakWindowSamples: Int,
    ): Float {
        val leftEnergy = windowEnergy(left, firstPeakIndex, peakWindowSamples) + windowEnergy(left, secondPeakIndex, peakWindowSamples)
        val rightEnergy = windowEnergy(right, firstPeakIndex, peakWindowSamples) + windowEnergy(right, secondPeakIndex, peakWindowSamples)
        return leftEnergy / rightEnergy.coerceAtLeast(1e-6f)
    }

    private fun localPeakRatio(
        left: FloatArray,
        right: FloatArray,
        peakIndex: Int,
    ): Float {
        val leftPeak = localPeakAmplitude(left, peakIndex)
        val rightPeak = localPeakAmplitude(right, peakIndex)
        return leftPeak / rightPeak.coerceAtLeast(1e-6f)
    }

    private fun localPeakAmplitude(
        signal: FloatArray,
        center: Int,
    ): Float {
        val start = (center - 2).coerceAtLeast(0)
        val end = (center + 2).coerceAtMost(signal.lastIndex)
        var peak = 0f
        for (index in start..end) {
            peak = max(peak, abs(signal[index]))
        }
        return peak
    }

    private fun windowEnergy(signal: FloatArray, center: Int, window: Int): Float {
        val start = (center - window / 2).coerceAtLeast(0)
        val end = (center + window / 2).coerceAtMost(signal.lastIndex)
        var energy = 0f
        for (index in start..end) {
            val value = signal[index]
            energy += value * value
        }
        return energy
    }

    private fun computeLagConfidence(gccResult: GccPhatResult): Double {
        return ((gccResult.peakRatio - 1.0) * config.lagConfidenceMultiplier)
            .coerceIn(0.0, 1.0)
    }

    private fun buildLagHints(
        gccResult: GccPhatResult,
        lagConfidence: Double,
    ): List<String> {
        val hints = mutableListOf<String>()
        if (gccResult.peakRatio < config.minPeakRatio) {
            hints += "GCC 主峰不够突出"
        } else if (lagConfidence < config.lowLagConfidenceHint) {
            hints += "时差置信度较低，仅作弱提示"
        }
        return hints
    }

    private data class CandidateSelection(
        val bestValid: TapCandidate?,
        val bestObserved: TapCandidate?,
    )
}
