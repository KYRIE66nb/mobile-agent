# Android Use：电脑 Agent 控制安卓手机 实施计划

> **For execution:** 大型跨层改造，建议按 Milestone 顺序执行；M1（桥）→ M3（MCP）为关键路径，M4/M5 可并行。步骤用 `- [ ]` 跟踪。

**Goal:** 把 iPhone-use（`github.com/zhongerxin/iPhone-use`，桌面 MCP 服务 + 手机端 WDA + 技能 + 实时屏幕）的"电脑 Agent 经 USB 控制真机"能力移植到本仓库，控制目标从 iPhone 换成 Android。

**Architecture:** 复用 iPhone-use 的三层架构，逐层做 Android 对位替换：桌面端是一个 Python 标准库 MCP stdio 服务（工具层基本照搬）；传输层由 `adb forward` 取代 appium-ios-device；手机端由本 App 内新增的 `BridgeService`（loopback HTTP，包装现有 `DeviceGateway`）取代 WebDriverAgent。**核心判断：我们的 App 本身就是一个"Android 版 WDA"**——无障碍节点树、截图、触控注入、Unicode 输入都已实现且比 ADB/uiautomator 方案更快更全，新增的只是把 `DeviceGateway` 暴露给电脑的一层薄 HTTP 协议。

**Tech Stack:** 沿用仓库现状——Kotlin + 协程 + kotlinx.serialization（经 `:agent-core` 传递依赖）+ `org.json`；桌面端 Python 3.9+ 纯标准库（与 iphone_use.py 一致，不引入第三方包）；不引入 NanoHTTPD/Ktor/Node。

**Spec:** 本文件即规格。参考源码在分析时克隆于 `/tmp/iPhone-use`（上游仓库，MIT）。

**核查基线：** `main` HEAD = `c093def`。执行前重新 `git status --short` 核对用户改动。

---

## 0. iPhone-use 功能清单（移植对象）

分析上游仓库后的完整清单，括号内为移植决策：

| 层 | 上游实现 | 规模 | Android 对位 |
|---|---|---|---|
| MCP 入口 | `server/iphone_use.py`：stdio JSON-RPC，17+2 个 `pua_*` 工具，schema 压缩发布，读写注解 | 705 行 | **基本照搬**，工具改名 `aua_*` |
| 控制逻辑 | `server/wda_controller.py`：PhoneController——紧凑节点树、selector 解析、歧义/遮挡/hittable 判定、tap/swipe/type/press/launch/wait/batch/scroll_find/collect_list、observation_id 上下文校验、截图兜底返回、uncertain 语义 | 915 行 | **移植核心**，selector 字段映射到 `NodeSnapshot` |
| 传输客户端 | `server/wda_client.py`：HTTP keep-alive、session 重建、只读重试、指标 | 223 行 | 对位 `adb_client.py`（HTTP 指向 `adb forward` 端口） |
| 设备/服务配置 | `server/wda_setup.py`：Xcode 签名、WDA fetch/build/start、异步 job | 918 行 | **大幅简化**：adb 设备发现 → 校验 App 安装 → 校验无障碍已开 → 配对取 token → 启动桥 → `adb forward`；无构建/签名 |
| App 目录 | `server/wda_apps.py`：安装清单 + 别名目录 + Apple 搜索 | 295 行 | 对位：桥返回 `listApps()` + 内置常用国产 App 目录；去掉 Apple 搜索 |
| 实时屏幕 | `server/wda_screen.py` + `tooling/screen-stream.mjs` + `ui/`：WDA 独立 MJPEG 口 → Codex widget | ~1100 行 | 桥端新增 MJPEG 端点（v1 用无障碍截图 ~3fps）；widget 降级为本地 HTML 预览页（M5） |
| USB 转发 | `tooling/forward.mjs`（appium-ios-device） | 45 行 | `adb forward tcp:L tcp:D`——Python 里 `subprocess` 一行，**Node 依赖整体移除** |
| 文本分段 | `server/wda_text.py`：长文本分段 + continue_token | 68 行 | 照搬协议；Android 端 `ACTION_SET_TEXT` 原生支持 Unicode/中文（比 iOS 更顺） |
| 图像缩放 | `server/wda_image.py`：截图缩放 + `pixel_to_point` | 76 行 | 照搬；Android 坐标即像素，`pixel_to_point=[1,1]` 保留字段兼容逻辑 |
| 遥测 | `server/analytics.py`（PostHog） | 224 行 | **不移植**（本项目隐私定位，M6 可选本地 metrics 已有 `pua_metrics` 对应物） |
| 技能 | `skills/iphone-use*/SKILL.md` + references/（READY 流程、截图兜底、认证接管、故障排查） | ~800 行 | 对位改写为 `android-use` / `android-use-setup` |
| 安装/注册 | `scripts/install.sh`、`mcp.json`、`plugin.json`（Codex marketplace） | ~50 行 | 对位改写；同时产出通用 `mcp.json` 片段适配 Codex / Claude / Devin |
| 测试 | `tests/` 20 个 pytest 文件 | ~2500 行 | 移植关键行为测试（协议、controller、fallback、observation budget） |

