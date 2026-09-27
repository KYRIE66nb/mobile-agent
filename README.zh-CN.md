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

### 模型与工具

- **OpenAI 兼容网关**：自建 OkHttp + SSE 流式解析，智谱 GLM / OpenAI / DeepSeek / 任意兼容端点即插即用，支持多配置切换与 `reasoning_effort` 透传；
- **20+ 内置工具**：设备操作（observe/action/gesture/batch/wait_for）、文件读写、网页抓取、通知管理、剪贴板、应用启动、语音转写（OpenAI / 讯飞兼容）；
- **多轮 Agent 循环**：上下文压缩、步数上限、随时取消、工具调用全程落库可回放。

## 架构

纯 Kotlin 六模块，零框架依赖（无 Hilt / Koin / MVVM 框架），核心运行时不碰 Android SDK：

| 模块 | 职责 |
|---|---|
| `:app` | Compose UI、手动装配（`PrototypeApplication`）、悬浮窗、更新器 |
| `:agent-core` | `ChatRuntime` 循环、工具契约、`DecisionGate` 安全闸 |
| `:model` | OpenAI 兼容网关（OkHttp + SSE）、探活 |
| `:device` | 无障碍服务、Root/AIDL 服务、VirtualDisplay、触控注入 |
| `:tools` | 设备/文件/网络/通知/剪贴板等工具实现 |
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
- [x] 语义级操作安全闸
- [x] App 内一键升级（GitHub Releases）
- [ ] 高频 App 操作模板（微信发消息等固化为可靠指令）
- [ ] 触发器系统：通知 / 位置 / 定时任务
- [ ] 非 Root 降级：Shizuku 前台操作
- [ ] PDF / Word / Excel 文档读写
- [ ] 可插拔的独立决策模型后端（Jev-like）

## 权限和数据边界

应用可能申请麦克风、通知、悬浮窗、开机启动、应用列表以及无障碍权限。Root、无障碍、截图和外部模型调用都是高权限能力，**只有在你主动开启或确认后才使用**。

模型请求、语音转写和网页访问会把相关内容发送给你配置的第三方服务。安装前请阅读 [隐私说明](PRIVACY.md)。

## 文档

- [产品定义](docs/PRODUCT.md) · [设计规范](docs/DESIGN.md) · [技术架构](docs/ARCHITECTURE.md) · [实施计划](docs/PLAN.md)
- [第三方声明](THIRD_PARTY_NOTICES.md) · [安全策略](SECURITY.md)

## License

本项目源码以 [Apache License 2.0](LICENSE) 开源，第三方组件遵循各自许可证。
