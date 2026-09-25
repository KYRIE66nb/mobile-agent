package xyz.chouxuewei.mobile_agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import xyz.chouxuewei.mobile_agent.core.ThemePreference

/** 聊天界面的语义色。页面只关心颜色用途，三套主题因此能共享同一套布局。 */
data class ChatColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val muted: Color,
    val userBubble: Color,
    val text: Color,
    val secondary: Color,
    val tertiary: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val outline: Color,
    val divider: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val error: Color,
    val errorSoft: Color,
)

private val Paper = ChatColors(
    background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), surfaceRaised = Color(0xFFF4F5F7),
    muted = Color(0xFFF0F1F3), userBubble = Color(0xFFF3F4F6), text = Color(0xFF17191C),
    secondary = Color(0xFF626974), tertiary = Color(0xFFA1A7B0), accent = Color(0xFF0A2957),
    accentSoft = Color(0xFFEEF1FF), onAccent = Color.White, outline = Color(0xFFD6DAE1),
    divider = Color(0xFFE6E8EC), success = Color(0xFF2D7B54), successSoft = Color(0xFFE8F5EE),
    warning = Color(0xFF8B5B12), warningSoft = Color(0xFFFFF4DA), error = Color(0xFFB42318),
    errorSoft = Color(0xFFFFECEA),

)

private val Graphite = ChatColors(
    background = Color(0xFF151719), surface = Color(0xFF1D2023), surfaceRaised = Color(0xFF23272B),
    muted = Color(0xFF292E32), userBubble = Color(0xFF292E32), text = Color(0xFFF0F3F2),
    secondary = Color(0xFFADB5B2), tertiary = Color(0xFF7E8884), accent = Color(0xFF8DE2C2),
    accentSoft = Color(0xFF1E3930), onAccent = Color(0xFF10271F), outline = Color(0xFF4A5350),
    divider = Color(0xFF32383D), success = Color(0xFF8ED5AE), successSoft = Color(0xFF20382C),
    warning = Color(0xFFE9C17C), warningSoft = Color(0xFF40351F), error = Color(0xFFFFB4AB),
    errorSoft = Color(0xFF4A2927),
)

private val Warm = ChatColors(
    background = Color(0xFFFAF7F0), surface = Color(0xFFFFFDFA), surfaceRaised = Color(0xFFF5F1E9),
    muted = Color(0xFFF0EDE5), userBubble = Color(0xFFE9EEE7), text = Color(0xFF302F2B),
    secondary = Color(0xFF676A61), tertiary = Color(0xFF96988E), accent = Color(0xFF52694F),
    accentSoft = Color(0xFFE4ECE2), onAccent = Color.White, outline = Color(0xFFC8CABF),
    divider = Color(0xFFE1DED5), success = Color(0xFF347346), successSoft = Color(0xFFE5F1E7),
    warning = Color(0xFF855C1C), warningSoft = Color(0xFFF6ECD6), error = Color(0xFFAD342A),
    errorSoft = Color(0xFFF8E8E4),
)

val LocalChatColors = staticCompositionLocalOf { Paper }

fun resolveTheme(preference: ThemePreference, systemDark: Boolean): ThemePreference =
    if (preference == ThemePreference.SYSTEM) {
        if (systemDark) ThemePreference.GRAPHITE else ThemePreference.PAPER
    } else preference

@Composable
fun Mobile_agentTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val resolved = resolveTheme(preference, isSystemInDarkTheme())
    val colors = when (resolved) {
        ThemePreference.GRAPHITE -> Graphite
        ThemePreference.WARM -> Warm
        else -> Paper
    }
    val base = if (resolved == ThemePreference.GRAPHITE) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = colors.accent, onPrimary = colors.onAccent,
        primaryContainer = colors.accentSoft, onPrimaryContainer = colors.text,
        secondary = colors.accent, onSecondary = colors.onAccent,
        secondaryContainer = colors.muted, onSecondaryContainer = colors.text,
        tertiary = colors.secondary, background = colors.background, onBackground = colors.text,
        surface = colors.surface, onSurface = colors.text, surfaceVariant = colors.muted,
        onSurfaceVariant = colors.secondary, outline = colors.outline, outlineVariant = colors.divider,
        error = colors.error, onError = Color.White, errorContainer = colors.errorSoft,
        onErrorContainer = colors.error, surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surfaceRaised, surfaceContainerHighest = colors.muted,
        surfaceContainerLow = colors.surface, surfaceContainerLowest = colors.background,
        surfaceTint = Color.Transparent,
    )
    CompositionLocalProvider(LocalChatColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