**对手机的执行侧，本项目已有等价物且更强**：`DeviceGateway`（`agent-core/.../DeviceContract.kt`）已覆盖 WDA 的能力面——`observe` 返回 `Observation`（节点 `NodeSnapshot` + 截图 `Screenshot` + 视口 + 前台包名）、`execute` 支持 Tap/LongPress/Swipe/InputText/PerformNodeAction/MultiStrokeGesture/PressKey/OpenApp/Wait、`inspect` 轻量刷新、`listApps`、`closeSession`。桥只是把这套接口 HTTP 化。

## 1. 全局约束

1. 不改写现有 Agent 回路（ChatRuntime/DecisionGate/工具执行器）；桥是**独立的第二条控制入口**，与 App 内 Agent 共享 `DeviceGateway` 与会话互斥锁。
2. 桥默认**关闭**；设置页新增"电脑控制（USB）"开关，开启才监听。状态在能力总览页可见。
3. 桥只 bind `127.0.0.1`；所有变更类请求要求 `Authorization: Bearer <pairing_token>`。token 只发给 `adb shell`（`Binder.getCallingUid() == Process.SHELL_UID` 校验），其他本机 App 拿不到。
4. 桌面端只走 `adb forward` 的 loopback 端口（默认本地 18301）；拒绝非 127.0.0.1 的桥地址（同上游 `WDA_URL` 策略）。
5. 保持 iPhone-use 的失败语义：动作超时/断线标 `uncertain=true`，错误附带当前截图供模型决策，不盲重放输入/发送/提交。
6. 不把 Python/Node 打进 APK；桌面端不引入 pip 依赖；Android 侧不引入 HTTP 框架。
7. 桥执行的每个变更类动作写 `AgentLog`（复用现有日志开关），产物不落盘。
8. 第一阶段只覆盖主屏前台（`ExecutionMode.MAIN_DISPLAY`）；Root 虚拟屏经桥暴露列为 M6 可选。

## 2. 先读实际代码（执行前）

- `agent-core/.../DeviceContract.kt`：`DeviceGateway` 全部契约（本计划第 3 节的 HTTP 协议是它的直译）
- `device/.../AndroidDeviceGateway.kt`：`openSession/observe/inspect/execute/closeSession` 与截图路径
- `device/.../accessibility/AgentAccessibilityService.kt`、`AccessibilityWindowCapture.kt`：截图限频 ≥333ms 的约束
- `tools/.../DeviceToolProvider.kt`：`device_observe` 的节点紧凑序列化、`device_action`/`device_batch` 的参数形状（桥复用同一批数据类）
- `agent-core/.../ToolContract.kt`、`AgentExecutionLimits.kt`：工具结果与步数上限的既有约定
- 上游对照：`/tmp/iPhone-use/server/{iphone_use,wda_client,wda_controller,wda_setup,wda_screen,wda_apps,wda_text,wda_image}.py`、`skills/`、`tooling/`、`ui/`
- `app/.../overlay/DeviceOperationOverlayService.kt`：桥激活时复用悬浮窗显示"电脑控制中"状态的接入点
- `PrototypeApplication.kt`：手动装配点，桥服务实例在这里创建/注入

## 3. 架构与协议设计

```
电脑端 agent (Codex/Claude/Devin)
  └─ MCP stdio ─ desktop/server/android_use.py  (aua_* 工具层, 端口 18301)
       └─ HTTP ─ 127.0.0.1:18301 ── adb forward ── 手机 127.0.0.1:17325
                                                    └─ BridgeService (App 进程)
                                                         └─ AndroidDeviceGateway
                                                              └─ AgentAccessibilityService / (v2: RootDeviceService)
```

