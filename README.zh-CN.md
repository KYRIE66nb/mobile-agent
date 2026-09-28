# Mobile Agent

[English](README.md) | [简体中文](README.zh-CN.md)

**让 AI 真正"握住"你的手机** —— 一个运行在本机、能看屏幕、能点屏幕的开源 Android 智能体。

[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/KYRIE66nb/mobile-agent)
[![Language](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)](https://github.com/KYRIE66nb/mobile-agent)
[![Release](https://img.shields.io/github/v/release/KYRIE66nb/mobile-agent)](https://github.com/KYRIE66nb/mobile-agent/releases/latest)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

> 项目处于早期开发阶段，接口与交互仍可能调整。请先在测试设备上使用，不要让它执行支付或账号安全操作。

## 为什么它和别的 Agent 不一样

桌面端 Agent 的天花板是浏览器；这个项目的天花板是**你手机上装的每一个 App**。

它不靠 API、不靠网页版——AI 像人一样看屏幕、点屏幕：微信、美团、设置、相册，只要人能在界面上操作，Agent 就能操作。

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/agent-loop.dark.png">
  <img src="docs/assets/agent-loop.light.png" alt="Mobile Agent 执行回路" width="100%">
</picture>

[交互式架构图](docs/assets/agent-loop.html)（下载后用浏览器打开，支持缩放 / 路径追踪 / 暗色模式）

## 核心能力

### 双路径界面感知

| 路径 | 原理 | 适用 |
|---|---|---|
| **无障碍节点树** | 读取界面语义节点（文本、坐标、可点性），按语义点击 | 标准控件界面，精确且省 token |
| **视觉坐标** | 截图直喂视觉模型，返回像素坐标注入触控 | 图标、图片墙、Flutter/自绘界面 |

开启"视觉模型"开关后（GLM-4.5V、GPT-4o 等），`device_observe` 的截图会作为图片内容块直接发给模型，模型按截图像素输出 `x/y` 即可完成点击、滑动、输入。

### 操作安全闸（DecisionGate）

每次**有外部副作用**的工具调用（发消息、改设置、删数据……）在执行前先过一道语义裁决：

- `allow`：普通操作直接执行，不打扰你；
- `confirm`：并入现有审批弹窗，附上裁决理由；
- `block`：高风险不可逆操作直接拒绝，原因结构化回传给模型让它换路子。

裁决超时或失败自动降级为"需人工确认"——**宁可多问一次，绝不静默放行**。可在设置中关闭；只读操作（读文件、识别界面）永远不进闸。

### 设备控制双引擎

- **无障碍服务**（免 Root）：读节点树、点击、输入、滑动、监听前台应用切换；
- **Root 虚拟屏**（可选）：独立的 `RootDeviceService` 进程在 VirtualDisplay 上干活，截屏 + 触控注入在**后台虚拟屏**执行，你主屏该干嘛干嘛；悬浮窗可实时围观 Agent 操作。

**执行位置偏好**（设置 → 通用 → 设备操作位置）：

| 档位 | 行为 |
|---|---|
| **自动**（默认） | Agent 按任务性质选主屏/后台；虚拟屏不可用时**自动降级主屏**继续执行，降级在审批文案和工具结果中如实披露 |
| **仅主屏** | 固定前台操作，无 Root 设备省掉注定失败的虚拟屏尝试 |
| **仅后台** | 只用虚拟屏，失败即失败，**绝不静默触碰主屏** |

**Shizuku 降级通道**：非 Root 设备安装并授权 Shizuku 后，`system_shell` 工具可以 shell(uid 2000) 身份执行白名单管理命令（am/pm/dumpsys/settings/input/content 等，拒绝管道与拼接），用于查应用信息、清缓存、发按键事件——无需抢主屏。

### 系统清理维护

对 Agent 说"清理一下手机""释放点内存"即可触发，三条路径按权限自动选择：

- **存储占用查询** `system_storage_stats`：单应用明细或占用 Top N 排行；查别的应用需"用量访问权限"，缺失时自动打开授权页引导一次授权；
- **应用缓存清理** `system_clear_cache`：不传参清本应用缓存；传包名 + Root → `RootDeviceService` 直接清空该应用 `cache/code_cache` 目录并返回释放字节数（**不碰登录状态和用户数据**）；无 Root → 自动降级打开应用详情页走界面操作；
- **后台内存释放** `system_free_memory`：`killBackgroundProcesses` 结束后台进程，支持单应用或一键全量，返回前后可用内存差。

清理类操作属于 `EXTERNAL_WRITE`/`DESTRUCTIVE`，会过安全闸裁决并经审批弹窗确认。

### 广告守卫（Ad Guard）

弹窗和摇一摇广告只闪现几秒，模型来不及反应——所以拦截交给无障碍事件流上的**确定性规则引擎**（毫秒级，不走模型），模型退居二线负责按需配规则：

- **跳过开屏广告**：自动点击"跳过"类节点；
- **关闭广告弹窗**：界面含"广告"标记时自动点"× / 关闭"；
- **拦截摇一摇跳转**：`auto_back` 规则——前台从指定 App 被甩到浏览器/商城等落地页时自动按返回，把跳转撤销；
- **Agent 可编程**：对它说"XX 老弹红包广告""XX 一摇就跳淘宝"，它会用 `adguard_add_rule` 现场写一条规则，下次直接秒拦。

每规则独立冷却 + 全局熔断（防误配规则循环误点），拦截成功弹 Toast 提示，`adguard_status` 可回放最近拦截记录。需要无障碍服务在线；设置页一键开关，默认关闭。

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/ad-guard.dark.png">
  <img src="docs/assets/ad-guard.light.png" alt="广告守卫旁路回路" width="100%">
</picture>

[交互式守卫回路图](docs/assets/ad-guard.html)（下载后用浏览器打开，支持缩放 / 路径追踪 / 暗色模式）

### 操作模板（Recipes）

高频流程不该每次让模型重新探索界面——操作模板把验证过的流程固化为**确定性步骤序列**，执行全程不进模型，又快又稳：

- **内置模板**：`wechat_send_message`（打开微信 → 搜索联系人 → 输入 → 发送）开箱即用；
- **Agent 可固化**：它用通用界面操作走通一遍流程后，可以 `recipe_save` 把步骤存成模板——下次同任务直接 `recipe_run` 秒级复现，参数化（`{contact}`、`{message}` 占位符）；
- **优雅降级**：某步超时或节点未命中时，错误携带**失败步骤序号**返回，模型从断点改用 `device_observe`/`device_action` 继续，不会卡死重来。

模板在**主屏前台**执行（要操作目标 App 的界面），需要无障碍服务在线。

### 模型与工具

- **OpenAI 兼容网关**：自建 OkHttp + SSE 流式解析，智谱 GLM / OpenAI / DeepSeek / 任意兼容端点即插即用，支持多配置切换与 `reasoning_effort` 透传；
- **50+ 内置工具**：设备操作（observe/action/gesture/batch/wait_for）、文件读写、文档读写编辑（PDF/Word/Excel 提取，docx/xlsx 生成与手术式编辑）、网页抓取、通知管理、剪贴板、应用启动、语音转写（OpenAI / 讯飞兼容）、系统维护（存储占用/缓存清理/内存释放/**受限 shell**：Root 或 Shizuku 通道）、广告守卫规则管理、操作模板执行与保存、联系人/日历查询（运行时权限授权）；
- **多轮 Agent 循环**：上下文压缩、步数上限、随时取消、工具调用全程落库可回放；同一轮内全是只读工具时**并行执行**（写操作保持顺序），界面识别结果按非默认字段紧凑序列化并预计算节点中心坐标，显著降低每轮 token 消耗。

## 架构

纯 Kotlin 六模块，零框架依赖（无 Hilt / Koin / MVVM 框架），核心运行时不碰 Android SDK：

| 模块 | 职责 |
|---|---|
| `:app` | Compose UI、手动装配（`PrototypeApplication`）、悬浮窗、更新器 |
| `:agent-core` | `ChatRuntime` 循环、工具契约、`DecisionGate` 安全闸 |
| `:model` | OpenAI 兼容网关（OkHttp + SSE）、探活 |
| `:device` | 无障碍服务、广告守卫引擎、Root/AIDL 服务、VirtualDisplay、触控注入 |
| `:tools` | 设备/文件/网络/通知/剪贴板/系统维护/广告守卫/操作模板等工具实现 |
| `:data` | Room 持久化、DataStore 设置、Keystore 密钥保护 |

技术栈：Kotlin 2.0 + Jetpack Compose（Material3）+ Room + DataStore + Coil + libsu，Gradle Kotlin DSL 版本目录，minSdk 24 / targetSdk 36。

## 快速开始

### 直接安装

从 [Releases](https://github.com/KYRIE66nb/mobile-agent/releases/latest) 下载 APK，或在 App 内 **设置 → 关于 → 检查更新** 直接升级。

### 自行构建

```bash
git clone https://github.com/KYRIE66nb/mobile-agent.git
cd mobile-agent
./gradlew :app:assembleDebug    # 产物在 app/build/outputs/apk/debug/
```

### 配置模型（以智谱为例）

设置 → 模型 → 新建配置：

- 地址：`https://open.bigmodel.cn/api/paas/v4`
- 模型：`glm-5.3-flash`（视觉能力用 `glm-4.5v` 并开启"视觉模型"开关）
- Key：智谱开放平台 API Key

## 路线图

- [x] 视觉模型截图操作路径（观察 → 坐标动作闭环）
- [x] 语义级操作安全闸（allow / confirm / block 三态裁决）
- [x] App 内一键升级（GitHub Releases 检查 → 下载 → 调起安装器）
- [x] 前台 / 后台虚拟屏执行位置偏好（自动降级）
- [x] 系统清理维护（存储查询 / 缓存清理 / 内存释放，Root 直清 + 无 Root 降级）
- [x] 广告守卫（无障碍规则引擎：开屏跳过 / 弹窗关闭 / 摇一摇拦截，Agent 可编程）
- [x] 高频 App 操作模板（确定性步骤固化，参数化执行，失败断点由模型兜底）
- [ ] 更多内置模板（支付宝、美团等高频场景的版本适配）
- [ ] 触发器系统：通知 / 位置 / 定时任务
- [x] 非 Root 降级：Shizuku shell 通道（白名单命令，用户授权后可用）
- [x] PDF / Word / Excel 文档读写（本地解析生成，无需联网）
- [x] 已有文档手术式编辑（docx 段落级 / xlsx 单元格级，原文件保留图片样式）
- [ ] 可插拔的独立决策模型后端（Jev-like）

## 权限和数据边界

应用可能申请麦克风、通知、悬浮窗、开机启动、应用列表以及无障碍权限。Root、无障碍、截图和外部模型调用都是高权限能力，**只有在你主动开启或确认后才使用**。

模型请求、语音转写和网页访问会把相关内容发送给你配置的第三方服务。安装前请阅读 [隐私说明](PRIVACY.md)。

## 文档

- [产品定义](docs/PRODUCT.md) · [设计规范](docs/DESIGN.md) · [技术架构](docs/ARCHITECTURE.md) · [实施计划](docs/PLAN.md)
- [第三方声明](THIRD_PARTY_NOTICES.md) · [安全策略](SECURITY.md)

## License

本项目源码以 [Apache License 2.0](LICENSE) 开源，第三方组件遵循各自许可证。
