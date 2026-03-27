package com.example.testppppp.audio.input

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.testppppp.audio.AudioCaptureStatus
import com.example.testppppp.audio.StereoAudioFrame
import com.example.testppppp.audio.TapDetectionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DualChannelAudioRecorder(
    private val config: TapDetectionConfig,
) {
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null

    fun start(
        scope: CoroutineScope,
        onStatus: (AudioCaptureStatus) -> Unit,
        onFrame: (StereoAudioFrame) -> Unit,
    ) {
        if (captureJob?.isActive == true) {
            return
        }

        captureJob = scope.launch(Dispatchers.IO) {
            onStatus(AudioCaptureStatus.Starting)
            val record = createAudioRecord()
            if (record == null) {
                onStatus(AudioCaptureStatus.Unsupported("Stereo AudioRecord could not be initialized on this device"))
                return@launch
            }

            audioRecord = record
            val shortsPerRead = (config.hopSizeSamples * 2).coerceAtLeast(512)
            val readBuffer = ShortArray(shortsPerRead)
            val windowSize = config.windowSizeSamples
            val hopSize = config.hopSizeSamples.coerceAtLeast(1)
            val workingCapacity = windowSize * 4
            val leftBuffer = FloatArray(workingCapacity)
            val rightBuffer = FloatArray(workingCapacity)
            var bufferedSamples = 0
            var frameIndex = 0L

            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    onStatus(AudioCaptureStatus.Error("AudioRecord failed to start recording"))
                    return@launch
                }

                onStatus(AudioCaptureStatus.Running)
                while (isActive) {
                    val readCount = record.read(readBuffer, 0, readBuffer.size)
                    if (readCount <= 0) {
                        onStatus(AudioCaptureStatus.Error("Audio read error: $readCount"))
                        break
                    }

                    val stereoFramesRead = readCount / 2
                    if (bufferedSamples + stereoFramesRead >= workingCapacity) {
                        bufferedSamples = 0
                    }

                    for (index in 0 until stereoFramesRead) {
                        val base = index * 2
                        leftBuffer[bufferedSamples + index] = readBuffer[base] / Short.MAX_VALUE.toFloat()
                        rightBuffer[bufferedSamples + index] = readBuffer[base + 1] / Short.MAX_VALUE.toFloat()
                    }
                    bufferedSamples += stereoFramesRead

                    while (bufferedSamples >= windowSize) {
                        val currentFrameIndex = frameIndex++
                        onFrame(
                            StereoAudioFrame(
                                left = leftBuffer.copyOfRange(0, windowSize),
                                right = rightBuffer.copyOfRange(0, windowSize),
                                sampleRateHz = config.sampleRateHz,
                                frameIndex = currentFrameIndex,
                                startSampleIndex = currentFrameIndex * hopSize.toLong(),
                            ),
                        )
                        val remaining = bufferedSamples - hopSize
                        if (remaining > 0) {
                            System.arraycopy(leftBuffer, hopSize, leftBuffer, 0, remaining)
                            System.arraycopy(rightBuffer, hopSize, rightBuffer, 0, remaining)
                        }
                        bufferedSamples = remaining.coerceAtLeast(0)
                    }
                }
            } catch (exception: Exception) {
                onStatus(AudioCaptureStatus.Error(exception.message ?: "Unknown audio capture error"))
            } finally {
                record.stopSafely()
                record.release()
                audioRecord = null
            }
        }
    }

    suspend fun stop() {
        captureJob?.cancelAndJoin()
        captureJob = null
        audioRecord?.stopSafely()
        audioRecord?.release()
        audioRecord = null
    }

    private fun createAudioRecord(): AudioRecord? {
        val bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufferSize <= 0) {
            return null
        }

        val audioSourceCandidates = listOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER,
        )

        return audioSourceCandidates.firstNotNullOfOrNull { source ->
            runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(config.sampleRateHz)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize * 2)
                    .build()
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
        }
    }

    private fun AudioRecord.stopSafely() {
        runCatching {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
        }
    }
}