### 3.1 桥 HTTP API（设备端口 `17325`，全部 JSON，`POST` 除非注明）

| 端点 | 对应 DeviceGateway | 说明 |
|---|---|---|
| `GET /status` | `availability()` | 版本、无障碍是否在线、会话占用、屏幕宽高/旋转、`capabilities` |
| `POST /session` | `openSession(MAIN_DISPLAY)` | 返回 `session_id`；已被占用返回 `session_busy` |
| `DELETE /session` | `closeSession` | |
| `POST /observe` | `observe`/`inspect` | body `{mode:"tree"\|"screenshot"\|"both", include_invisible, max_nodes}`；返回 `Observation` 紧凑 JSON + `observation_id` + `image`(base64 jpeg, `pixel_to_point:[1,1]`) |
| `POST /action` | `execute` | body `{session_id, observation_id?, action:{kind, ...}}`；kind ∈ `tap,long_press,swipe,input_text,node_action,gesture,press_key,open_app,wait`；返回 `ActionResult` 各态 |
| `GET /apps` | `listApps` | `[{label, package_name}]` |
| `POST /key` | `execute(PressKey)` | `home,back,recents,volume_up,volume_down`（见 3.4） |
| `GET /screen` | — | MJPEG 流（M5）：复用 `AccessibilityWindowCapture`/`FrameSource`，帧间隔 ≥350ms，`multipart/x-mixed-replace` |
| `POST /pairing/status` | — | 只读：桥开关状态、token 是否已发（不返回 token） |

selector 解析、歧义候选、scroll_find、collect_list、batch 编排、文本分段全部在**桌面端** `adb_controller.py` 实现（与上游同构），桥保持薄。

### 3.2 配对与授权

- 新增 `BridgePairingProvider : ContentProvider`，`exported=true`，`call("pairing_token")` 仅在 `Binder.getCallingUid() == Process.SHELL_UID` 时返回持久 token（首启生成，存 DataStore/files 0600）。
- 桌面端 `adb_setup` 执行 `adb shell content call --uri content://<applicationId>.bridge --method pairing_token`，解析 Bundle 输出拿 token → 存 `~/.local/share/android-use/`（700/600，同上游状态目录约定）。
- 手机上其他 App 调用同一 provider 得到 `SecurityException`；电脑本机进程即使扫到转发端口，无 token 也只收到 401。
- App 设置页提供"重新生成配对码"按钮（吊销旧 token）。

### 3.3 setup 流程（取代 Xcode 那 918 行）

`aua_setup(action=status|discover|pair|install|enable_bridge|start|stop)` 内部对应 shell 步骤：

1. `adb devices` 找已授权设备（`discover`，多设备回列表让模型选）
2. `adb shell pm list packages <applicationId>` + `dumpsys package <applicationId> | grep versionName`（`install` 缺失时提示用 Releases APK 或 `./gradlew installDebug`）
3. `adb shell settings get secure enabled_accessibility_services` 含 `xyz.chouxuewei.mobile_agent/.accessibility.AgentAccessibilityService`；未开 → `am start -a android.settings.ACCESSIBILITY_SETTINGS` 并走"用户完成"提问流程（对应上游"用户解锁/信任"路径）
4. `adb shell content call` 取 token（`pair`）
5. `adb shell am broadcast -a <applicationId>.BRIDGE_START`（或直接 `am start-foreground-service`；`start`/`stop`）
6. `adb forward tcp:18301 tcp:17325` + `GET /status` 健康检查
7. `aua_ready` = /status 无障碍在线 + session 可建 + observe 返回 + 可选截图验证

对应上游的"异步 job + status 轮询"语义保留：每步幂等、可重入、返回 `job_id`/具体缺项/`next_steps`。

### 3.4 字段与能力映射

| iPhone-use selector 字段 | Android `NodeSnapshot` |
|---|---|
| `label` | `text` 或 `contentDescription`（匹配按序取第一个命中） |
| `name` | `viewId`（resource-id） |
| `value` | `text`（编辑框当前值） |
| `type` | `className` 短名（`Button`,`EditText`,`RecyclerView`…；映射表去掉 `android.widget.`/`androidx.` 前缀） |
| `enabled` | `enabled` |
| `predicate`(NSPredicate) | 不支持；新增 `text_regex`/`desc_regex` 字段兜底 |
| `index` | 同语义（匹配序号） |

