package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.chouxuewei.mobile_agent.core.DEFAULT_SINGLE_RUN_MAX_STEPS
import xyz.chouxuewei.mobile_agent.core.MAX_SINGLE_RUN_MAX_STEPS
import xyz.chouxuewei.mobile_agent.core.MIN_SINGLE_RUN_MAX_STEPS
import xyz.chouxuewei.mobile_agent.core.requireValidSingleRunMaxSteps

private val Context.agentExecutionSettingsDataStore by preferencesDataStore("agent_execution_settings")

/** 持久化全局 Agent 执行限制；模型连接参数仍由 ModelSettingsRepository 单独管理。 */
class AgentExecutionSettingsRepository(context: Context) {
    private val store = context.applicationContext.agentExecutionSettingsDataStore
    private val maxStepsKey = intPreferencesKey("single_run_max_steps")

    val maxSteps: Flow<Int> = store.data.map { preferences ->
        preferences[maxStepsKey]
            ?.takeIf { it in MIN_SINGLE_RUN_MAX_STEPS..MAX_SINGLE_RUN_MAX_STEPS }
            ?: DEFAULT_SINGLE_RUN_MAX_STEPS
    }.distinctUntilChanged()

    suspend fun setMaxSteps(value: Int) {
        requireValidSingleRunMaxSteps(value)
        store.edit { it[maxStepsKey] = value }
    }

    suspend fun currentMaxSteps(): Int = maxSteps.first()
}
