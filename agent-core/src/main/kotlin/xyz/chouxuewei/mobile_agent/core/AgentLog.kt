package xyz.chouxuewei.mobile_agent.core

/**
 * 全局诊断日志入口。核心层不依赖 Android Log，由 App 在启动时安装真实输出器。
 * 调用方只记录 ID、数量、耗时和状态，不得写入密钥、完整对话、工具参数或截图字节。
 */
object AgentLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun write(level: Level, tag: String, message: String, error: Throwable?)
    }

    @Volatile private var sink: Sink? = null
    @Volatile var enabled: Boolean = false

    fun install(output: Sink) {
        sink = output
    }

    fun d(tag: String, message: () -> String) = write(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: () -> String) = write(Level.INFO, tag, message, null)
    fun w(tag: String, error: Throwable? = null, message: () -> String) =
        write(Level.WARN, tag, message, error)
    fun e(tag: String, error: Throwable? = null, message: () -> String) =
        write(Level.ERROR, tag, message, error)

    private inline fun write(
        level: Level,
        tag: String,
        message: () -> String,
        error: Throwable?,
    ) {
        if (!enabled) return
        sink?.write(level, "MA-$tag", message(), error)
    }
}