| pua_* 动作 | Android `Action` |
|---|---|
| tap(selector/x,y) | `Action.Tap`（selector→bounds 中心点；可点击节点优先 `PerformNodeAction(CLICK)`） |
| swipe(direction,region) | `Action.Swipe`（方向语义同为手指方向） |
| type_text | `Action.InputText`(SET_TEXT) / focused 节点；`submit`→`PressKey(ENTER)` |
| press_button(home/volume±) | `PressKey(HOME)`；`DeviceKey` **需扩** `VOLUME_UP/VOLUME_DOWN`（无障碍 `GLOBAL_ACTION` 无音量，走 `dispatchKeyEvent`/Shizuku `input keyevent` 兜底） |
| launch_app(bundle_id) | `OpenApp(AppTarget(packageName))` |
| wait | 桌面端轮询 `/observe`（有界，同上游） |
| observe/find/batch/scroll_find/collect_list | 桌面端编排 `/observe`+`/action` |
| homescreen | `PressKey(HOME)` 或 `GLOBAL_ACTION_HOME` |

hittable/遮挡判定：WDA 依赖 `isHittable`；Android 端用 `bounds ⊂ viewport` + `visible` + 前景 `packageName` 一致 + 重叠节点启发（同 `DeviceToolProvider` 现有歧义处理），不足的 case 走截图兜底——语义与上游一致。

## 4. 文件结构

```
mobile-agent/
├── device/src/main/java/xyz/chouxuewei/mobile_agent/device/bridge/   【新】
│   ├── BridgeServer.kt          # 协程 ServerSocket loopback HTTP, 路由+鉴权+并发锁
│   ├── BridgeProtocol.kt        # 请求/响应 DTO + Observation/ActionResult JSON 编解码
│   ├── BridgeService.kt         # FGS(specialUse): 持有 ServerSocket, 常驻通知"电脑控制中"
│   ├── BridgePairingProvider.kt # ContentProvider, SHELL_UID 校验发 token
│   └── BridgeController.kt      # HTTP→DeviceGateway 适配, 会话占用锁
├── app/.../settings 或 capabilities 页                            【改】+"电脑控制"开关/配对码重置
├── app/src/main/AndroidManifest.xml                               【改】注册 Service+Provider+BOOT?否
├── desktop/                                                      【新】纯 Python+静态文件，不入 Gradle
│   ├── server/android_use.py     # ←iphone_use.py  (aua_* 17+2 工具)
│   ├── server/adb_client.py      # ←wda_client.py  (HTTP+adb forward 管理)
│   ├── server/adb_controller.py  # ←wda_controller.py
│   ├── server/adb_setup.py       # ←wda_setup.py (adb 流程, ~350行预期)
│   ├── server/adb_apps.py        # ←wda_apps.py (+内置国产 App 目录 apps.json)
│   ├── server/adb_screen.py      # ←wda_screen.py (MJPEG hub)
│   ├── server/adb_text.py        # ←wda_text.py
│   ├── server/adb_image.py       # ←wda_image.py
│   ├── skills/android-use/SKILL.md + references/                 # ←iphone-use 技能改写
│   ├── skills/android-use-setup/SKILL.md + references/
│   ├── scripts/install.sh        # 注册 mcp + 复制 skills (Codex/通用)
│   ├── scripts/phone_preview.html # M5: 独立实时屏幕页 (img 刷 MJPEG + 光效)
│   ├── mcp.json  plugin.json     # 适配 Codex marketplace + 通用 mcpServers 片段
│   └── tests/                    # pytest: protocol/controller/fallback/setup/apps
└── device/src/test/.../bridge/   【新】JVM 单测（fake DeviceGateway 驱动 HTTP 层）
```

## 5. 里程碑任务

### M1：手机端桥（:device + :app）

**Files:**
- Create: `device/.../bridge/{BridgeProtocol,BridgeController,BridgeServer,BridgeService,BridgePairingProvider}.kt`
- Modify: `device/build.gradle.kts`（如需 coroutines 已在）、`app/AndroidManifest.xml`、设置/能力页、`agent-core/.../DeviceContract.kt`（`DeviceKey` +`VOLUME_UP/VOLUME_DOWN`）、`AndroidDeviceGateway.kt`（音量键注入实现）
- Test: `device/src/test/.../bridge/BridgeServerTest.kt`（fake gateway：auth 401、并发 session 锁、observe/action 编解码）

