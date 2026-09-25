package xyz.chouxuewei.mobile_agent.device.root

import xyz.chouxuewei.mobile_agent.core.localizedText
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object DeviceCommands {
    /** ProcessBuilder 使用独立参数，不把包名、文字或坐标拼成 shell 脚本。 */
    fun run(vararg arguments: String): String {
        val process = ProcessBuilder(*arguments).redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()
        val output = reader.submit<String> {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        try {
            check(process.waitFor(10, TimeUnit.SECONDS)) { localizedText("设备命令超时：${arguments.first()}", "Device command timed out: ${arguments.first()}") }
            val text = output.get(2, TimeUnit.SECONDS)
            check(process.exitValue() == 0 && !text.contains("Error:") && !text.contains("Exception")) {
                text.take(1000).ifBlank { localizedText("设备命令失败：${process.exitValue()}", "Device command failed: ${process.exitValue()}") }
            }
            return text
        } finally {
            process.destroy()
            output.cancel(true)
            reader.shutdownNow()
        }
    }

    // 仅解析任务的显示归属，不返回其他应用的页面内容给界面。
    fun packageDisplays(dump: String, packageName: String): Set<Int> {
        check(Regex("(?m)^\\s*Display #0").containsMatchIn(dump)) { localizedText("无法识别任务显示归属，停止启动应用", "Could not determine the task display; app launch stopped.") }
        var display: Int? = null
        val result = mutableSetOf<Int>()
        val activity = Regex("\\b${Regex.escape(packageName)}/")
        for (line in dump.lineSequence()) {
            // Display 列表之后会再次汇总 Resumed Activity；该摘要没有 displayId，不能沿用上一个虚拟屏编号。
            if (line.trimStart().startsWith("Resumed activities in task display areas")) break
            Regex("^\\s*Display #(\\d+)").find(line)?.let { display = it.groupValues[1].toInt() }
            if (line.contains("ActivityRecord{") && activity.containsMatchIn(line)) {
                display?.let(result::add)
            }
        }
        return result
    }

    /** WindowManager 的逐显示段用于确认刚启动的可见窗口最终落在哪块屏。 */
    fun packageWindowDisplays(dump: String, packageName: String): Set<Int> {
        // RootService 在 HyperOS 上只会看到自己拥有的虚拟屏，因此这里只要求存在可识别的显示段。
        check(Regex("(?m)^\\s*Display: mDisplayId=\\d+\\b").containsMatchIn(dump)) {
            localizedText("无法识别窗口显示归属，停止启动应用", "Could not determine the window display; app launch stopped.")
        }
        var display: Int? = null
        val result = mutableSetOf<Int>()
        for (line in dump.lineSequence()) {
            Regex("^\\s*Display: mDisplayId=(\\d+)\\b").find(line)?.let {
                display = it.groupValues[1].toInt()
            }
            if (line.contains(packageName)) display?.let(result::add)
        }
        return result
    }

    fun browserDisplays(dump: String): Set<Int> = packageDisplays(dump, "com.android.browser")
}
