package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import xyz.chouxuewei.mobile_agent.core.ThemePreference

private val Context.appearanceDataStore by preferencesDataStore("appearance")
class AppearanceRepository(context: Context) {
    private val store = context.applicationContext.appearanceDataStore
    private val themeKey = stringPreferencesKey("theme")
    private val currentKey = stringPreferencesKey("current_conversation")
    private val detailedLoggingKey = booleanPreferencesKey("detailed_logging")
    private val persistentOverlayKey = booleanPreferencesKey("persistent_overlay")
    val theme = store.data.map { p -> runCatching { ThemePreference.valueOf(p[themeKey].orEmpty()) }.getOrDefault(ThemePreference.SYSTEM) }.distinctUntilChanged()
    val currentConversation = store.data.map { it[currentKey] }.distinctUntilChanged()
    val detailedLogging = store.data.map { it[detailedLoggingKey] ?: false }.distinctUntilChanged()
    /**
     * 这个开关只表示“空闲时仍保留悬浮按钮”。任务执行、授权和询问仍可临时启动服务，
     * 避免用户关闭常驻后失去必要的后台控制入口。
     */
    val persistentOverlay = store.data.map { it[persistentOverlayKey] ?: false }.distinctUntilChanged()
    suspend fun setTheme(value: ThemePreference) { store.edit { it[themeKey] = value.name } }
    suspend fun setCurrentConversation(id: String) { store.edit { it[currentKey] = id } }
    suspend fun setDetailedLogging(enabled: Boolean) { store.edit { it[detailedLoggingKey] = enabled } }
    suspend fun setPersistentOverlay(enabled: Boolean) { store.edit { it[persistentOverlayKey] = enabled } }
}
