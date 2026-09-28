package xyz.chouxuewei.mobile_agent.voice

import xyz.chouxuewei.mobile_agent.core.AgentLog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.ArrayDeque
import java.util.Locale

/**
 * 系统 TTS 播报器：定时任务完成时朗读结果摘要。
 * TextToSpeech 的构造与初始化回调都要求在主线程；首次 speak 才建立服务连接，
 * 语言数据缺失时静默降级（仅记录日志），不影响任务本身的通知交付。
 */
class VoiceAnnouncer(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()

    fun speak(text: String) {
        val spoken = text.take(280)
        if (Looper.myLooper() == Looper.getMainLooper()) enqueue(spoken)
        else main.post { enqueue(spoken) }
    }

    fun shutdown() {
        main.post {
            runCatching { tts?.stop(); tts?.shutdown() }
            tts = null; ready = false
        }
    }

    private fun enqueue(text: String) {
        if (ready) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "announce-" + System.nanoTime())
            return
        }
        pending.addLast(text)
        if (pending.size > 3) pending.removeFirst()
        if (tts == null) init()
    }

    private fun init() {
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                ready = status == TextToSpeech.SUCCESS &&
                    tts?.setLanguage(Locale.getDefault()) !in setOf(
                        TextToSpeech.LANG_MISSING_DATA,
                        TextToSpeech.LANG_NOT_SUPPORTED,
                    )
                if (!ready) {
                    AgentLog.w("Voice") { "TTS unavailable, announcements skipped" }
                    pending.clear()
                    return@TextToSpeech
                }
                while (pending.isNotEmpty()) {
                    tts?.speak(pending.removeFirst(), TextToSpeech.QUEUE_ADD, null, "announce-" + System.nanoTime())
                }
            }
        }.onFailure {
            AgentLog.w("Voice") { "TTS init failed: ${it.message}" }
            pending.clear()
        }
    }
}
