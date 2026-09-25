package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

const val MAX_PERSONALIZATION_CHARS = 4_000

private val Context.personalizationDataStore by preferencesDataStore("personalization")

/** 用户主动保存的长期偏好；它独立于对话历史，清空后不会留下隐藏提示词。 */
class PersonalizationRepository(context: Context) {
    private val store = context.applicationContext.personalizationDataStore
    private val instructionsKey = stringPreferencesKey("instructions")

    val instructions: Flow<String> = store.data
        .map { preferences -> preferences[instructionsKey].orEmpty() }
        .distinctUntilChanged()

    suspend fun setInstructions(value: String) {
        val normalized = value.trim()
        require(normalized.length <= MAX_PERSONALIZATION_CHARS) {
            "个性化提示词最多 $MAX_PERSONALIZATION_CHARS 个字符"
        }
        store.edit { preferences ->
            if (normalized.isEmpty()) preferences.remove(instructionsKey)
            else preferences[instructionsKey] = normalized
        }
    }

    suspend fun currentInstructions(): String = instructions.first()
}
