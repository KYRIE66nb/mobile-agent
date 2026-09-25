package xyz.chouxuewei.mobile_agent.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.chouxuewei.mobile_agent.data.ResolvedIflytekSpeechSettings
import xyz.chouxuewei.mobile_agent.data.ResolvedOpenAiSpeechSettings
import xyz.chouxuewei.mobile_agent.data.SpeechApiFormat
import xyz.chouxuewei.mobile_agent.data.SpeechSettingsRepository
import xyz.chouxuewei.mobile_agent.model.IflytekSpeechTranscriptionConfig
import xyz.chouxuewei.mobile_agent.model.IflytekSpeechTranscriptionGateway
import xyz.chouxuewei.mobile_agent.model.SpeechTranscriptionConfig
import xyz.chouxuewei.mobile_agent.model.SpeechTranscriptionGateway

enum class VoiceInputSource { CHAT, OVERLAY }

enum class VoiceInputDestination { CURRENT_CONVERSATION, NEW_CONVERSATION }

data class VoiceInputTarget(
    val conversationId: String,
    val reasoningEffort: String?,
    val modelProfileId: String?,
    val source: VoiceInputSource,
    val destination: VoiceInputDestination = VoiceInputDestination.CURRENT_CONVERSATION,
)

sealed interface VoiceInputState {
    data object Idle : VoiceInputState
    data class Recording(val target: VoiceInputTarget) : VoiceInputState
    data class Transcribing(val target: VoiceInputTarget) : VoiceInputState
    data class Failed(val message: String) : VoiceInputState
}

/**
 * 统一采集 16kHz/16bit/单声道 PCM 并封装为 WAV。OpenAI 可直接上传 WAV，讯飞则跳过
 * 44 字节 WAV 头后逐帧发送 PCM，从录音源头保证两种协议使用同一份兼容音频。
 */