- [ ] `BridgeProtocol`：Observation→紧凑 JSON（节点省略默认字段：`enabled/visible` 为 true 时省略，`tap` 预计算中心点——对齐上游 `compact_node` 风格与现有 `device_observe` 序列化）
- [ ] `BridgeController`：`/status /session /observe /action /apps /key` 路由；单会话互斥（复用 `AndroidDeviceGateway.mutex` 语义：桥 session 与 App 内 Agent session 共一把锁，占用返回 `session_busy`）
- [ ] `BridgeServer`：`ServerSocket(17325, 50, InetAddress.getByName("127.0.0.1"))` + 协程 accept；HTTP/1.1 keep-alive；`Authorization` 校验；请求体上限 1MB；读超时 15s
- [ ] `BridgePairingProvider`：`call()`→`Binder.getCallingUid()==Process.SHELL_UID` 才 `Bundle.putString("token", …)`；token 持久化 + 重置
- [ ] `BridgeService`：FGS + 常驻通知（可点停止）；设置开关默认 off；开关变化即启停
- [ ] `DeviceKey.VOLUME_UP/VOLUME_DOWN`：`AgentAccessibilityService` 不支持音量 → `AndroidDeviceGateway` 实现优先级：无障碍 `dispatchKeyEvent`（API 30+）→ Shizuku `input keyevent` → `Unsupported`
- [ ] 单测 + `./gradlew :device:testDebugUnitTest :app:assembleDebug` 绿

### M2：桌面传输与工具层（desktop/server）

**Files:**
- Create: `desktop/server/{android_use,adb_client,adb_controller,adb_text,adb_image}.py`
- Test: `desktop/tests/test_protocol.py,test_controller.py`

- [ ] `adb_client.py`：对位 `WDAClient`——HTTP keep-alive 到 `127.0.0.1:18301`，`ensure_forward()`（探测失败自动重跑 `adb forward`），session 复用，只读重试，UncertainError 语义
- [ ] `adb_controller.py`：对位 `PhoneController`——`observe/find/resolve/tap/swipe/type_text/press_button/launch_app/wait/batch/scroll_find/collect_list`；selector 匹配按 §3.4 表；歧义返回 `ambiguous_target+candidates`；selector 失败/遮挡/`no_scroll_progress` 错误一律附最新截图；`observation_id` 上下文校验（前台包名+视口）
- [ ] `android_use.py`：照搬 stdio JSON-RPC + `published_schema` 压缩 + tools 注解；`SCHEMAS`/`DESCRIPTIONS` 按上游改写（`pua_`→`aua_`，`bundle_id`→`package_name`，predicate→regex 字段，button 枚举换 `home/back/recents/volume_up/volume_down`）
- [ ] `adb_text.py`/`adb_image.py`：分段输入协议与截图缩放照搬（Android 端 `InputText` 一次全量，分段逻辑保留客户端侧做进度/续传语义）
- [ ] pytest 用 fake bridge（`http.server` 线程）跑通 observe→tap→batch 链路与错误路径

### M3：setup / ready / apps（desktop + 联调）

**Files:**
- Create: `desktop/server/{adb_setup,adb_apps}.py`、`desktop/server/apps.json`
- Test: `desktop/tests/test_setup.py,test_apps.py`；真机手测

- [ ] `adb_setup.py` 实现 §3.3 七步；job 异步语义（`threading`+内存 job 表，同上游）与 `state_directory()`（`~/.local/share/android-use`，700/600）
- [ ] `adb_apps.py`：`GET /apps` 清单 + `apps.json` 内置目录（微信 `com.tencent.mm`、支付宝 `com.eg.android.AlipayGphone`、美团、抖音、淘宝、设置 `com.android.settings` 等 30 条）；`installed_verified` 语义照搬
- [ ] `aua_doctor`：adb 存在性/版本、设备授权状态、App 版本、无障碍开关、转发端口占用、桥版本——逐项 `ok/missing`+修复指引
- [ ] 真机联调清单：`installDebug` → 开无障碍 → `adb shell am broadcast BRIDGE_START` → `adb forward` → `curl /status` → `aua_ready` → `aua_observe` → `aua_tap`

### M4：技能与注册（desktop/skills + scripts）

