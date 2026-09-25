# Mobile Agent

[English](README.md) | [简体中文](README.zh-CN.md)

Mobile Agent 是一款面向 Android 的开源 AI 聊天与本地工具应用。用户可以在持续对话中调用文件、网络和设备能力，并在执行敏感操作前进行授权确认。

> 当前项目处于早期开发阶段，接口、数据结构和交互仍可能调整。请先在测试设备上使用，不要将它用于支付或账号安全操作。

## 主要能力

- 多轮聊天、上下文压缩和模型用量统计。
- OpenAI 兼容模型服务，可配置地址、模型和 API Key。
- OpenAI 兼容格式及科大讯飞格式的语音转写。
- 文件、图片、网页读取和生成产物管理。
- 可选的 Root/无障碍设备操作、悬浮助手和授权控制。

## 权限和数据边界

Mobile Agent 可能申请麦克风、通知、悬浮窗、开机启动、应用列表以及无障碍等权限。Root、无障碍、截图和外部模型调用都具有较高权限，只有在用户主动开启或确认后才应使用。

模型请求、语音和网页访问可能把用户选择的内容发送到相应第三方服务。安装和使用前请阅读 [隐私说明](PRIVACY.md)。

## 项目文档

- [产品定义](docs/PRODUCT.md)
- [设计规范](docs/DESIGN.md)
- [技术架构](docs/ARCHITECTURE.md)
- [实施计划](docs/PLAN.md)
- [第三方组件声明](THIRD_PARTY_NOTICES.md)

## 开源许可

项目自身代码使用 [Apache License 2.0](LICENSE)。第三方组件继续遵循各自许可证，详见 [第三方组件声明](THIRD_PARTY_NOTICES.md) 与 `licenses/`。
