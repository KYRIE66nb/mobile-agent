# 安全策略

更新日期：2026-10-17。

## 支持范围

项目尚处于早期开发阶段。安全修复优先合入默认分支，并随下一个公开版本发布；旧测试版本不承诺长期维护。

## 报告漏洞

请不要在公开 Issue 中披露可直接利用的漏洞、API Key、签名材料、真实对话、设备截图或个人数据。

GitHub 仓库建立后，请优先使用仓库 **Security → Report a vulnerability** 提交私密漏洞报告。若该入口尚未启用，可以先创建一个不包含复现细节的普通 Issue，请维护者建立私密沟通渠道。

报告建议包含：

- 受影响版本或提交。
- Android 版本和设备环境。
- 可复现的最小步骤。
- 实际影响和需要的前置权限。
- 已知缓解方式。

维护者确认问题前，请避免公开完整利用方式。

## Android Use 桥的安全边界

本轮发布涉及两个独立版本：App 0.1.20 与桌面 Android Use 0.2.0。桌面桥只在 Android 11+ 通过无障碍服务控制主屏，不要求 Root；App 内 Agent 的设备工具仍走独立的 Root 通道。桌面桥的 `BridgeService` 不导出，只监听手机 `127.0.0.1:17325`，桌面端通过 USB 调试和 `adb forward` 连接；桌面端不能直接启动该服务，只能启动公开的主 Activity，由 App 按设置开关恢复。

配对 token 由应用 Provider 发给 `adb shell`（`SHELL_UID`），再保存在桌面用户的 `~/.android-use/config.json`；状态目录为 0700，配置与截图产物为 0600。请把 USB 调试授权、桌面状态目录和已配对电脑视为同一信任边界，不要把 token、真实屏幕截图或含敏感内容的预览 URL 放入日志、Issue 或共享目录。

`aua_screen` 的浏览器预览是桌面侧同源临时页面，与桌面 Agent 共用会话和操作锁，但与 App 内 gateway 实例独立。暂停会保留最后一帧并阻止新的图像与手机控制；恢复后必须等待新帧，旧帧坐标不可复用。`aua_observe` 的截图会进入桌面状态目录的有限 JPG 缓存；`aua_screenshot` 通过 Android 系统把图片保存到手机相册/Screenshots，系统接受请求不等于图片已核验。锁屏、无障碍断开、部分 OEM 的后台限制和其它 Android 版本可能使桥或系统截图失败。

报告 Android Use 问题时，请附桌面插件版本、App 版本、Android/OEM、是否使用 Root 或无障碍通道、ADB 授权状态、`aua_setup status`/`aua_ready` 的脱敏结果和最小复现步骤；不要附配对 token、完整屏幕截图、个人数据或模型密钥。
