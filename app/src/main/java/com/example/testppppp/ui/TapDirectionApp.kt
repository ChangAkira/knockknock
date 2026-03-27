package com.example.testppppp.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.testppppp.audio.AudioCaptureStatus
import com.example.testppppp.audio.DetectionDebugInfo
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapDirectionApp(
    viewModel: TapDirectionViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = viewModel::onPermissionResult,
    )
    val initialPermissionGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(initialPermissionGranted) {
        viewModel.onPermissionResult(initialPermissionGranted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("双麦桌面敲击方向识别")
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(uiState)
            ActionCard(
                uiState = uiState,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onStart = viewModel::startListening,
                onStop = viewModel::stopListening,
                onStartCalibration = viewModel::startCalibration,
                onCancelCalibration = viewModel::cancelCalibration,
                onClearCalibration = viewModel::clearCalibration,
            )
            LatestDetectionCard(uiState)
            WaveformCard(uiState.latestDetection?.debugInfo)
            LogsCard(uiState.logs)
        }
    }
}

@Composable
private fun StatusCard(uiState: TapDirectionUiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("权限: ${if (uiState.permissionGranted) "已授予" else "未授予"}")
            Text("采集: ${audioStatusText(uiState.audioCaptureStatus)}")
            Text("标定 JSON: ${if (uiState.hasStoredCalibration) "已存在" else "未生成"}")
            Text(uiState.statusMessage)
            val progress = uiState.calibrationProgress
            val currentDirection = progress.currentDirection?.displayName ?: "无"
            Text("当前标定方向: $currentDirection")
            Text("每方向目标样本: ${progress.targetSamplesPerDirection}")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                progress.collectedCounts.forEach { (direction, count) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    ) {
                        Text(
                            text = "${direction.displayName}: $count/${progress.targetSamplesPerDirection}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    uiState: TapDirectionUiState,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartCalibration: () -> Unit,
    onCancelCalibration: () -> Unit,
    onClearCalibration: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("控制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!uiState.permissionGranted) {
                    Button(onClick = onRequestPermission) {
                        Text("申请录音权限")
                    }
                }
                Button(
                    onClick = onStart,
                    enabled = uiState.permissionGranted && uiState.audioCaptureStatus !is AudioCaptureStatus.Running,
                ) {
                    Text("开始检测")
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = uiState.audioCaptureStatus is AudioCaptureStatus.Running || uiState.audioCaptureStatus is AudioCaptureStatus.Starting,
                ) {
                    Text("停止检测")
                }
                Button(
                    onClick = onStartCalibration,
                    enabled = uiState.audioCaptureStatus is AudioCaptureStatus.Running && !uiState.calibrationProgress.isRunning,
                ) {
                    Text("开始标定")
                }
                OutlinedButton(
                    onClick = onCancelCalibration,
                    enabled = uiState.calibrationProgress.isRunning,
                ) {
                    Text("取消标定")
                }
                OutlinedButton(onClick = onClearCalibration) {
                    Text("清空标定")
                }
            }
            Text(
                "建议流程：先申请录音权限并开始检测，再按上方、右方、下方三个区域依次执行标定，最后观察实时方向结果与置信度。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LatestDetectionCard(uiState: TapDirectionUiState) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("最新检测结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val latest = uiState.latestDetection
            if (latest == null) {
                Text("尚无检测结果")
                return@Column
            }

            Text("是否触发: ${latest.triggered}")
            Text(
                "Δt: ${
                    latest.delayMicroseconds?.let { value -> "%.2f us".format(value) } ?: "N/A"
                }",
            )
            Text("方向: ${latest.sector?.displayName ?: "未分类"}")
            Text("Confidence: ${"%.4f".format(latest.confidence)}")
            Text("几何置信度 C1: ${"%.4f".format(latest.classification?.geometryConfidence ?: 0.0)}")
            Text("信号质量 C2: ${"%.4f".format(latest.classification?.signalQualityConfidence ?: 0.0)}")
            Text("GCC 尖锐度 C3: ${"%.4f".format(latest.classification?.gccSharpnessConfidence ?: 0.0)}")

            val debugInfo = latest.debugInfo
            if (debugInfo != null) {
                HorizontalDivider()
                Text("峰位置: ${debugInfo.peaks.joinToString { "%.1fms".format(it.timeMs) }}")
                Text("质量分: ${"%.1f".format(debugInfo.qualityScore)}")
                Text("绝对峰值: ${"%.4f".format(debugInfo.absolutePeak)}")
                Text("SNR: ${"%.3f".format(debugInfo.snr)}")
                Text("近场比: ${"%.3f".format(debugInfo.nearFieldRatio)}")
                Text("瞬态分: ${"%.3f".format(debugInfo.transientScore)}")
                Text("峰尖锐度: ${"%.3f".format(debugInfo.peakSharpness)}")
                Text("双通道一致性: ${"%.3f".format(debugInfo.stereoConsistency)}")
                Text("第二峰比例: ${"%.3f".format(debugInfo.trailingPeakRatio)}")
                Text("间隔分: ${"%.3f".format(debugInfo.gapScore)}")
                Text("通道能量比: ${"%.3f".format(debugInfo.channelEnergyRatio)}")
                Text("双峰通道比: ${"%.3f".format(debugInfo.combinedPeakChannelRatio)}")
                Text("第一峰通道比: ${"%.3f".format(debugInfo.firstPeakChannelRatio)}")
                Text("第二峰通道比: ${"%.3f".format(debugInfo.secondPeakChannelRatio)}")
                Text("Lag 置信度: ${"%.3f".format(debugInfo.lagConfidence)}")
                Text("主峰/次峰比: ${"%.3f".format(debugInfo.peakRatio)}")
                Text(
                    "KNN 分数: top=${"%.3f".format(latest.classification?.topScore ?: 0.0)}, " +
                        "right=${"%.3f".format(latest.classification?.rightScore ?: 0.0)}, " +
                        "bottom=${"%.3f".format(latest.classification?.bottomScore ?: 0.0)}",
                )
                Text(
                    "KNN 投票: top=${latest.classification?.topVotes ?: 0}, " +
                        "right=${latest.classification?.rightVotes ?: 0}, " +
                        "bottom=${latest.classification?.bottomVotes ?: 0}",
                )
                Text(
                    "平均距离: top=${formatDistance(latest.classification?.topAverageDistance)}, " +
                        "right=${formatDistance(latest.classification?.rightAverageDistance)}, " +
                        "bottom=${formatDistance(latest.classification?.bottomAverageDistance)}",
                )
                Text("分层判定: ${latest.classification?.stageDecision?.ifBlank { "无" } ?: "无"}")
                Text("拒识原因: ${debugInfo.rejectionReason ?: "无"}")
                Text("失败原因: ${debugInfo.failureReasons.joinToString().ifBlank { "无" }}")
            }
        }
    }
}

