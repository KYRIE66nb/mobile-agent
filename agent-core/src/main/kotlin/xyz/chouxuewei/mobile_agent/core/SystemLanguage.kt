package xyz.chouxuewei.mobile_agent.core

import java.util.Locale

/**
 * 项目只维护中文和英文两套文案：当前 App 语言为中文时使用中文，其余语言统一回退英文。
 * 设置为“跟随系统”时，Android 会把当前 App 语言同步为系统语言。
 * 核心、模型和工具模块不依赖 Android Context，因此共用这个轻量入口。
 */
fun localizedText(
    chinese: String,
    english: String,
    locale: Locale = Locale.getDefault(),
): String = if (locale.language.equals("zh", ignoreCase = true)) chinese else english
