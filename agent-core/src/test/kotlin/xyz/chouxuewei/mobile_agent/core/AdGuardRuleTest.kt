package xyz.chouxuewei.mobile_agent.core

import org.junit.Assert.*
import org.junit.Test

class AdGuardRuleTest {

    private fun backRule(
        scope: String? = "com.shop.app",
        pattern: String? = "(?i)(webview|browser|adactivity|landing)",
    ) = AdGuardRule(
        id = "r1",
        name = "摇一摇守卫",
        packageScope = scope,
        action = AdGuardAction.AUTO_BACK,
        classPattern = pattern,
    )

    @Test fun `in-app jump matches feature class in scoped package`() {
        val rule = backRule()
        assertTrue(rule.matchesInAppJump("com.shop.app", "com.shop.app.ad.AdWebViewActivity"))
        assertTrue(rule.matchesInAppJump("com.shop.app", "com.shop.app.browser.InnerBrowserActivity"))
    }

    @Test fun `in-app jump requires class pattern`() {
        val rule = backRule(pattern = null)
        assertFalse(rule.matchesInAppJump("com.shop.app", "com.shop.app.ad.AdActivity"))
    }

    @Test fun `in-app jump requires matching package scope`() {
        val rule = backRule()
        assertFalse(rule.matchesInAppJump("com.other.app", "com.other.app.AdActivity"))
    }

    @Test fun `in-app jump ignores normal navigation classes`() {
        val rule = backRule()
        assertFalse(rule.matchesInAppJump("com.shop.app", "com.shop.app.MainActivity"))
        assertFalse(rule.matchesInAppJump("com.shop.app", "com.shop.app.ui.SettingsActivity"))
    }

    @Test fun `in-app jump rejects disabled rules and click rules`() {
        assertFalse(backRule().copy(enabled = false).matchesInAppJump("com.shop.app", "x.AdActivity"))
        val click = AdGuardRule(id = "c", name = "点击", action = AdGuardAction.CLICK_TEXT, matchTexts = listOf("跳过"))
        assertFalse(click.matchesInAppJump("com.shop.app", "x.AdActivity"))
    }

    @Test fun `invalid pattern rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { backRule(pattern = "(") }
    }
}
