package xyz.chouxuewei.mobile_agent.substitution

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import xyz.chouxuewei.mobile_agent.core.substitution.NormalizedRect
import xyz.chouxuewei.mobile_agent.core.substitution.TimerConfig
import xyz.chouxuewei.mobile_agent.core.substitution.TimerLayout
import xyz.chouxuewei.mobile_agent.core.substitution.TimerServiceState
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

/** 替身计时器设置页：开关、游戏选择、参数、校准与状态说明。 */
@Composable
fun SubstitutionSettingsPage(app: PrototypeApplication) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by app.substitutionSettings.config.collectAsState(initial = TimerConfig())
    val state by SubstitutionTimerCoordinator.state.collectAsState()
    val ui by SubstitutionTimerService.uiState.collectAsState()
    val overlayGranted = remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> SubstitutionTimerCoordinator.onConsentResult(result.resultCode, result.data) }
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { overlayGranted.value = Settings.canDrawOverlays(context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 状态 ----
        Text("替身计时器", style = MaterialTheme.typography.titleMedium)
        Text(
            text = stateLabel(state, ui.detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 开关 ----
        SettingSwitch("启用替身计时", config.enabled) {
            scope.launch { app.substitutionSettings.setEnabled(it) }
        }
        SettingSwitch("打开 App 时自动进入启动流程", config.autoStartOnAppOpen) {
            scope.launch { app.substitutionSettings.setAutoStart(it) }
        }
        SettingSwitch("显示我方计时", config.showSelfTimer) {
            scope.launch { app.substitutionSettings.setShowSelf(it) }
        }
        SettingSwitch("我方在右侧（实战分边互换）", config.swapSides) {
            scope.launch { app.substitutionSettings.setSwapSides(it) }
        }

        HorizontalDivider()

        // ---- 目标游戏 ----
        GamePackagePicker(config.gamePackage) {
            scope.launch { app.substitutionSettings.setGamePackage(it) }
        }

        // ---- 参数 ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParamField(
                Modifier.weight(1f), "冷却（秒）",
                (config.cooldownMs / 1000).toString(),
            ) { v -> v.toLongOrNull()?.let { scope.launch { app.substitutionSettings.setCooldownMs(it * 1000) } } }
            ParamField(
                Modifier.weight(1f), "采样间隔(ms)",
                config.frameIntervalMs.toString(),
            ) { v -> v.toLongOrNull()?.let { scope.launch { app.substitutionSettings.setFrameIntervalMs(it) } } }
        }
        Text(
            "冷却默认 15 秒为决斗场经验值，可按实际模式调整；识别延迟另计",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        // ---- 权限与会话 ----
        if (!overlayGranted.value) {
            Text("悬浮窗权限未授予，计时无法显示在游戏上方", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = {
                overlayLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")),
                )
            }) { Text("去授权悬浮窗") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { SubstitutionTimerCoordinator.startNow(consentLauncher) }) {
                Text(if (state == TimerServiceState.DISABLED || state == TimerServiceState.NEEDS_PERMISSION) "开始（申请录屏授权）" else "会话进行中")
            }
            if (state == TimerServiceState.MONITORING || state == TimerServiceState.WAITING_FOR_GAME) {
                OutlinedButton(onClick = { SubstitutionTimerCoordinator.pause() }) { Text("暂停本次") }
            }
            if (state == TimerServiceState.PAUSED) {
                OutlinedButton(onClick = { SubstitutionTimerCoordinator.resume() }) { Text("继续") }
            }
            if (state != TimerServiceState.DISABLED) {
                OutlinedButton(onClick = { SubstitutionTimerCoordinator.stop() }) { Text("停止") }
            }
        }

        HorizontalDivider()

        // ---- 校准 ----
        Text("豆槽校准", style = MaterialTheme.typography.titleMedium)
        if (!config.calibrated) {
            Text(
                "未校准：先在游戏里进入一场对局画面，再回到本页用实时画面框选双方豆槽",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        CalibrationPanel(config, app)

        Spacer(Modifier.height(24.dp))
    }
}

private fun stateLabel(state: TimerServiceState, detail: String): String = when (state) {
    TimerServiceState.DISABLED -> "已停止"
    TimerServiceState.NEEDS_PERMISSION -> "等待录屏授权"
    TimerServiceState.NEEDS_CALIBRATION -> "已授权，等待校准"
    TimerServiceState.WAITING_FOR_GAME -> "已待机，等待进入游戏"
    TimerServiceState.MONITORING -> "识别中"
    TimerServiceState.PAUSED -> "已暂停本次"
    TimerServiceState.ERROR -> "异常"
} + if (detail.isNotBlank()) " — $detail" else ""

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ParamField(
    modifier: Modifier,
    label: String,
    value: String,
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onCommit(it) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GamePackagePicker(selected: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }
    var expanded by remember { mutableStateOf(false) }
    val label = apps.firstOrNull { it.first == selected }?.second
        ?: if (selected.isBlank()) "未选择" else selected
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("目标游戏") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            apps.forEach { (pkg, name) ->
                DropdownMenuItem(
                    text = { Text("$name  ($pkg)") },
                    onClick = { onSelect(pkg); expanded = false },
                )
            }
        }
    }
}

