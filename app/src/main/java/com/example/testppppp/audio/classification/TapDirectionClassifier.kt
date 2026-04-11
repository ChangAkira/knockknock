package com.example.testppppp.audio.classification

import com.example.testppppp.audio.CalibrationData
import com.example.testppppp.audio.CalibrationSample
import com.example.testppppp.audio.ClassificationResult
import com.example.testppppp.audio.GccPhatResult
import com.example.testppppp.audio.SectorDirection
import com.example.testppppp.audio.TapDetectionConfig
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class TapDirectionClassifier(
    private val config: TapDetectionConfig,
) {
    @Volatile
    private var calibrationData: CalibrationData? = null
    @Volatile
    private var statModels: Map<SectorDirection, StatModel>? = null

    fun updateCalibration(data: CalibrationData?) {
        calibrationData = data
        statModels = data?.let { buildStatModels(it) }
    }

    fun classify(
        delaySeconds: Double,
        qualityScore: Double,
        lagConfidence: Double,
        gccResult: GccPhatResult,
        channelEnergyRatio: Double,
        channelPeakRatio: Double,
        firstPeakChannelRatio: Double,
        secondPeakChannelRatio: Double,
    ): ClassificationResult? {
        val calibration = calibrationData ?: return unavailableClassification("尚无标定数据，无法分类")
        if (calibration.directions.isEmpty()) {
            return unavailableClassification("标定数据为空，无法分类")
        }
        if (!calibration.isComplete()) {
            return unavailableClassification("标定数据不完整，无法分类")
        }

        val rawSamples = calibration.rawSamples
        val topSamples = rawSamples[SectorDirection.TOP].orEmpty()
        val rightSamples = rawSamples[SectorDirection.RIGHT].orEmpty()
        val bottomSamples = rawSamples[SectorDirection.BOTTOM].orEmpty()
        if (topSamples.isEmpty() || rightSamples.isEmpty() || bottomSamples.isEmpty()) {
            return unavailableClassification("标定缺少原始样本，请重新标定")
        }

        val input = FeatureVector(
            delaySeconds = delaySeconds,
            logEnergyRatio = safeLog(channelEnergyRatio),
            logCombinedPeakRatio = safeLog(channelPeakRatio),
            logFirstPeakRatio = safeLog(firstPeakChannelRatio),
            logSecondPeakRatio = safeLog(secondPeakChannelRatio),
            lagConfidence = lagConfidence,
        )

        val (multiClassResult, rightGateResult, statDistanceByLabel) = if (config.statModelEnabled) {
            val models = statModels
            if (models == null || models.size < 3) {
                val allVectors = buildLabeledVectors(calibration)
                val knn = multiClassKnn(
                    input = input,
                    samples = allVectors,
                    k = balancedK(maxK = 5, groupSizes = listOf(topSamples.size, rightSamples.size, bottomSamples.size)),
                )
                val gate = rightVsNotRightKnn(
                    input = input,
                    topSamples = topSamples,
                    rightSamples = rightSamples,
                    bottomSamples = bottomSamples,
                )
                Triple(knn, gate, emptyMap())
            } else {
                val stat = multiClassStatModel(input, models)
                val gate = rightVsNotRightStatModel(input, models)
                Triple(stat.summary, gate, stat.distanceByLabel)
            }
        } else {
            val allVectors = buildLabeledVectors(calibration)
            val knn = multiClassKnn(
                input = input,
                samples = allVectors,
                k = balancedK(
                    maxK = 5,
                    groupSizes = listOf(topSamples.size, rightSamples.size, bottomSamples.size),
                ),
            )
            val gate = rightVsNotRightKnn(
                input = input,
                topSamples = topSamples,
                rightSamples = rightSamples,
                bottomSamples = bottomSamples,
            )
            Triple(knn, gate, emptyMap())
        }

        val stagedDecision = when {
            rightGateResult.acceptRight -> StageDecision.RIGHT
            rightGateResult.rejectAsNotRight -> decideTopOrBottom(input, topSamples, bottomSamples)
            else -> StageDecision.REJECT
        }

        val selectedSector = when (stagedDecision) {
            StageDecision.RIGHT -> SectorDirection.RIGHT
            StageDecision.TOP -> SectorDirection.TOP
            StageDecision.BOTTOM -> SectorDirection.BOTTOM
            StageDecision.REJECT -> null
        }

        val bestScore = when (selectedSector) {
            SectorDirection.TOP -> multiClassResult.scoreByLabel[SectorDirection.TOP] ?: 0.0
            SectorDirection.RIGHT -> multiClassResult.scoreByLabel[SectorDirection.RIGHT] ?: 0.0
            SectorDirection.BOTTOM -> multiClassResult.scoreByLabel[SectorDirection.BOTTOM] ?: 0.0
            null -> 0.0
        }
        val sortedScores = multiClassResult.scoreByLabel.values.sortedDescending()
        val secondBestScore = sortedScores.getOrElse(1) { 0.0 }

        val c1 = bestScore
        val c2 = (qualityScore / 100.0).coerceIn(0.0, 1.0)
        val c3 = lagConfidence.coerceAtLeast(config.lowLagConfidenceHint)
        val confidence = c1 * c2 * c3

        val boundaryReject = selectedSector != null && (bestScore - secondBestScore) < (if (config.statModelEnabled) config.statModelMarginReject else 0.10)
        val peakRatioReject = gccResult.peakRatio < config.minPeakRatio
        val lowConfidenceReject = confidence < config.testingConfidenceThreshold || qualityScore < config.testingAcceptedQualityScore
        val rightBalanceReject = selectedSector == SectorDirection.RIGHT && !rightGateResult.acceptRight
        val statDistanceReject = if (!config.statModelEnabled || selectedSector == null) {
            false
        } else {
            val d = statDistanceByLabel[selectedSector]
            d != null && d.isFinite() && d > config.statModelDistanceRejectThreshold
        }

        val rejectionReason = when {
            stagedDecision == StageDecision.REJECT -> rightGateResult.reason ?: "KNN stage rejected sample"
            lowConfidenceReject -> "Confidence below threshold"
            boundaryReject -> "Decision margin too small"
            rightBalanceReject -> "Right-region KNN gate not satisfied"
            peakRatioReject -> "GCC primary/secondary peak ratio too low"
            statDistanceReject -> "Stat-model distance too large"
            else -> null
        }

        val topScore = multiClassResult.scoreByLabel[SectorDirection.TOP] ?: 0.0
        val rightScore = multiClassResult.scoreByLabel[SectorDirection.RIGHT] ?: 0.0
        val bottomScore = multiClassResult.scoreByLabel[SectorDirection.BOTTOM] ?: 0.0

        return ClassificationResult(
            sector = selectedSector,
            confidence = confidence,
            geometryConfidence = c1,
            signalQualityConfidence = c2,
            gccSharpnessConfidence = c3,
            topScore = topScore,
            rightScore = rightScore,
            bottomScore = bottomScore,
            triggered = rejectionReason == null,
            rejectionReason = rejectionReason,
            stageDecision = rightGateResult.stageDescription + when (stagedDecision) {
                StageDecision.TOP -> " -> top"
                StageDecision.BOTTOM -> " -> bottom"
                StageDecision.RIGHT -> " -> right"
                StageDecision.REJECT -> " -> reject"
            },
            topVotes = multiClassResult.voteCountByLabel[SectorDirection.TOP] ?: 0,
            rightVotes = multiClassResult.voteCountByLabel[SectorDirection.RIGHT] ?: 0,
            bottomVotes = multiClassResult.voteCountByLabel[SectorDirection.BOTTOM] ?: 0,
            topAverageDistance = multiClassResult.averageDistanceByLabel[SectorDirection.TOP] ?: 0.0,
            rightAverageDistance = multiClassResult.averageDistanceByLabel[SectorDirection.RIGHT] ?: 0.0,
            bottomAverageDistance = multiClassResult.averageDistanceByLabel[SectorDirection.BOTTOM] ?: 0.0,
        )
    }

    private fun unavailableClassification(reason: String): ClassificationResult {
        return ClassificationResult(
            sector = null,
            confidence = 0.0,
            geometryConfidence = 0.0,
            signalQualityConfidence = 0.0,
            gccSharpnessConfidence = 0.0,
            topScore = 0.0,
            rightScore = 0.0,
            bottomScore = 0.0,
            triggered = false,
            rejectionReason = reason,
        )
    }

    private fun buildLabeledVectors(calibration: CalibrationData): List<LabeledFeatureVector> {
        return calibration.rawSamples.flatMap { (label, samples) ->
            samples.map { sample ->
                LabeledFeatureVector(
                    label = label,
                    vector = sample.toFeatureVector(),
                )
            }
        }
    }

    private fun decideTopOrBottom(
        input: FeatureVector,
        topSamples: List<CalibrationSample>,
        bottomSamples: List<CalibrationSample>,
    ): StageDecision {
        val result = multiClassKnn(
            input = input,
            samples = topSamples.map { LabeledFeatureVector(SectorDirection.TOP, it.toFeatureVector()) } +
                bottomSamples.map { LabeledFeatureVector(SectorDirection.BOTTOM, it.toFeatureVector()) },
            k = balancedK(maxK = 5, groupSizes = listOf(topSamples.size, bottomSamples.size)),
        )
        val topScore = result.scoreByLabel[SectorDirection.TOP] ?: 0.0
        val bottomScore = result.scoreByLabel[SectorDirection.BOTTOM] ?: 0.0
        return when {
            topScore - bottomScore > 0.10 -> StageDecision.TOP
            bottomScore - topScore > 0.10 -> StageDecision.BOTTOM
            else -> StageDecision.REJECT
        }
    }

    private fun rightVsNotRightKnn(
        input: FeatureVector,
        topSamples: List<CalibrationSample>,
        rightSamples: List<CalibrationSample>,
        bottomSamples: List<CalibrationSample>,
    ): RightGateResult {
        val samples = rightSamples.map { BinaryLabeledVector(BinaryLabel.RIGHT, it.toFeatureVector()) } +
            topSamples.map { BinaryLabeledVector(BinaryLabel.NOT_RIGHT, it.toFeatureVector()) } +
            bottomSamples.map { BinaryLabeledVector(BinaryLabel.NOT_RIGHT, it.toFeatureVector()) }
        val summary = binaryKnn(
            input = input,
            samples = samples,
            k = balancedK(maxK = 5, groupSizes = listOf(rightSamples.size, topSamples.size + bottomSamples.size)),
        )
        val rightScore = summary.scoreByLabel[BinaryLabel.RIGHT] ?: 0.0
        val notRightScore = summary.scoreByLabel[BinaryLabel.NOT_RIGHT] ?: 0.0
        val rightVotes = summary.voteCountByLabel[BinaryLabel.RIGHT] ?: 0
        val notRightVotes = summary.voteCountByLabel[BinaryLabel.NOT_RIGHT] ?: 0
        val rightDistance = summary.averageDistanceByLabel[BinaryLabel.RIGHT] ?: Double.POSITIVE_INFINITY
        val notRightDistance = summary.averageDistanceByLabel[BinaryLabel.NOT_RIGHT] ?: Double.POSITIVE_INFINITY

        return when {
            rightVotes > notRightVotes && rightScore >= 0.52 && rightDistance <= notRightDistance * 1.05 -> {
                RightGateResult(
                    acceptRight = true,
                    rejectAsNotRight = false,
                    reason = null,
                    stageDescription = "KNN rightGate=right",
                )
            }

            notRightVotes > rightVotes && notRightScore >= rightScore -> {
                RightGateResult(
                    acceptRight = false,
                    rejectAsNotRight = true,
                    reason = null,
                    stageDescription = "KNN rightGate=notRight",
                )
            }

            else -> {
                RightGateResult(
                    acceptRight = false,
                    rejectAsNotRight = false,
                    reason = "Right-vs-notRight KNN uncertain",
                    stageDescription = "KNN rightGate=uncertain",
                )
            }
        }
    }

    private fun multiClassKnn(
        input: FeatureVector,
        samples: List<LabeledFeatureVector>,
        k: Int,
    ): MultiClassSummary {
        val scales = featureScales(samples.map(LabeledFeatureVector::vector))
        val weights = FEATURE_WEIGHTS
        val nearest = samples
            .map { sample ->
                val distance = weightedDistance(input, sample.vector, scales, weights)
                Neighbor(sample.label, distance)
            }
            .sortedBy { it.distance }
            .take(k.coerceAtMost(samples.size))

        val voteCountByLabel = SectorDirection.ordered.associateWith { label ->
            nearest.count { it.label == label }
        }
        val scoreByLabel = SectorDirection.ordered.associateWith { label ->
            nearest
                .filter { it.label == label }
                .sumOf { 1.0 / (it.distance + 1e-6) }
        }.normalizeScores()
        val averageDistanceByLabel = SectorDirection.ordered.associateWith { label ->
            val labelNeighbors = nearest.filter { it.label == label }
            if (labelNeighbors.isEmpty()) Double.POSITIVE_INFINITY else labelNeighbors.map { it.distance }.average()
        }
        return MultiClassSummary(scoreByLabel, voteCountByLabel, averageDistanceByLabel)
    }

    private fun binaryKnn(
        input: FeatureVector,
        samples: List<BinaryLabeledVector>,
        k: Int,
    ): BinarySummary {
        val scales = featureScales(samples.map(BinaryLabeledVector::vector))
        val weights = FEATURE_WEIGHTS
        val nearest = samples
            .map { sample ->
                val distance = weightedDistance(input, sample.vector, scales, weights)
                Neighbor(sample.label, distance)
            }
            .sortedBy { it.distance }
            .take(k.coerceAtMost(samples.size))

        val labels = BinaryLabel.entries
        val voteCountByLabel = labels.associateWith { label -> nearest.count { it.label == label } }
        val scoreByLabel = labels.associateWith { label ->
            nearest.filter { it.label == label }.sumOf { 1.0 / (it.distance + 1e-6) }
        }.normalizeScores()
        val averageDistanceByLabel = labels.associateWith { label ->
            val labelNeighbors = nearest.filter { it.label == label }
            if (labelNeighbors.isEmpty()) Double.POSITIVE_INFINITY else labelNeighbors.map { it.distance }.average()
        }
        return BinarySummary(scoreByLabel, voteCountByLabel, averageDistanceByLabel)
    }

    private fun featureScales(vectors: List<FeatureVector>): DoubleArray {
        val floors = doubleArrayOf(5e-5, 0.08, 0.08, 0.08, 0.08, 0.05)
        val dimensions = floors.size
        val means = DoubleArray(dimensions)
        vectors.forEach { vector ->
            val values = vector.toArray()
            for (index in 0 until dimensions) {
                means[index] += values[index]
            }
        }
        for (index in 0 until dimensions) {
            means[index] = means[index] / vectors.size.coerceAtLeast(1)
        }
        val variances = DoubleArray(dimensions)
        vectors.forEach { vector ->
            val values = vector.toArray()
            for (index in 0 until dimensions) {
                val delta = values[index] - means[index]
                variances[index] += delta * delta
            }
        }
        return DoubleArray(dimensions) { index ->
            sqrt((variances[index] / vectors.size.coerceAtLeast(1)).coerceAtLeast(0.0)).coerceAtLeast(floors[index])
        }
    }

    private fun weightedDistance(
        first: FeatureVector,
        second: FeatureVector,
        scales: DoubleArray,
        weights: DoubleArray,
    ): Double {
        val firstValues = first.toArray()
        val secondValues = second.toArray()
        var sum = 0.0
        for (index in firstValues.indices) {
            val normalizedDelta = (firstValues[index] - secondValues[index]) / scales[index]
            sum += weights[index] * normalizedDelta * normalizedDelta
        }
        return sqrt(sum)
    }

    private fun CalibrationSample.toFeatureVector(): FeatureVector {
        return FeatureVector(
            delaySeconds = delaySeconds,
            logEnergyRatio = safeLog(channelEnergyRatio),
            logCombinedPeakRatio = safeLog(channelPeakRatio),
            logFirstPeakRatio = safeLog(firstPeakChannelRatio),
            logSecondPeakRatio = safeLog(secondPeakChannelRatio),
            lagConfidence = lagConfidence,
        )
    }

    private fun safeLog(value: Double): Double = ln(value.coerceAtLeast(1e-6))

    private fun buildStatModels(calibration: CalibrationData): Map<SectorDirection, StatModel> {
        val models = mutableMapOf<SectorDirection, StatModel>()
        SectorDirection.ordered.forEach { label ->
            val vectors = calibration.rawSamples[label].orEmpty().map { it.toFeatureVector() }
            if (vectors.size < 3) return@forEach
            models[label] = StatModel.fromVectors(vectors)
        }
        return models
    }

    private data class StatResult(
        val summary: MultiClassSummary,
        val distanceByLabel: Map<SectorDirection, Double>,
    )

    private fun multiClassStatModel(input: FeatureVector, models: Map<SectorDirection, StatModel>): StatResult {
        val weights = FEATURE_WEIGHTS
        val distances = SectorDirection.ordered.associateWith { label ->
            val model = models.getValue(label)
            model.diagonalMahalanobisDistance(input, weights)
        }
        val scores = distances.mapValues { (_, d) ->
            1.0 / (d.coerceAtLeast(1e-6))
        }.normalizeScores()
        // 在统计模型模式下，voteCount/avgDistance 沿用字段但语义变成“模型距离”
        val voteCount = SectorDirection.ordered.associateWith { 0 }
        val avgDistance = SectorDirection.ordered.associateWith { label -> distances[label] ?: Double.POSITIVE_INFINITY }
        return StatResult(
            summary = MultiClassSummary(scores, voteCount, avgDistance),
            distanceByLabel = distances,
        )
    }

    private fun rightVsNotRightStatModel(input: FeatureVector, models: Map<SectorDirection, StatModel>): RightGateResult {
        val weights = FEATURE_WEIGHTS
        val dRight = models.getValue(SectorDirection.RIGHT).diagonalMahalanobisDistance(input, weights)
        val dTop = models.getValue(SectorDirection.TOP).diagonalMahalanobisDistance(input, weights)
        val dBottom = models.getValue(SectorDirection.BOTTOM).diagonalMahalanobisDistance(input, weights)
        val dNotRight = min(dTop, dBottom)
        val rightScore = 1.0 / dRight.coerceAtLeast(1e-6)
        val notRightScore = 1.0 / dNotRight.coerceAtLeast(1e-6)

        return when {
            rightScore > notRightScore * 1.05 -> RightGateResult(
                acceptRight = true,
                rejectAsNotRight = false,
                reason = null,
                stageDescription = "STAT rightGate=right",
            )
            notRightScore > rightScore * 1.05 -> RightGateResult(
                acceptRight = false,
                rejectAsNotRight = true,
                reason = null,
                stageDescription = "STAT rightGate=notRight",
            )
            else -> RightGateResult(
                acceptRight = false,
                rejectAsNotRight = false,
                reason = "Right-vs-notRight stat-model uncertain",
                stageDescription = "STAT rightGate=uncertain",
            )
        }
    }

    private fun balancedK(
        maxK: Int,
        groupSizes: List<Int>,
    ): Int {
        val positiveSizes = groupSizes.filter { it > 0 }
        if (positiveSizes.isEmpty()) {
            return 1
        }
        val minGroupSize = positiveSizes.minOrNull() ?: 1
        return minOf(maxK, positiveSizes.sum(), minGroupSize * 2 - 1).coerceAtLeast(1)
    }

    private fun <T> Map<T, Double>.normalizeScores(): Map<T, Double> {
        val total = values.sum().coerceAtLeast(1e-9)
        return mapValues { (_, value) -> value / total }
    }

    private data class FeatureVector(
        val delaySeconds: Double,
        val logEnergyRatio: Double,
        val logCombinedPeakRatio: Double,
        val logFirstPeakRatio: Double,
        val logSecondPeakRatio: Double,
        val lagConfidence: Double,
    ) {
        fun toArray(): DoubleArray = doubleArrayOf(
            delaySeconds,
            logEnergyRatio,
            logCombinedPeakRatio,
            logFirstPeakRatio,
            logSecondPeakRatio,
            lagConfidence,
        )
    }

    private data class LabeledFeatureVector(
        val label: SectorDirection,
        val vector: FeatureVector,
    )

    private data class BinaryLabeledVector(
        val label: BinaryLabel,
        val vector: FeatureVector,
    )

    private data class Neighbor<T>(
        val label: T,
        val distance: Double,
    )

    private data class MultiClassSummary(
        val scoreByLabel: Map<SectorDirection, Double>,
        val voteCountByLabel: Map<SectorDirection, Int>,
        val averageDistanceByLabel: Map<SectorDirection, Double>,
    )

    private data class BinarySummary(
        val scoreByLabel: Map<BinaryLabel, Double>,
        val voteCountByLabel: Map<BinaryLabel, Int>,
        val averageDistanceByLabel: Map<BinaryLabel, Double>,
    )

    private data class RightGateResult(
        val acceptRight: Boolean,
        val rejectAsNotRight: Boolean,
        val reason: String?,
        val stageDescription: String,
    )

    private enum class BinaryLabel {
        RIGHT,
        NOT_RIGHT,
    }

    private enum class StageDecision {
        TOP,
        RIGHT,
        BOTTOM,
        REJECT,
    }

    companion object {
        private val FEATURE_WEIGHTS = doubleArrayOf(
            1.4, // delaySeconds
            1.2, // logEnergyRatio
            1.1, // logCombinedPeakRatio
            0.9, // logFirstPeakRatio
            0.9, // logSecondPeakRatio
            0.7, // lagConfidence
        )
    }

    private data class StatModel(
        val mean: DoubleArray,
        val stddev: DoubleArray,
    ) {
        fun diagonalMahalanobisDistance(x: FeatureVector, weights: DoubleArray): Double {
            val values = x.toArray()
            var sum = 0.0
            for (i in values.indices) {
                val sigma = stddev[i].coerceAtLeast(1e-9)
                val z = (values[i] - mean[i]) / sigma
                sum += weights[i] * z * z
            }
            return sqrt(sum)
        }

        companion object {
            fun fromVectors(vectors: List<FeatureVector>): StatModel {
                val dim = vectors.firstOrNull()?.toArray()?.size ?: 0
                val mean = DoubleArray(dim)
                vectors.forEach { v ->
                    val arr = v.toArray()
                    for (i in 0 until dim) mean[i] += arr[i]
                }
                val denom = vectors.size.coerceAtLeast(1).toDouble()
                for (i in 0 until dim) {
                    mean[i] = mean[i] / denom
                }
                val varr = DoubleArray(dim)
                vectors.forEach { v ->
                    val arr = v.toArray()
                    for (i in 0 until dim) {
                        val d = arr[i] - mean[i]
                        varr[i] += d * d
                    }
                }
                val std = DoubleArray(dim) { i ->
                    sqrt((varr[i] / vectors.size.coerceAtLeast(1)).coerceAtLeast(0.0))
                }
                val floors = doubleArrayOf(5e-5, 0.08, 0.08, 0.08, 0.08, 0.05)
                for (i in 0 until dim) {
                    std[i] = max(std[i], floors.getOrElse(i) { 1e-6 })
                }
                return StatModel(mean, std)
            }
        }
    }
}