@Composable
private fun WaveformCard(debugInfo: DetectionDebugInfo?) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("波形与峰标记", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (debugInfo == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("等待音频帧")
                }
                return@Column
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(8.dp),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val centerYLeft = height * 0.28f
                    val centerYRight = height * 0.72f
                    val amplitudeScale = height * 0.22f
                    val envelopeScale = height * 0.35f
                    val sampleCount = max(
                        debugInfo.waveformSnapshot.left.size,
                        max(debugInfo.waveformSnapshot.right.size, debugInfo.waveformSnapshot.envelope.size),
                    ).coerceAtLeast(2)

                    fun sampleX(index: Int): Float = index.toFloat() / (sampleCount - 1) * width

                    drawLine(
                        color = Color.Gray,
                        start = Offset(0f, centerYLeft),
                        end = Offset(width, centerYLeft),
                    )
                    drawLine(
                        color = Color.Gray,
                        start = Offset(0f, centerYRight),
                        end = Offset(width, centerYRight),
                    )

                    debugInfo.waveformSnapshot.left.windowedPairs().forEach { (from, to) ->
                        drawLine(
                            color = Color(0xFF1E88E5),
                            start = Offset(sampleX(from.first), centerYLeft - from.second * amplitudeScale),
                            end = Offset(sampleX(to.first), centerYLeft - to.second * amplitudeScale),
                            strokeWidth = 2f,
                            cap = StrokeCap.Round,
                        )
                    }
                    debugInfo.waveformSnapshot.right.windowedPairs().forEach { (from, to) ->
                        drawLine(
                            color = Color(0xFF43A047),
                            start = Offset(sampleX(from.first), centerYRight - from.second * amplitudeScale),
                            end = Offset(sampleX(to.first), centerYRight - to.second * amplitudeScale),
                            strokeWidth = 2f,
                            cap = StrokeCap.Round,
                        )
                    }
                    debugInfo.waveformSnapshot.envelope.windowedPairs().forEach { (from, to) ->
                        drawLine(
                            color = Color(0xFFE53935),
                            start = Offset(sampleX(from.first), height * 0.5f - from.second * envelopeScale),
                            end = Offset(sampleX(to.first), height * 0.5f - to.second * envelopeScale),
                            strokeWidth = 2f,
                        )
                    }
                    debugInfo.waveformSnapshot.peakIndices.forEach { index ->
                        val x = sampleX(index.coerceIn(0, sampleCount - 1))
                        drawLine(
                            color = Color(0xFFFFB300),
                            start = Offset(x, 0f),
                            end = Offset(x, height),
                            strokeWidth = 1.5f,
                        )
                    }
                }
            }
            Text("蓝线: 左声道，绿线: 右声道，红线: 包络，黄线: 峰位置")
        }
    }
}

@Composable
private fun LogsCard(logs: List<DetectionLogEntry>) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("调试日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (logs.isEmpty()) {
                Text("暂无日志")
                return@Column
            }
            logs.take(20).forEachIndexed { index, log ->
                Text(log.message)
                if (index != logs.take(20).lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun FloatArray.windowedPairs(): List<Pair<Pair<Int, Float>, Pair<Int, Float>>> {
    if (size < 2) {
        return emptyList()
    }
    return (0 until lastIndex).map { index ->
        Pair(Pair(index, this[index]), Pair(index + 1, this[index + 1]))
    }
}

private fun audioStatusText(status: AudioCaptureStatus): String {
    return when (status) {
        AudioCaptureStatus.Idle -> "空闲"
        AudioCaptureStatus.Starting -> "初始化中"
        AudioCaptureStatus.Running -> "运行中"
        is AudioCaptureStatus.Unsupported -> "设备不支持: ${status.reason}"
        is AudioCaptureStatus.Error -> "错误: ${status.reason}"
    }
}

private fun formatDistance(value: Double?): String {
    if (value == null || !value.isFinite()) {
        return "N/A"
    }
    return "%.3f".format(value)
}