/** 校准面板：实时预览帧 + 双侧豆槽滑杆 + 识别预览。 */
@Composable
private fun CalibrationPanel(config: TimerConfig, app: PrototypeApplication) {
    val frame by SubstitutionTimerService.preview.collectAsState()
    DisposableEffect(Unit) {
        SubstitutionTimerService.previewEnabled = true
        onDispose { SubstitutionTimerService.previewEnabled = false }
    }
    var self by remember(config.layout) { mutableStateOf(config.layout.selfDots) }
    var enemy by remember(config.layout) { mutableStateOf(config.layout.enemyDots) }
    val scope = rememberCoroutineScope()

    if (frame == null) {
        Text(
            "无画面：请先开始采集会话并进入游戏，校准页会显示最近一帧（仅内存，不保存）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val bmp = frame!!
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(bmp.width.toFloat() / bmp.height),
    ) {
        Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            fun drawRect(r: NormalizedRect, color: Color) {
                drawRect(
                    color = color,
                    topLeft = Offset(r.left * size.width, r.top * size.height),
                    size = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height),
                    style = Stroke(width = 3f),
                )
            }
            drawRect(self, Color(0xFF81C784))
            drawRect(enemy, Color(0xFFFF7043))
        }
    }
    RectSliders("我方豆槽（绿框）", self) { self = it }
    RectSliders("敌方豆槽（橙框）", enemy) { enemy = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            scope.launch {
                app.substitutionSettings.saveLayout(
                    config.layout.copy(selfDots = self, enemyDots = enemy),
                )
            }
        }) { Text("保存校准") }
        OutlinedButton(onClick = {
            scope.launch { app.substitutionSettings.clearCalibration() }
        }) { Text("清除校准") }
    }
}

@Composable
private fun RectSliders(label: String, rect: NormalizedRect, onChange: (NormalizedRect) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodySmall)
    RectSliderRow("左", rect.left) { onChange(onChangeNorm(rect, l = it)) }
    RectSliderRow("上", rect.top) { onChange(onChangeNorm(rect, t = it)) }
    RectSliderRow("宽", rect.right - rect.left) { onChange(onChangeNorm(rect, rr = (rect.left + it).coerceAtMost(1f))) }
    RectSliderRow("高", rect.bottom - rect.top) { onChange(onChangeNorm(rect, b = (rect.top + it).coerceAtMost(1f))) }
}

@Composable
private fun RectSliderRow(name: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelSmall)
        Slider(
            value = value, onValueChange = onChange,
            valueRange = 0f..1f, modifier = Modifier.weight(1f),
        )
    }
}

private fun onChangeNorm(
    r: NormalizedRect, l: Float? = null, t: Float? = null,
    rr: Float? = null, b: Float? = null,
) = NormalizedRect.of(l ?: r.left, t ?: r.top, rr ?: r.right, b ?: r.bottom)
