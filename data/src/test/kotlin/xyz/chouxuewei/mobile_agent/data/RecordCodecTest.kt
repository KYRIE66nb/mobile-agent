package xyz.chouxuewei.mobile_agent.data

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.Action
import xyz.chouxuewei.mobile_agent.core.ActionResult
import xyz.chouxuewei.mobile_agent.core.AppTarget
import xyz.chouxuewei.mobile_agent.core.DeviceKey
import xyz.chouxuewei.mobile_agent.core.NodeRef

class RecordCodecTest {
    @Test
    fun everyActionCanRoundTripThroughDatabaseJson() {
        val actions = listOf(
            Action.Tap(10, 20),
            Action.LongPress(10, 20, 700),
            Action.Swipe(1, 2, 30, 40, 350),
            Action.InputText("中文🙂", NodeRef("node-1")),
            Action.PressKey(DeviceKey.ENTER),
            Action.OpenApp(AppTarget("com.android.browser")),
            Action.Wait(500),
            Action.EnableNodeAccess,
        )

        actions.forEach { action ->
            assertEquals(action, RecordCodec.decodeAction(RecordCodec.encodeAction(action)))
        }
    }

    @Test
    fun everyResultCanRoundTripThroughDatabaseJson() {
        val results = listOf(
            ActionResult.Performed("完成"),
            ActionResult.Unsupported("不支持"),
            ActionResult.SessionExpired("已失效"),
            ActionResult.ObservationMismatch("观察过期"),
            ActionResult.TargetMismatch("目标不匹配"),
            ActionResult.Failure("执行失败", retryable = true),
        )

        results.forEach { result ->
            assertEquals(result, RecordCodec.decodeResult(RecordCodec.encodeResult(result)))
        }
    }
}
