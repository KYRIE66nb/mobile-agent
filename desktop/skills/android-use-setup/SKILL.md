---
name: android-use-setup
description: Android Use 的安装与通道建立：adb/platform-tools、USB 调试、手机端 App、无障碍服务、配对 token、桥服务与 adb forward。比 iPhone-use 简单——无签名、无构建、无 Xcode。按本指南完成 aua_setup 各步骤直到 aua_ready 通过。
---

# Android Use 安装与通道建立

## 组成

```
桌面 Agent ──MCP stdio──> server/android_use.py (aua_* 工具)
              │ HTTP 127.0.0.1:18301 (Bearer token)
        adb forward tcp:18301 tcp:17325
              │ 手机 127.0.0.1:17325
     mobile-agent App 内 BridgeService (前台服务)
              └── AccessibilityDeviceGateway → 无障碍服务
```

桥只绑手机 loopback，桌面侧只接 `adb forward` 出来的 127.0.0.1；token 只对 adb shell（SHELL_UID）可见，不会出现在日志或仓库里。状态目录默认 `~/.android-use`（目录 0700，配置与产物 0600），保存 serial/token/端口与截图产物。

## 前提

1. **platform-tools**：`adb --version` 可用；否则安装 Android SDK platform-tools 或设 `ADB` 环境变量指向 adb。
2. **手机端 App**：mobile-agent 已安装到 Android 11+ 手机（`adb install` 或 Android Studio 部署；包名 `xyz.chouxuewei.mobile_agent`），桌面桥需要无障碍截屏能力。
3. **USB 调试**：手机开发者选项开 USB 调试，连接后授权此电脑。

## 步骤（aua_setup 逐步执行）

1. `aua_setup(action="discover")` — 列出 `adb devices`；`unauthorized` 表示手机上要确认授权弹窗。
2. `aua_setup(action="configure", serial="...")` — 选定设备写入状态目录。
3. `aua_setup(action="enable_accessibility")` — 通过 adb `settings put secure enabled_accessibility_services` 直接开通 `…/AgentAccessibilityService`，并置 `accessibility_enabled=1`。大多数 ROM 无需手动进设置；若手机弹确认框请批准。
4. `aua_setup(action="pair")` — 调手机端 `content://…/bridge` 的 `pairing_token`（仅 SHELL_UID 可读），token 存 `~/.android-use/config.json`（0600），不打印。
5. `aua_setup(action="start_bridge")` — 读取 App 内开关；未开启时返回 `user_action_required`，在手机设置 → 通用 → 电脑控制（USB）开启。已开启时启动导出的主 Activity，由 App 恢复桥服务；仍需后续 `ready` 验证，不能从启动请求推断 HTTP 已就绪。
6. `aua_setup(action="forward")` — `adb forward tcp:18301 tcp:17325`。
7. `aua_ready` — 端到端验证：转发恢复、桥响应、无障碍已连接、复用/开 session、读树、有效视口和前台应用；默认还必须读到截图。任一必需项失败或预览暂停都不会返回 `ready=true`。通过后才能做手机任务；不会自动打开预览。

`aua_setup(action="status")` 随时给出逐级检查与 `missing` 列表；照它走即可，不用背步骤。`stop_bridge` 停止（force-stop 整个 App）。

## 手动开通无障碍（备用）

adb 开通失败或 ROM 拦截时：系统设置 → 无障碍 → 已下载的服务 → mobile-agent（AgentAccessibilityService）→ 开启并确认警告。然后回到第 4 步。

## 常见故障

- `adb_unavailable`：装 platform-tools 或设 `ADB`。
- 设备 `unauthorized`：手机弹窗勾选「始终允许」并确定。
- `pairing_failed`：App 未安装或 Provider 未导出；确认 `pm path xyz.chouxuewei.mobile_agent` 有输出。
- `bridge_start_failed`：先打开一次 App（MIUI 等 ROM 限制后台拉起 FGS），或在 App 设置里手动开桥。
- `aua_unreachable`/`http_reachable` false：重新 `forward`；换线/换口检查 `adb devices` 是否掉线。
- `accessibility_off`：无障碍被系统回收（省电策略）——重新 `enable_accessibility` 或在设置里重开。
- `session_busy`：手机 App 正在跑自己的 Agent 任务或锁被占；等它结束再试。

## 卸载与重置

- 换电脑配对：`aua_setup pair` 重新取 token（同一 token，除非手机上重置配对）。
- 清除桌面状态：删 `~/.android-use`。
- 端口冲突：`aua_setup(action="forward", local_port=19301)`，配置、客户端与实际转发会使用相同端口。
