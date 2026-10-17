package xyz.chouxuewei.mobile_agent.device.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.core.Action
import xyz.chouxuewei.mobile_agent.core.DeviceKey

@RunWith(AndroidJUnit4::class)
class BridgeProtocolTest {
    @Test
    fun screenshotPressKeyIsParsedAsTheNativeScreenshotAction() {
        val action = BridgeProtocol.actionFromJson(
            JSONObject("{\"kind\":\"press_key\",\"key\":\"screenshot\"}"),
        )

        assertEquals(Action.PressKey(DeviceKey.SCREENSHOT), action)
    }
}
