# Android Use 桌面控制更新

**Goal:** 对照 iPhone-use 0.3.15 改善电脑操作安卓手机的可靠性、人工接管和截图体验。

**Architecture:** 保留 Python 标准库 MCP → adb loopback → Android 无障碍桥。浏览器预览由桌面进程的 loopback HTTP 服务提供，与 MCP 共用同一控制器、会话和操作锁。系统截图复用 `PressKey`，不另建控制通道。

**Tech Stack:** Python 3 标准库、Kotlin、现有 Gradle/Android SDK，无新依赖。

## 已核对的证据

- 本项目基线 `6968808`；桌面测试 `python3 -m unittest discover -s desktop/tests -v`：34 项通过。
- 上游基线 [`8000688` / 0.3.15](https://github.com/zhongerxin/iPhone-use/commit/80006885df81531ce7a874b3b0df65980e25e59b)，2026-10-16：系统截图进入手机相册。
- 上游 [`a1faa65`](https://github.com/zhongerxin/iPhone-use/commit/a1faa65c96ce66886609f0df67289988e2fba7e6)，2026-10-13：续传绑定原输入目标；`f496687`：校验安装清单结构；`5cc2fa8`：接管保留预览画面。
- 本项目 `desktop/server/adb_controller.py` 已有语义定位、分片输入、批量、截图兜底和不盲重放语义，适合增量适配。
- 当前缺口：续传不检查原应用/字段内容；Python code point 与 Android UTF-16 限额不一致；`aua_ready` 吞掉观察异常；预览按钮另建会话；缓存未校验每行结构。
- Android 系统截图使用 [GLOBAL_ACTION_TAKE_SCREENSHOT](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#GLOBAL_ACTION_TAKE_SCREENSHOT)。桌面桥本身要求 Android 11+；截图请求被接受不等于相册写入已经核验。

## 行为与实施步骤

- [x] 输入安全：原 app、视口、节点身份和已写内容变化时，续传拒绝写入/提交；每片最多 500 UTF-16 单元，最终字段最多 2000 单元，超限在首片前拒绝。修改 `adb_controller.py` / `adb_text.py`，新增输入回归测试。
- [x] 应用发现：错误格式/污染缓存不能产生已安装证明；有效空清单可接受；目录仍可查询并明确 warnings。修改 `adb_apps.py`，新增安装清单测试。
- [x] 就绪与生命周期：观察失败、缺截图、无效视口、空树/无前台不报告 ready；配置端口实际生效；重载关闭旧连接/流。修改 `android_use.py` / `adb_setup.py`，新增运行时回归测试。
- [x] 预览接管：同源 loopback 页面，使用共享 MJPEG 帧和控制器；人工点按/拖动/导航与 MCP 共用锁；暂停保留最后一帧并显示暂停状态，拒绝采帧/控制，恢复仅在桥确认后进行。修改 `adb_screen.py` / `phone_preview.html`，新增 `adb_preview.py` 和协议测试。
- [x] 系统截图：`aua_screenshot {}` → `/action` → `PressKey(SCREENSHOT)` → Android 系统动作；暂停/锁屏/虚拟屏拒绝，接受后返回 `verified=false`。扩展枚举、桥解析和设备执行器，增加桌面协议测试，编译验证 Android 分支。
- [x] 文档及验证：更新桌面使用说明与变更记录；运行所有桌面测试、受影响 Gradle 单测及 APK 构建；浏览器验证预览主路径和暂停行为。

## 约束与验收

继续使用 loopback + token；动作不自动重放；不引入外部遥测；不修改 App 内聊天/游戏流程。用户已在后续消息明确授权真机测试、提交推送、创建 PR 和合并；排除无关 `.omx` 生成文件。

WHEN 续传期间切换应用/输入框或编辑已写内容，THEN 返回结构化错误且不再写入或提交。WHEN 任一必需的读取证明缺失，THEN `ready=false`。WHEN 用户暂停预览，THEN 保留最后一帧并禁止新图像和控制。WHEN 系统截图请求成功，THEN 返回已请求及相册目的地，仍要求查看保存结果。

初期本地测试时没有设备；后续已连接 Android 16 真机，实际结果见下。其他型号和 OEM 行为不能以本机测试代替。

## 验证结果（2026-10-17）

- 桌面：`PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest discover -s desktop/tests`，99 项通过。
- Android/JVM：`:device:testDebugUnitTest` 13 项、`:agent-core:test` 184 项通过；`:device:compileDebugAndroidTestKotlin` 和 `:app:assembleDebug` 成功。
- 浏览器：同源页面、共享会话导航、像素映射点击、一次拖动、暂停禁用且保留旧画面通过模拟桥验收。
- 静态：Python AST、预览 JavaScript `node --check`、`git diff --check` 通过。移除了测试生成的临时截图目录，恢复测试运行产生的已跟踪 pyc。
- 真机：Android 16/API 36，1280×2772；坐标聚焦、中文 Emoji、720 字符两段精确回读、超限无写入、前台/应用切换、滚动/拖动、MJPEG、暂停等待在途操作/拒绝控制/恢复新帧、浏览器导航和系统截图相册落盘通过。真机阶段修复了前台误报、输入布局身份/回读滞后、滚动锚点、空闲连接和服务激活。
- 限制：lint 30 个错误对应基线已有调用；Gradle UTP 镜像依赖缺失，测试 APK 被手机系统拒绝安装，instrumentation 未运行。没有绕过安装限制。
