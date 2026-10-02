package xyz.chouxuewei.mobile_agent.substitution

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.chouxuewei.mobile_agent.core.substitution.SideTimer
import xyz.chouxuewei.mobile_agent.core.substitution.TimerSide
import xyz.chouxuewei.mobile_agent.data.SubstitutionTimerRepository
import kotlin.math.abs

/**
 * 替身计时悬浮窗：小胶囊、FLAG_NOT_FOCUSABLE 不抢游戏触控，
 * 拖动可重新定位并持久化。倒计时只显示"推测恢复"——不保证游戏内真实状态。
 */
class SubstitutionOverlay(
    private val context: Context,
    private val repo: SubstitutionTimerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var enemyText: TextView? = null
    private var selfText: TextView? = null
    private var statusText: TextView? = null
    private var shown = false
    private var savedX: Int? = null
    private var savedY: Int? = null

    init {
        scope.launch { repo.config.collect {
            savedX = it.overlayX; savedY = it.overlayY
        } }
    }

    fun show() {
        if (shown) return
        if (!android.provider.Settings.canDrawOverlays(context)) {
            xyz.chouxuewei.mobile_agent.core.AgentLog.w("SubTimer") {
                "overlay skipped: SYSTEM_ALERT_WINDOW not granted"
            }
            return
        }
        val container = buildView()
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX ?: 40
            y = savedY ?: 120
        }
        view = container
        params = p
        shown = true
        runCatching { windows.addView(container, p) }
            .onSuccess {
                xyz.chouxuewei.mobile_agent.core.AgentLog.i("SubTimer") {
                    "overlay attached at ${p.x},${p.y}"
                }
            }
            .onFailure { t ->
                shown = false
                view = null; params = null
                enemyText = null; selfText = null; statusText = null
                xyz.chouxuewei.mobile_agent.core.AgentLog.e("SubTimer", t) {
                    "overlay attach failed"
                }
            }
    }

    fun hide() {
        if (!shown) return
        shown = false
        view?.let { runCatching { windows.removeView(it) } }
        view = null; params = null
        enemyText = null; selfText = null; statusText = null
    }

    fun updateTimers(self: SideTimer, enemy: SideTimer, showSelf: Boolean) {
        val now = SystemClock.elapsedRealtime()
        enemyText?.text = timerText("敌方", enemy, now)
        selfText?.apply {
            visibility = if (showSelf) View.VISIBLE else View.GONE
            text = timerText("我方", self, now)
        }
        statusText?.text = when {
            enemy.conflict || self.conflict -> "⚠ 候选冲突"
            enemy.active || self.active -> "疑似替身冷却中"
            else -> "监视中"
        }
    }

    private fun timerText(label: String, t: SideTimer, now: Long): String = when {
        !t.active -> "$label 未知"
        t.remaining(now) <= 0 -> "$label 推测已恢复"
        else -> "$label %.1fs%s".format(t.remaining(now) / 1000f, if (t.suspected) "?" else "")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildView(): View {
        val pad = dp(8)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            background = GradientDrawable().apply {
                setColor(0xCC101318.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        enemyText = TextView(context).apply {
            setTextColor(0xFFFF7043.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            text = "敌方 未知"
        }
        selfText = TextView(context).apply {
            setTextColor(0xFF81C784.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "我方 未知"
        }
        statusText = TextView(context).apply {
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            text = "监视中"
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallButton("暂停") {
                SubstitutionTimerCoordinator.pause()
            })
            addView(smallButton("重置") {
                SubstitutionTimerCoordinator.resetTimers()
            })
        }
        root.addView(enemyText)
        root.addView(selfText)
        root.addView(statusText)
        root.addView(controls)

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false
        root.setOnTouchListener { _, ev ->
            val p = params ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = p.x; startY = p.y; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!dragging && abs(dx) + abs(dy) > dp(6)) dragging = true
                    if (dragging) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        windows.updateViewLayout(root, p)
                    }
                    dragging
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        scope.launch { repo.saveOverlayPosition(p.x, p.y) }
                    }
                    dragging
                }
                else -> false
            }
        }
        return root
    }

    private fun smallButton(label: String, onClick: () -> Unit): View =
        TextView(context).apply {
            text = label
            setTextColor(0xFF90CAF9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun destroy() = hide()
}
