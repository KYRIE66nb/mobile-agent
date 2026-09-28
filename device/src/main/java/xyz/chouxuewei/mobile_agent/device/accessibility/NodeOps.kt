package xyz.chouxuewei.mobile_agent.device.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/** 收集窗口内可见节点；调用方负责统一回收列表与根节点。 */
internal fun collectVisibleNodes(
    node: AccessibilityNodeInfo,
    into: MutableList<AccessibilityNodeInfo>,
    max: Int,
) {
    if (into.size >= max) return
    if (node.isVisibleToUser) into += node
    for (index in 0 until node.childCount) {
        if (into.size >= max) return
        node.getChild(index)?.let { collectVisibleNodes(it, into, max) }
    }
}

internal fun nodeMatchesAny(node: AccessibilityNodeInfo, matchTexts: List<String>): Boolean {
    val text = node.text?.toString()
    val desc = node.contentDescription?.toString()
    return matchTexts.any { match ->
        text?.contains(match, ignoreCase = true) == true || desc?.contains(match, ignoreCase = true) == true
    }
}

/** 命中的文字节点常嵌在可点父级里，向上找最近的可点击祖先；中间节点交给统一回收列表。 */
internal fun clickableSelfOrAncestor(
    node: AccessibilityNodeInfo,
    recyclable: MutableList<AccessibilityNodeInfo>,
): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node
    repeat(4) {
        if (current == null) return null
        if (current.isClickable && current.isEnabled) return current
        current = current.parent?.also { recyclable += it }
    }
    return if (current?.isClickable == true && current.isEnabled) current else null
}
