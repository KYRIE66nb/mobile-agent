package xyz.chouxuewei.mobile_agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 透明授权 Activity：personal_grant_permission 工具通过内部 action 唤起，
 * 弹出系统权限框后立即结束，不渲染任何界面。
 */
class PermissionRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS)?.toList().orEmpty()
        if (permissions.isEmpty()) {
            finish()
            return
        }
        launcher.launch(permissions.toTypedArray())
    }

    companion object {
        const val EXTRA_PERMISSIONS = "permissions"
    }
}
