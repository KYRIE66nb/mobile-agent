package xyz.chouxuewei.mobile_agent

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 不使用 Compose 测试时钟，直接验证系统无障碍树中的冷启动、抽屉开关与返回。 */
@RunWith(AndroidJUnit4::class)
class ChatLaunchTest {
    @Test fun realFrameClockStartsWithDrawerClosedAndBackClosesIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            fun nodes(): List<AccessibilityNodeInfo> {
                fun walk(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap { walk(n.getChild(it)) }
                return walk(automation.rootInActiveWindow)
            }
            fun await(condition: ()->Boolean) {
                val end=SystemClock.uptimeMillis()+5000
                while(!condition() && SystemClock.uptimeMillis()<end) SystemClock.sleep(50)
                assertTrue(condition())
            }
            await { nodes().any { it.contentDescription?.toString()=="会话菜单" } }
            SystemClock.sleep(500)
            assertFalse("冷启动不得让关闭的抽屉遮挡聊天",nodes().any { it.text?.toString()=="搜索会话" && it.isVisibleToUser })
            val history=nodes().first { it.contentDescription?.toString()=="历史会话" }
            var clickable: AccessibilityNodeInfo?=history
            while(clickable!=null && !clickable.isClickable) clickable=clickable.parent
            assertTrue(requireNotNull(clickable).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { nodes().any { it.text?.toString()=="搜索会话" && it.isVisibleToUser } }
            pressBack()
            await { nodes().none { it.text?.toString()=="搜索会话" && it.isVisibleToUser } }
            await { nodes().any { it.contentDescription?.toString()=="历史会话" && it.isVisibleToUser } }
        }
    }
}
