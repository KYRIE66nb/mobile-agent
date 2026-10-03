package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.chouxuewei.mobile_agent.core.substitution.DetectionTuning
import xyz.chouxuewei.mobile_agent.core.substitution.NormalizedRect
import xyz.chouxuewei.mobile_agent.core.substitution.TimerConfig
import xyz.chouxuewei.mobile_agent.core.substitution.TimerLayout

private val Context.substitutionDataStore by preferencesDataStore("substitution_timer")

/**
 * 替身计时器设置：独立 DataStore，与聊天/模型配置隔离。
 * enabled/autoStart 默认 true 表示"启用启动流程"，不代表已授权采集。
 */
class SubstitutionTimerRepository(context: Context) {

    private val store = context.applicationContext.substitutionDataStore

    private object Keys {
        val ENABLED = booleanPreferencesKey("st_enabled")
        val AUTO_START = booleanPreferencesKey("st_auto_start")
        val GAME_PACKAGE = stringPreferencesKey("st_game_package")
        val SHOW_SELF = booleanPreferencesKey("st_show_self")
        val COOLDOWN_MS = longPreferencesKey("st_cooldown_ms")
        val FRAME_INTERVAL_MS = longPreferencesKey("st_frame_interval_ms")
        val CALIBRATED = booleanPreferencesKey("st_calibrated")
        val LAYOUT = stringPreferencesKey("st_layout")
        val OVERLAY_X = intPreferencesKey("st_overlay_x")
        val OVERLAY_Y = intPreferencesKey("st_overlay_y")
        val SWAP_SIDES = booleanPreferencesKey("st_swap_sides")

        // 检测调参（HSV 阈值三键为旧版残留，新实现用颜色盒，仅保留仍生效的键）
        val LIT_PIXEL_RATIO = floatPreferencesKey("st_lit_pixel_ratio")
        val CONFIRM_FRAMES = intPreferencesKey("st_confirm_frames")
        val INVALID_TIMEOUT = longPreferencesKey("st_invalid_timeout")
        val DEDUPE_MS = longPreferencesKey("st_dedupe_ms")
    }

    val config: Flow<TimerConfig> = store.data.map { p ->
        val defaultTuning = DetectionTuning()
        TimerConfig(
            enabled = p[Keys.ENABLED] ?: true,
            autoStartOnAppOpen = p[Keys.AUTO_START] ?: true,
            gamePackage = p[Keys.GAME_PACKAGE] ?: "",
            showSelfTimer = p[Keys.SHOW_SELF] ?: true,
            cooldownMs = p[Keys.COOLDOWN_MS] ?: 15_000,
            frameIntervalMs = (p[Keys.FRAME_INTERVAL_MS] ?: 140).coerceIn(80, 500),
            calibrated = p[Keys.CALIBRATED] ?: false,
            layout = decodeLayout(p[Keys.LAYOUT]),
            swapSides = p[Keys.SWAP_SIDES] ?: false,
            overlayX = p[Keys.OVERLAY_X],
            overlayY = p[Keys.OVERLAY_Y],
            tuning = DetectionTuning(
                litPixelMinRatio = (p[Keys.LIT_PIXEL_RATIO]
                    ?: defaultTuning.litPixelMinRatio).coerceIn(0.05f, 0.8f),
                confirmFrames = (p[Keys.CONFIRM_FRAMES] ?: defaultTuning.confirmFrames).coerceIn(2, 8),
                invalidBaselineTimeoutMs = p[Keys.INVALID_TIMEOUT]
                    ?: defaultTuning.invalidBaselineTimeoutMs,
                dedupeMs = p[Keys.DEDUPE_MS] ?: defaultTuning.dedupeMs,
            ),
        )
    }.distinctUntilChanged()

    suspend fun current(): TimerConfig = config.first()

    suspend fun setEnabled(value: Boolean) = edit { it[Keys.ENABLED] = value }
    suspend fun setAutoStart(value: Boolean) = edit { it[Keys.AUTO_START] = value }
    suspend fun setGamePackage(value: String) = edit { it[Keys.GAME_PACKAGE] = value.trim() }
    suspend fun setShowSelf(value: Boolean) = edit { it[Keys.SHOW_SELF] = value }
    suspend fun setSwapSides(value: Boolean) = edit { it[Keys.SWAP_SIDES] = value }

    suspend fun setCooldownMs(value: Long) =
        edit { it[Keys.COOLDOWN_MS] = value.coerceIn(1_000, 120_000) }

    suspend fun setFrameIntervalMs(value: Long) =
        edit { it[Keys.FRAME_INTERVAL_MS] = value.coerceIn(80, 500) }

    suspend fun saveLayout(layout: TimerLayout) =
        edit {
            it[Keys.LAYOUT] = encodeLayout(layout)
            it[Keys.CALIBRATED] = true
        }

    suspend fun clearCalibration() = edit { it[Keys.CALIBRATED] = false }

    suspend fun saveOverlayPosition(x: Int, y: Int) = edit {
        it[Keys.OVERLAY_X] = x; it[Keys.OVERLAY_Y] = y
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(block)
    }

    /** 矩形编码：l,t,r,b;l,t,r,b;l,t,r,dots（content;self;enemy;dotsPerSide） */
    private fun encodeLayout(l: TimerLayout): String =
        listOf(l.contentRect, l.selfDots, l.enemyDots)
            .joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" } +
            ";${l.dotsPerSide}"

    private fun decodeLayout(raw: String?): TimerLayout {
        if (raw == null) return TimerLayout()
        return runCatching {
            val parts = raw.split(";")
            fun rect(i: Int) = parts[i].split(",").map(String::toFloat)
                .let { NormalizedRect.of(it[0], it[1], it[2], it[3]) }
            TimerLayout(
                contentRect = rect(0), selfDots = rect(1), enemyDots = rect(2),
                dotsPerSide = parts.getOrNull(3)?.toIntOrNull() ?: 4,
            )
        }.getOrDefault(TimerLayout())
    }
}
