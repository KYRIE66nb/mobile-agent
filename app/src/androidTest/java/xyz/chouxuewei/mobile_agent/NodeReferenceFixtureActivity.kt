package xyz.chouxuewei.mobile_agent

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout

/** 只打包进 androidTest APK，为节点引用测试提供不受正式页面布局影响的输入框。 */
class NodeReferenceFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = EditText(this).apply {
            hint = "节点引用测试输入框"
            contentDescription = "节点引用测试输入框"
            isSingleLine = true
        }
        setContentView(FrameLayout(this).apply {
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin, margin, margin)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        })
    }
}
