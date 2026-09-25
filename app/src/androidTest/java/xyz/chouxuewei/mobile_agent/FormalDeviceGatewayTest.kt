package xyz.chouxuewei.mobile_agent

import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import xyz.chouxuewei.mobile_agent.core.Action
import xyz.chouxuewei.mobile_agent.core.ActionResult
import xyz.chouxuewei.mobile_agent.core.AppTarget
import xyz.chouxuewei.mobile_agent.core.DeviceResult
import xyz.chouxuewei.mobile_agent.core.ExecutionMode
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

/** 直接验证正式接口的主屏会话，以及过期观察和关闭会话的明确结果。 */
@RunWith(AndroidJUnit4::class)
class FormalDeviceGatewayTest {
    @Test fun mainSessionUsesOpenObserveExecuteCloseContract() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PrototypeApplication
        val gateway = app.deviceGateway
        val launchIntent = checkNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(launchIntent)

        val opened = gateway.openSession(ExecutionMode.MAIN_DISPLAY)
        assertTrue(opened is DeviceResult.Success)
        val session = (opened as DeviceResult.Success).value
        try {
            val first = gateway.observe(session.id)
            assertTrue(first is DeviceResult.Success)
            val observation = (first as DeviceResult.Success).value
            assertNotNull(observation.screenshot)
            assertTrue(observation.viewport.width > 0 && observation.viewport.height > 0)

            // 目标手机主屏大于 720×1280；使用虚拟屏范围以外的安全边缘坐标，防止主屏误套固定尺寸。
            if (observation.viewport.width > 720 && observation.viewport.height > 1280) {
                val mainDisplayTap = gateway.execute(
                    session.id,
                    observation.id,
                    Action.Tap(observation.viewport.width - 1, observation.viewport.height - 1),
                )
                assertTrue(mainDisplayTap.toString(), mainDisplayTap is ActionResult.Performed)
                val afterTap = gateway.observe(session.id)
                assertTrue(afterTap is DeviceResult.Success)
            }

            val currentObservation = (gateway.observe(session.id) as DeviceResult.Success).value
            val wrongObservation = gateway.execute(session.id, "not-current", Action.Wait(1))
            assertTrue(wrongObservation is ActionResult.ObservationMismatch)
            assertTrue(gateway.execute(session.id, currentObservation.id, Action.Wait(1)) is ActionResult.Performed)
            assertTrue(
                gateway.execute(session.id, currentObservation.id, Action.Wait(1)) is ActionResult.ObservationMismatch,
            )

            assertTrue(gateway.observe(session.id) is DeviceResult.Success)
            assertTrue(gateway.closeSession(session.id) is DeviceResult.Success)
            assertTrue(gateway.observe(session.id) is DeviceResult.SessionExpired)
        } finally {
            gateway.closeSession(session.id)
        }
    }

    @Test fun explicitNodeReferenceIsRevalidatedBeforeTextInput() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PrototypeApplication
        val gateway = app.deviceGateway
        val launchIntent = checkNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val testPackage = instrumentation.context.packageName
        instrumentation.context.startActivity(
            Intent().setClassName(testPackage, NodeReferenceFixtureActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()

        val opened = gateway.openSession(ExecutionMode.MAIN_DISPLAY)
        assertTrue(opened is DeviceResult.Success)
        val session = (opened as DeviceResult.Success).value
        try {
            val beforeEnable = gateway.observe(session.id) as DeviceResult.Success
            assertTrue(
                gateway.execute(session.id, beforeEnable.value.id, Action.EnableNodeAccess) is ActionResult.Performed,
            )

            val observed = (gateway.observe(session.id) as DeviceResult.Success).value
            val editable = checkNotNull(observed.nodes.firstOrNull {
                it.editable && it.enabled && it.visible && it.packageName == testPackage
            }) { "测试页面没有找到可编辑节点" }
            assertTrue(
                gateway.execute(
                    session.id,
                    observed.id,
                    Action.InputText("节点引用验证", editable.ref),
                ) is ActionResult.Performed,
            )

            val afterInput = (gateway.observe(session.id) as DeviceResult.Success).value
            val currentEditable = checkNotNull(afterInput.nodes.firstOrNull {
                it.editable && it.enabled && it.visible && it.packageName == testPackage
            })

            // 不通过 DeviceGateway 切换窗口，保留旧 observationId，专门验证节点身份会被重新核对。
            app.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            delay(500)
            val staleTarget = gateway.execute(
                session.id,
                afterInput.id,
                Action.InputText("这段文字不应写入新窗口", currentEditable.ref),
            )
            assertTrue(staleTarget.toString(), staleTarget is ActionResult.TargetMismatch)
        } finally {
            gateway.closeSession(session.id)
            app.startActivity(launchIntent)
        }
    }

    @Test fun settingsLaunchUsesControlledTargetRegistry() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PrototypeApplication
        val gateway = app.deviceGateway
        val launchIntent = checkNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(launchIntent)
        instrumentation.waitForIdleSync()

        val opened = gateway.openSession(ExecutionMode.MAIN_DISPLAY)
        assertTrue(opened is DeviceResult.Success)
        val session = (opened as DeviceResult.Success).value
        try {
            val observed = (gateway.observe(session.id) as DeviceResult.Success).value
            val launched = gateway.execute(
                session.id,
                observed.id,
                Action.OpenApp(AppTarget("com.android.settings")),
            )
            assertTrue(launched.toString(), launched is ActionResult.Performed)

            val settingsObservation = (gateway.observe(session.id) as DeviceResult.Success).value
            val rejected = gateway.execute(
                session.id,
                settingsObservation.id,
                Action.OpenApp(AppTarget("com.example.not.installed")),
            )
            assertTrue(rejected.toString(), rejected is ActionResult.Failure)
        } finally {
            gateway.closeSession(session.id)
            app.startActivity(launchIntent)
        }
    }
}
