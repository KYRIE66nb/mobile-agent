package xyz.chouxuewei.mobile_agent.device.accessibility

import java.security.MessageDigest
import xyz.chouxuewei.mobile_agent.core.NodeRef

/**
 * 节点引用只携带稳定身份指纹，不保存 AccessibilityNodeInfo。
 * 页面结构或控件位置变化后指纹会变化，执行层因而会拒绝旧识别结果里的目标。
 */
internal object NodeReference {
    fun create(
        windowId: Int,
        viewId: String?,
        packageName: String?,
        className: String?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): NodeRef {
        val identity = buildString {
            part(windowId.toString())
            part(viewId)
            part(packageName)
            part(className)
            part(left.toString())
            part(top.toString())
            part(right.toString())
            part(bottom.toString())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val hex = buildString(32) {
            for (index in 0 until 16) {
                val value = digest[index].toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
        return NodeRef("node-v1-$hex")
    }

    private fun StringBuilder.part(value: String?) {
        if (value == null) {
            append("-1:")
        } else {
            append(value.length).append(':').append(value)
        }
        append('|')
    }

    private const val HEX = "0123456789abcdef"
}