class VoiceInputController(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SpeechSettingsRepository,
    private val openAiGateway: SpeechTranscriptionGateway,
    private val iflytekGateway: IflytekSpeechTranscriptionGateway,
    private val onTranscript: (VoiceInputTarget, String) -> Unit,
    private val onRecordingStopped: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingFile: File? = null
    private var recordingJob: Job? = null
    @Volatile private var capturing = false
    private var recordingStartedAt = 0L

    fun start(target: VoiceInputTarget): Result<Unit> = runCatching {
        check(_state.value is VoiceInputState.Idle || _state.value is VoiceInputState.Failed) {
            "已有语音输入正在处理"
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "当前设备不支持 16 kHz 单声道录音" }
        val bufferSize = maxOf(minBuffer, FRAME_BYTES * 4)
        @Suppress("DEPRECATION")
        val created = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        check(created.state == AudioRecord.STATE_INITIALIZED) {
            created.release()
            "无法初始化麦克风"
        }
        val directory = File(appContext.cacheDir, "voice-input").apply { mkdirs() }
        val file = File(directory, "voice-${UUID.randomUUID()}.wav")
        writeEmptyWavHeader(file)
        try {
            created.startRecording()
        } catch (failure: Exception) {
            created.release()
            file.delete()
            throw failure
        }
        check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            created.release()
            file.delete()
            "麦克风没有开始录音"
        }
        audioRecord = created
        recordingFile = file
        recordingStartedAt = SystemClock.elapsedRealtime()
        capturing = true
        recordingJob = scope.launch(Dispatchers.IO) {
            capturePcm(created, file, bufferSize)
        }
        _state.value = VoiceInputState.Recording(target)
    }.onFailure { failure ->
        capturing = false
        _state.value = VoiceInputState.Failed(recordingError(failure))
    }

    fun finish() = finish(VoiceInputDestination.CURRENT_CONVERSATION)

    fun finish(destination: VoiceInputDestination) {
        val current = _state.value as? VoiceInputState.Recording ?: return
        val file = recordingFile ?: return cancel()
        // 松手时才确定发送位置，让同一次录音可以根据最终滑动方向选择当前或新会话。
        val target = current.target.copy(destination = destination)
        val duration = SystemClock.elapsedRealtime() - recordingStartedAt
        val writer = recordingJob
        stopCapture()
        onRecordingStopped()
        clearCaptureReferences()
        _state.value = VoiceInputState.Transcribing(target)
        scope.launch {
            writer?.join()
            if (duration < MIN_RECORDING_MILLIS || file.length() <= WAV_HEADER_BYTES) {
                file.delete()
                _state.value = VoiceInputState.Failed("录音时间太短，请按住说话")
                return@launch
            }
            try {
                val text = when (val resolved = settings.resolve()) {
                    is ResolvedOpenAiSpeechSettings -> openAiGateway.transcribe(
                        SpeechTranscriptionConfig(resolved.endpointUrl, resolved.model, resolved.apiKey),
                        file,
                    )
                    is ResolvedIflytekSpeechSettings -> iflytekGateway.transcribe(
                        IflytekSpeechTranscriptionConfig(
                            resolved.endpointUrl,
                            resolved.appId,
                            resolved.apiKey,
                            resolved.apiSecret,
                            resolved.language,
                            resolved.accent,
                        ),
                        file,
                    )
                }
                onTranscript(target, text)
                _state.value = VoiceInputState.Idle
            } catch (failure: Exception) {
                _state.value = VoiceInputState.Failed(failure.message ?: "语音转写失败，请重试")
            } finally {
                file.delete()
            }
        }
    }

    fun cancel() {
        val file = recordingFile
        val writer = recordingJob
        stopCapture()
        onRecordingStopped()
        clearCaptureReferences()
        _state.value = VoiceInputState.Idle
        scope.launch {
            writer?.join()
            file?.delete()
        }
    }

    fun clearFailure() {
        if (_state.value is VoiceInputState.Failed) _state.value = VoiceInputState.Idle
    }

    suspend fun verifyConfiguration(format: SpeechApiFormat): Result<Unit> = runCatching {
        val file = createSilentWav(File(appContext.cacheDir, "voice-input").apply { mkdirs() })
        try {
            when (val resolved = settings.resolve(format)) {
                is ResolvedOpenAiSpeechSettings -> openAiGateway.verify(
                    SpeechTranscriptionConfig(resolved.endpointUrl, resolved.model, resolved.apiKey),
                    file,
                )
                is ResolvedIflytekSpeechSettings -> iflytekGateway.verify(
                    IflytekSpeechTranscriptionConfig(
                        resolved.endpointUrl,
                        resolved.appId,
                        resolved.apiKey,
                        resolved.apiSecret,
                        resolved.language,
                        resolved.accent,
                    ),
                    file,
                )
            }
        } finally {
            file.delete()
        }
    }

    private fun capturePcm(record: AudioRecord, file: File, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        try {
            FileOutputStream(file, true).use { output ->
                while (capturing) {
                    val count = record.read(buffer, 0, buffer.size)
                    if (count > 0) output.write(buffer, 0, count)
                    if (SystemClock.elapsedRealtime() - recordingStartedAt >= MAX_RECORDING_MILLIS) {
                        scope.launch { finish() }
                        break
                    }
                }
                output.flush()
            }
        } finally {
            // 即使设备在停止录音时抛出读取异常，也要补齐 WAV 头，避免后续误判成损坏文件。
            updateWavHeader(file)
        }
    }

    private fun stopCapture() {
        capturing = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
    }

    private fun clearCaptureReferences() {
        audioRecord = null
        recordingFile = null
        recordingJob = null
        recordingStartedAt = 0L
    }

    private fun recordingError(failure: Throwable): String = when (failure) {
        is SecurityException -> "没有麦克风权限，请先在设置中允许录音"
        else -> failure.message?.takeIf(String::isNotBlank) ?: "无法启动录音，请重试"
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 1280
        const val WAV_HEADER_BYTES = 44L
        const val MIN_RECORDING_MILLIS = 450L
        const val MAX_RECORDING_MILLIS = 60_000L
    }
}

private fun createSilentWav(directory: File): File {
    val file = File(directory, "speech-check-${UUID.randomUUID()}.wav")
    writeEmptyWavHeader(file)
    FileOutputStream(file, true).use { it.write(ByteArray(16_000 / 4 * 2)) }
    updateWavHeader(file)
    return file
}

private fun writeEmptyWavHeader(file: File) {
    RandomAccessFile(file, "rw").use { output ->
        output.setLength(0)
        output.write(ByteArray(44))
    }
}

private fun updateWavHeader(file: File) {
    val dataSize = (file.length() - 44L).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    RandomAccessFile(file, "rw").use { output ->
        fun writeAscii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun writeLeInt(value: Int) {
            output.write(value and 0xff)
            output.write(value shr 8 and 0xff)
            output.write(value shr 16 and 0xff)
            output.write(value shr 24 and 0xff)
        }
        fun writeLeShort(value: Int) {
            output.write(value and 0xff)
            output.write(value shr 8 and 0xff)
        }
        output.seek(0)
        writeAscii("RIFF")
        writeLeInt(36 + dataSize)
        writeAscii("WAVEfmt ")
        writeLeInt(16)
        writeLeShort(1)
        writeLeShort(1)
        writeLeInt(16_000)
        writeLeInt(16_000 * 2)
        writeLeShort(2)
        writeLeShort(16)
        writeAscii("data")
        writeLeInt(dataSize)
    }
}
