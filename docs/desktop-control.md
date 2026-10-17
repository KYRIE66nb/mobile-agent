# 用电脑控制 Android 手机

桌面端版本：0.2.0，随 App 0.1.20（versionCode 20）提供正式安装包。更新对照：iPhone-use 0.3.15（`8000688`，2026-10-16）。

## 代码分析与更新取舍

现有项目已经有完整的桌面控制基础：Python MCP 处理工具和选择器，`adb forward` 负责 USB 通道，手机上的 `BridgeServer` 做 loopback 鉴权，`AccessibilityDeviceGateway` 执行节点动作、触控、文字和截屏。手机 App 的模型、对话和游戏计时模块不需要为桌面控制重写。

网关契约可复用，但 `BridgeService` 当前新建独立的 `AccessibilityDeviceGateway`，其会话锁不等于 App 内 Agent 的全局任务锁。本次统一的是桌面 MCP 与浏览器预览的会话；App 内任务与桌面任务仍应分时运行，跨入口统一调度不在本轮范围。

| 代码层 | 实际职责 | 本次改善 |
| --- | --- | --- |
| `desktop/server/android_use.py` | MCP schema、操作锁、setup/ready、结果封装 | 如实检查树/视口/前台/截图；暂停期间未就绪；动作已接受后读屏失败保留禁止重放证据；截图只作为图像块发送，文本保留路径/比例，避免重复 base64 占用上下文 |
| `adb_controller.py` / `adb_text.py` | 本地语义选择器、坐标、输入分片和批量 | 续传绑定 session/App/视口/节点/焦点/已写前缀；UTF-16 预检；修复新鲜 App 上下文检查 |
| `adb_apps.py` | 已安装清单与别名目录 | 坏响应/混合清单/污染缓存安全降级，目录信息不会伪装成安装证明 |
| `adb_screen.py` / `adb_preview.py` | 共享 MJPEG 帧与本地浏览器控制 | 页面同源、配对 token 不进 HTML；点按/拖动/滚轮/导航共用会话和锁；旧帧不可操作 |
| `device/.../bridge/BridgeController.kt` | 手机原语路由 | 暂停先拒绝排队动作，再等待已接受动作完成；暂停时拒绝图像与控制 |
| `AccessibilityDeviceGateway.kt` | 无 Root 的主屏执行 | 增加系统截图；锁屏/系统拒绝时失败，虚拟屏明确不支持 |

真机验证补充修复：前台应用从焦点应用窗口读取，避免状态栏被误认为 `systemui`；输入框使用平台 ID 或原窗口内结构路径维持身份，布局增高不会把同一字段误判为新目标；每片输入等待有界精确回读，期间不重复写入。滚动锚点离开或进入手势区域也可证明位移。HTTP 连接闲置 10 秒后主动换新连接，避开手机端 30 秒空闲关闭，动作仍只发送一次。

对照上游的实际更新：

