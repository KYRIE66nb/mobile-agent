package xyz.chouxuewei.mobile_agent.tools

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.chouxuewei.mobile_agent.core.RequestedToolCall
import xyz.chouxuewei.mobile_agent.core.ToolDefinition
import xyz.chouxuewei.mobile_agent.core.ToolExecutionContext
import xyz.chouxuewei.mobile_agent.core.ToolProvider
import xyz.chouxuewei.mobile_agent.core.ToolResult
import xyz.chouxuewei.mobile_agent.core.ToolSideEffect
import xyz.chouxuewei.mobile_agent.core.localizedText

/**
 * 个人数据集成：联系人/日历查询。
 * 数据边界：只读、按查询过滤、结果只进当前对话上下文；
 * 权限缺失时通过本应用内部授权页引导用户授予，绝不静默读取。
 */
class PersonalDataToolProvider(context: Context) : ToolProvider {

    private val appContext = context.applicationContext

    override val id = "personal_data"
    override val title get() = localizedText("个人数据", "Personal data")
    override val description get() = localizedText("在用户授权后查询联系人与日历事件。", "Query contacts and calendar events after the user grants access.")

    override val definitions get() = listOf(
        ToolDefinition(
            "contacts_search", localizedText("搜索联系人", "Search contacts"),
            localizedText("按姓名或号码片段搜索通讯录，返回姓名、电话和邮箱。需要联系人读取权限，未授权时调用 personal_grant_permission 引导用户授予。结果只包含匹配条目的联系方式，不包含全量通讯录。", "Search the address book by name or number fragment, returning names, phones, and emails. Requires contacts access; when missing, call personal_grant_permission so the user can grant it. Results contain only matching entries, not the whole address book."),
            localizedJsonSchema("""{"type":"object","properties":{"query":{"type":"string","maxLength":100,"description":localizedText("姓名或号码片段；留空返回前 limit 条", "Name or number fragment; empty returns the first limit entries")},"limit":{"type":"integer","minimum":1,"maximum":50,"default":20}},"additionalProperties":false}"""),
            ToolSideEffect.READ, id,
            approvalDescription = localizedText("在通讯录中搜索联系人。", "Search contacts in the address book."),
        ),
        ToolDefinition(
            "calendar_events", localizedText("查询日历事件", "Query calendar events"),
            localizedText("列出从现在起 days_ahead 天内的日历事件（含重复日程展开），返回标题、时间、地点和所属日历。需要日历读取权限，未授权时调用 personal_grant_permission。", "List calendar events within days_ahead days from now (recurring events expanded), returning title, time, location, and calendar name. Requires calendar access; when missing, call personal_grant_permission."),
            localizedJsonSchema("""{"type":"object","properties":{"days_ahead":{"type":"integer","minimum":1,"maximum":90,"default":7},"limit":{"type":"integer","minimum":1,"maximum":50,"default":20}},"additionalProperties":false}"""),
            ToolSideEffect.READ, id,
            approvalDescription = localizedText("读取近期的日历事件。", "Read upcoming calendar events."),
        ),
        ToolDefinition(
            "personal_grant_permission", localizedText("请求数据权限", "Request data permission"),
            localizedText("弹出系统授权框让用户授予 contacts（联系人）或 calendar（日历）读取权限。只在对应查询工具报权限缺失时调用，不要主动反复请求；授权后需要重新调用原查询工具。", "Show the system permission dialog so the user can grant contacts or calendar read access. Call only after a query tool reports missing permission; do not request proactively or repeatedly. The original query must be retried after granting."),
            localizedJsonSchema("""{"type":"object","properties":{"permissions":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"string","enum":["contacts","calendar"]},"description":localizedText("要请求授予的权限名", "Permission names to request")}},"required":["permissions"],"additionalProperties":false}"""),
            ToolSideEffect.READ, id,
            approvalDescription = localizedText("弹出系统权限授权框。", "Show the system permission dialog."),
        ),
    )

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        when (call.toolId) {
            "contacts_search" -> searchContacts(call.arguments())
            "calendar_events" -> calendarEvents(call.arguments())
            "personal_grant_permission" -> grantPermission(call.arguments())
            else -> error(localizedText("个人数据工具不支持 ${call.toolId}", "Personal data tools do not support ${call.toolId}"))
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = runCatching {
        val args = call.arguments()
        when (call.toolId) {
            "contacts_search" -> localizedText("搜索联系人：", "Search contacts: ") +
                (args["query"]?.jsonPrimitive?.contentOrNull?.take(60) ?: "")
            "calendar_events" -> localizedText("查询 ${args["days_ahead"]?.jsonPrimitive?.intOrNull ?: 7} 天内的日历", "Calendar for the next ${args["days_ahead"]?.jsonPrimitive?.intOrNull ?: 7} days")
            "personal_grant_permission" -> localizedText("请求权限：", "Request permission: ") +
                (args["permissions"]?.toString()?.take(60) ?: "")
            else -> null
        }
    }.getOrNull()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissionError(permission: String, scope: String): Nothing =
        error(localizedText(
            "缺少${scope}权限。请先调用 personal_grant_permission 让用户授予，再重试本操作。[missing_permission=$permission]",
            "Missing $scope permission. Call personal_grant_permission so the user can grant it, then retry. [missing_permission=$permission]",
        ))

    private suspend fun searchContacts(args: JsonObject): ToolResult {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            missingPermissionError(Manifest.permission.READ_CONTACTS, localizedText("联系人", "contacts"))
        }
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 50)
        val entries = LinkedHashMap<String, MutableSet<String>>()
        withContext(Dispatchers.IO) {
            // 电话
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val number = cursor.getString(1) ?: continue
                    if (query.isNotEmpty() && !name.contains(query, true) &&
                        !number.filter(Char::isDigit).contains(query.filter(Char::isDigit).ifEmpty { "" })) continue
                    entries.getOrPut(name) { sortedSetOf() } += number
                }
            }
            // 邮箱
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val address = cursor.getString(1) ?: continue
                    if (query.isNotEmpty() && !name.contains(query, true) &&
                        !address.contains(query, true)) continue
                    entries.getOrPut(name) { sortedSetOf() } += address
                }
            }
        }
        val limited = entries.entries.take(limit)
        return ToolResult(
            buildJsonObject {
                put("query", query)
                put("total_matched", entries.size)
                put("returned", limited.size)
                putJsonArray("contacts") {
                    limited.forEach { (name, values) ->
                        add(buildJsonObject {
                            put("name", name)
                            putJsonArray("addresses") { values.forEach { add(it) } }
                        })
                    }
                }
            }.toString(),
            localizedText("找到 ${limited.size} 个联系人", "Found ${limited.size} contacts"),
        )
    }

    private suspend fun calendarEvents(args: JsonObject): ToolResult {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            missingPermissionError(Manifest.permission.READ_CALENDAR, localizedText("日历", "calendar"))
        }
        val days = (args["days_ahead"]?.jsonPrimitive?.intOrNull ?: 7).coerceIn(1, 90)
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 50)
        val now = System.currentTimeMillis()
        val end = now + days * 24L * 3600 * 1000
        // Instances URI 会自动展开重复日程。
        val uri = ContentUris.withAppendedId(
            ContentUris.withAppendedId(CalendarContract.Instances.CONTENT_URI, now), end,
        )
        val events = mutableListOf<JsonObject>()
        withContext(Dispatchers.IO) {
            appContext.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.ALL_DAY,
                ),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && events.size < limit) {
                    events += buildJsonObject {
                        put("title", cursor.getString(0) ?: "")
                        put("begin_epoch_ms", cursor.getLong(1))
                        put("end_epoch_ms", cursor.getLong(2))
                        cursor.getString(3)?.let { put("location", it) }
                        if (cursor.getInt(4) == 1) put("all_day", true)
                    }
                }
            }
        }
        return ToolResult(
            buildJsonObject {
                put("days_ahead", days)
                put("from_epoch_ms", now)
                put("to_epoch_ms", end)
                put("returned", events.size)
                putJsonArray("events") { events.forEach { add(it) } }
            }.toString(),
            localizedText("找到 ${events.size} 个日程", "Found ${events.size} events"),
        )
    }

    private fun grantPermission(args: JsonObject): ToolResult {
        val names = args["permissions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: error(localizedText("缺少 permissions 参数", "Missing permissions parameter."))
        val mapped = names.distinct().map {
            when (it) {
                "contacts" -> Manifest.permission.READ_CONTACTS
                "calendar" -> Manifest.permission.READ_CALENDAR
                else -> error(localizedText("不支持的权限 $it", "Unsupported permission $it"))
            }
        }
        val already = mapped.filter { hasPermission(it) }
        val needed = mapped - already.toSet()
        if (needed.isEmpty()) {
            return ToolResult(
                buildJsonObject { put("granted", true); putJsonArray("already_granted") { mapped.forEach { add(it) } } }.toString(),
                localizedText("权限已全部授予", "All permissions already granted"),
            )
        }
        val intent = Intent(ACTION_REQUEST_PERMISSIONS)
            .setPackage(appContext.packageName)
            .putExtra(EXTRA_PERMISSIONS, needed.toTypedArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return ToolResult(
            buildJsonObject {
                put("dialog_shown", true)
                putJsonArray("requested") { needed.forEach { add(it) } }
                put("note", localizedText("授权框已弹出；用户操作完成后请重试原查询。", "The dialog is shown; retry the original query after the user responds."))
            }.toString(),
            localizedText("已弹出权限授权框", "Permission dialog shown"),
        )
    }

    companion object {
        const val ACTION_REQUEST_PERMISSIONS = "xyz.chouxuewei.mobile_agent.action.REQUEST_PERMISSIONS"
        const val EXTRA_PERMISSIONS = "permissions"
    }
}
