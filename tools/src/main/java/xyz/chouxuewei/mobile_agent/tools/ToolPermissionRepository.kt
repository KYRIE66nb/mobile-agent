package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.chouxuewei.mobile_agent.core.ToolAccess
import xyz.chouxuewei.mobile_agent.core.ToolPermissionMode
import xyz.chouxuewei.mobile_agent.core.ToolPermissionStore

private val Context.toolPermissionDataStore by preferencesDataStore(name = "tool_permissions")

/** 设置按能力组保存；未设置时启用能力，并在每次调用前请求批准。 */
class ToolPermissionRepository(context: Context) : ToolPermissionStore {
    private val store = context.applicationContext.toolPermissionDataStore

    override val accesses: Flow<Map<String, ToolAccess>> = store.data.map { values ->
        val ids = values.asMap().keys.mapNotNull { key ->
            when {
                key.name.startsWith(ENABLED_PREFIX) -> key.name.removePrefix(ENABLED_PREFIX)
                key.name.startsWith(PERMISSION_PREFIX) -> key.name.removePrefix(PERMISSION_PREFIX)
                else -> null
            }
        }.toSet()
        ids.associateWith { id ->
            ToolAccess(
                enabled = values[booleanPreferencesKey(ENABLED_PREFIX + id)] ?: true,
                permission = values[stringPreferencesKey(PERMISSION_PREFIX + id)]
                    ?.let { runCatching { ToolPermissionMode.valueOf(it) }.getOrNull() }
                    ?: ToolPermissionMode.REQUEST_APPROVAL,
            )
        }
    }

    override suspend fun access(capabilityId: String): ToolAccess =
        accesses.first()[capabilityId] ?: ToolAccess()

    override suspend fun setEnabled(capabilityId: String, enabled: Boolean) {
        require(CAPABILITY_ID.matches(capabilityId)) { localizedText("能力 ID 无效", "Invalid capability ID.") }
        store.edit { it[booleanPreferencesKey(ENABLED_PREFIX + capabilityId)] = enabled }
    }

    override suspend fun setPermission(capabilityId: String, mode: ToolPermissionMode) {
        require(CAPABILITY_ID.matches(capabilityId)) { localizedText("能力 ID 无效", "Invalid capability ID.") }
        store.edit { it[stringPreferencesKey(PERMISSION_PREFIX + capabilityId)] = mode.name }
    }

    private companion object {
        const val ENABLED_PREFIX = "capability_enabled."
        const val PERMISSION_PREFIX = "capability_permission."
        val CAPABILITY_ID = Regex("[a-z][a-z0-9_.-]{0,99}")
    }
}