- [`8000688`](https://github.com/zhongerxin/iPhone-use/commit/80006885df81531ce7a874b3b0df65980e25e59b)：系统截图进入手机相册。本项目使用 Android `GLOBAL_ACTION_TAKE_SCREENSHOT` 对应实现。
- [`a1faa65`](https://github.com/zhongerxin/iPhone-use/commit/a1faa65c96ce66886609f0df67289988e2fba7e6)：长文本续传绑定原目标。Android 额外按 Java/Kotlin UTF-16 长度预检，避免部分写入后才撞到 2000 单元限制。
- [`f496687`](https://github.com/zhongerxin/iPhone-use/commit/f496687)：安装清单结构校验。本项目遵守自身 bridge 的 `apps[]` 协议，错误格式明确降级。
- [`5cc2fa8`](https://github.com/zhongerxin/iPhone-use/commit/5cc2fa8)：人工接管时保留预览画面。本项目保留最后一帧、禁用控制，恢复后等待新帧。
- 上游 0.3.11 的直接预览操作：Android 复用现有桥的 tap/swipe 原语实现鼠标操作，没有引入 iOS 的 WDA、Xcode 或 Node 依赖。

上述功能依据实际源码移植；OEM 手势、相册保存和无障碍稳定性仍需要真机验证。上游 GA4/PostHog 遥测没有接入本项目。

## 连接与使用

1. 安装本轮 APK 到 Android 11+ 手机；开启 USB 调试，并在手机上授权电脑。
2. 注册 MCP 服务：命令 `python3`，参数为本项目中 `desktop/server/android_use.py` 的绝对路径；插件配置见 `desktop/mcp.json`。
3. 依次使用 `aua_setup` 的 `discover`、`configure`、`enable_accessibility`、`pair`。手机设置 → 通用 → 电脑控制（USB）开启桥，随后执行 `forward`。
4. 调用 `aua_ready {}`。只有 `ready=true` 才能操作；默认必须取得截图。`screenshot=false` 仍要求有效树、视口、前台应用和可用控制通道。
5. 调用 `aua_screen {"action":"open"}` 打开电脑浏览器。可点击手机画面、拖动、滚轮翻页，或使用返回/主屏幕/最近任务及翻页按钮。

浏览器和 Agent 共用同一设备会话及操作锁。另一操作正在运行时，页面会报告忙碌；不要连点重试。切换手机/配对/端口会关闭旧预览，需要重新打开。预览属于本机临时服务，关闭 MCP 服务后失效。

`start_bridge` 只激活导出的主 Activity，并由 App 按现有设置恢复桥；首次仍须在 App 内开启电脑控制。Android 不允许 adb shell 直接启动非导出的 `BridgeService`，不能把启动请求成功等同于 HTTP 就绪。

### 两种截图

| 需要的结果 | 工具 | 如何确认 |
| --- | --- | --- |
| 把屏幕交给电脑上的模型看 | `aua_observe {"mode":"screenshot"}` | 结果中有图像及像素比例 |
| 截图留在手机，供 App 图片选择器使用 | `aua_screenshot {}` | 到手机相册/Screenshots 或目标 App 选择器查看新图片 |
| 下载电脑当前预览画面 | 预览页“下载当前画面” | 电脑浏览器下载结果；不保存到手机 |

系统截图返回 `screenshot_requested=true, verified=false` 表示系统已接受请求，不能据此声称相册内容已核验。失败或断线后先查看实际结果，不能自动再截一次。

### 长文本与续传

`aua_type_text` 一次提交完整文本；每片最多 500 UTF-16 单元，最终字段连同已有前缀最多 2000 单元。Emoji 通常占两个单元。总量超限会在任何输入前拒绝。

出现 `input_complete=false` 时，下一次只传 `continue_token`。原 session/App/视口/输入框/焦点或已写前缀变化会使续传失效。此时读取字段的真实内容，只补缺失部分，不能重发全文。提交前再次确认焦点仍在原字段。

### 人工认证接管

先调用 `aua_screen {"action":"pause"}`，确认成功后让用户在手机上输入密码/验证码。页面保留最后一帧并显示暂停，新的截图和控制都被拒绝。用户确认完成后再调用 `resume`，等待新画面后继续。

暂停请求失败时不能报告“安全接管已建立”。恢复、重连或旋转后，不沿用旧画面的坐标；预览帧不代替 Agent 的新鲜观察或最终业务核验。

## 验证范围

桌面协议测试使用本地模拟桥，覆盖真实 HTTP、MCP 调度、续传目标变化、安装清单、共享会话、暂停/旧帧拒绝、动作只发一次和就绪失败。浏览器验收使用仓库截图作为模拟帧，验证导航、点击坐标换算、拖动与暂停禁用；不等于真机触控验收。

本地检查命令：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s desktop/tests -v
./gradlew :device:testDebugUnitTest :device:compileDebugAndroidTestKotlin :agent-core:test :app:assembleDebug
```

桌面桥如实报告当前可见应用，允许控制 Mobile Agent 自身；启动主 Activity 后，READY 不承诺恢复此前的应用。任务需要其他 App 时应显式调用 `aua_launch_app`。

2026-10-17 已在型号 2604FRK1EC、Android 16 / API 36、1280×2772 真机验证：升级保留数据，前台窗口识别、已安装应用查询、坐标点框、中文/Emoji 逐字回读、720 字符两次续传、UTF-16 超限无写入、测试草稿清理、应用启动、滚动位移、显式拖动、实时预览、浏览器主屏幕按钮及系统截图相册落盘。相册新增一张 1280×2772 图片；暂停等待在途操作约 1.2 秒，暂停期间控制/图像/READY 均被拒绝，恢复后取得新帧。浏览器闲置 35 秒后的首次 Home 请求成功，随后读取确认手机前台为 `com.miui.home`。

0.1.20 正式 release APK 另完成保留数据覆盖升级、读屏、中文/Emoji 输入及 810 字符两次续传精确回读；测试草稿已清理，设备返回桌面。

自动验证：桌面 104 项测试通过（含安装脚本，`ResourceWarning` 视为错误），另有 9 项发布校验 CLI 测试；`:device` 13 项与 `:agent-core` 184 项 JVM 测试已覆盖设备控制改动。正式 release APK 的版本、签名、非调试属性及模型默认值为空均已校验。`:device:lintDebug` 仍报告 30 个基线已有调用的错误，不能声称全量 lint 通过。发布构建步骤见 [发布指南](RELEASING.md)。

测试运行器限制：Gradle UTP 运行器所需的镜像依赖缺失；原生测试 APK 安装又被手机系统拒绝，因此 instrumentation 用例未实际运行。采用已升级主 App 的真实 HTTP 桥完成上述端到端检查，没有绕过系统安装限制。当前仅覆盖这台设备，其他 OEM/Android 版本仍需分别验证。