- [ ] `skills/android-use/SKILL.md`：照搬上游骨架逐段改写——READY 优先、乐观执行默认 `observe=none`、截图兜底、selector 字段表（Android 版）、batch≤20、`uncertain` 不重放、微信输入（Android 上 `\n` 经 SET_TEXT 安全，改写换行指引）、认证接管改"密码/指纹/验证码交给用户"（复用 `aua_screen pause` 语义或简化版）
- [ ] `skills/android-use-setup/SKILL.md`：对应 §3.3，用户操作=开 USB 调试/装 App/开无障碍，同样用"已完成继续"提问流程
- [ ] `references/`：`apps.md`（国产 App 目录）、`screen.md`、`troubleshooting.md`（adb 授权弹窗、accessibility 被杀、厂商后台限制）、`authentication.md`
- [ ] `install.sh`：探测 `codex`/`claude`/`devin` CLI，写入各自 mcp 配置 + 安装技能目录；`mcp.json` 同上游结构
- [ ] `plugin.json`：`android-use` 命名、icon 复用项目 logo 改写

### M5：实时屏幕（桥 MJPEG + 预览页）

- [ ] 桥 `GET /screen`：`multipart/x-mixed-replace`，帧源用 `AccessibilityWindowCapture` 周期截图（≥350ms 节流，JPEG q70，宽≤480——对齐现有 `VirtualDisplayPreviewFrame` 参数）；仅已认证连接可用；无客户端即停采
- [ ] `adb_screen.py`：`ScreenHub` 对位移植（帧缓存/订阅/`after_seq` 增量）
- [ ] `phone_preview.html`：单文件页（`webbrowser.open` 或 Codex `ui://` resource 二期），MJPEG `<img>` + Android 外壳 + 点击光效（移植 `ui/src/app.ts` 的 cursor 事件渲染；事件由桥在 `/action` 时广播）
- [ ] `aua_screen(open|pause|resume)`：pause 时桥停帧+清缓存——对应认证接管

### M6：收尾（可选/后续）

- [ ] Root 虚拟屏经桥暴露（`session` 带 `mode` 参数）——电脑干活不占主屏
- [ ] 桥动作可选接入 DecisionGate（手机弹审批）——默认关，设置可开
- [ ] `aua_metrics` 对齐上游统计语义；CHANGELOG/README/ARCHITECTURE 更新
- [ ] Windows/Linux adb 路径与 `adb` 不在 PATH 的 doctor 分支

## 6. 验证策略

| 层 | 验证 |
|---|---|
| 桥 | JVM 单测（fake gateway 全覆盖路由/鉴权/锁/编解码）+ `assembleDebug` |
| 桌面 | `python3 -m pytest desktop/tests`（fake bridge HTTP fixture，不依赖真机） |
| 端到端 | 真机：`adb forward` 后 `curl -H "Authorization: Bearer $T" 127.0.0.1:18301/status`；`aua_ready`→observe→在设置 App 做一次 tap→swipe→type_text 中文→press home；`pua_collect_list` 语义对照测试 |
| 回归 | `./gradlew :device:testDebugUnitTest :agent-core:testDebugUnitTest`；既有失败基线先记录 |
| 安全 | 无 token→401；非 shell UID 调 provider→拒绝；桥关闭→端口不可达；LAN IP 直连不通（只 bind loopback） |

## 7. 风险与决策点

| 风险 | 缓解 |
|---|---|
| 无障碍被厂商系统杀掉 | `aua_doctor`/`ready` 明确报该层；技能指引重开 |
| 截图限频（≥333ms）影响流式与动作后观察 | 复用现有 `WINDOW_SCREENSHOT_RETRY_DELAY_MS`；动作后观察以树为主、截图为辅（与上游一致） |
| App 进程被回收导致桥中断 | FGS + 常驻通知；`adb_setup start` 可远程拉起；session 失效返回 `session_expired` 由客户端重建（上游同语义） |
| `DeviceGateway` 会话被 App 内 Agent 同时占用 | 共享 mutex + `session_busy`；技能指引不同时跑两边任务 |
| selector 语义差异（iOS label/name vs Android text/desc/viewId） | §3.4 映射表 + `find` 先验；不足走截图坐标 |
| 音量键无无障碍路径 | §3.4 分级实现，最差 `Unsupported` 如实返回 |
| 桌面宿主差异（Codex plugin.json vs Claude mcp vs Devin） | `install.sh` 三分支都写标准 `mcpServers` 条目；widget 的 `ui://` 资源做成可选增强，核心链路不依赖宿主 UI |
