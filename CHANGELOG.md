# Changelog

本项目所有值得记录的变更。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循语义化版本。安装包见 [Releases](https://github.com/KYRIE66nb/mobile-agent/releases)。

## [0.1.17] - 2026-10-02

- **火影替身计时（新功能）**：决斗场替身术冷却悬浮计时——MediaProjection 免 Root 抓帧，HSV 逐像素豆数判定；敌我独立计时、暂停/重置、进 App 自动进入监视流程；校准页在真实捕获帧上框选双方豆槽
- **录屏共存兜底**：系统录屏/投屏抢走 VirtualDisplay 投影时自动重建采集；仍被抢则切换无障碍截屏通道（Android 14+、需无障碍服务）
- **替身检测引擎重写**：移植开源规范豆型状态机——豆只许连续点亮，含未知态或"暗-亮-暗"洞形的帧整帧丢弃，噪声期后检测不断线
- **可选专用决策后端（Laya/Jev）**：设置 → 专用决策后端接入独立小模型服务为安全闸裁决；SHADOW 只记录 / ENFORCE 才生效；出站仅最小决策上下文且需显式同意
- **思考强度预设 + 压缩阈值可调**：GLM 官方档位一键填充，自动压缩阈值 30%–95% 可调
- **稳定性**：模型空响应自愈重试、安全闸裁决硬化、授权结果落后台不再崩进程、暂停卡死修复
- **README 改版**：中文为主版本，真机全量截图 + 决斗场实录视频内嵌播放

## [0.1.16] - 2026-09-29

- **上下文溢出自愈**：服务端判定上下文超限且本轮未产出内容时，自动压缩后按原轮重试一次
- **保底截断**：超长工具结果/历史回显截成摘录并附 `history_read` 引用，不再杀死 run
- **错误分类链路**：model 层读取 HTTP 错误响应体的溢出标识传给运行时

## [0.1.15] - 2026-09-29

- **备用模型可指定**：故障切换支持下拉指定备用配置
- **对话导出/导入**：全部会话一键备份为 JSON；导入幂等合并，换机不丢历史
- **OOXML 解析硬化**：docx/xlsx 定位从正则升级为容错 XML 片段扫描器

## [0.1.14] - 2026-09-29

- 广告守卫 `auto_back` 支持同应用内广告页拦截（包内 WebView/落地页按特征类名撤销）
- 触发器会话移出主聊天列表与分享接收列表
- 安全收紧：`notifications_list` 输出加低信任标记

## [0.1.13] - 2026-09-28

- **模型调用韧性**：瞬时故障指数退避重试；主配置失败自动切换备用配置；已产出内容绝不重发
- 任务完成 TTS 播报（离线、可选）
- 定时任务每日计数持久化
- 产物保留策略：可清理 30 天前的登记产物

## [0.1.12] - 2026-09-28

- **通知快捷回复（RemoteInput）**：`notifications_reply` 直接经通知把文字送回来源应用
- **能力/授权总览页**：所有系统授权与能力组状态一屏总览，一键跳授权页
- `agent-core` 测试套件挂起修复

## [0.1.11] - 2026-09-28

- **定时任务（Triggers）上线**：定时/通知匹配/周期巡检三种触发，AlarmManager 排期
- **硬安全边界**：每任务独立 `tool_scope` 白名单；冷却 + 每日上限 + 三连失败熔断
- 隔离与回放：每任务独立会话，设置页可启停/删除

## [0.1.10] - 2026-09-28

- **内置文档处理 Skill**：Agent 自带"inspect → edit → share"文档工作流

## [0.1.9] - 2026-09-28

- **docx/xlsx 手术式编辑**：段落级/单元格级修改，原文不动产出新文件

## [0.1.8] - 2026-09-28

- **文档读写**：file_read 提取 PDF/docx/xlsx；document_write 纯本地生成
- **执行提速**：同轮只读工具并行执行，token 用量显著下降
- **Shizuku 降级**：非 Root 设备白名单 shell 命令
- **数据集成**：contacts_search / calendar_events 查询工具

## [0.1.7] 及更早 - 2026-09-26 ~ 27

- 初始版本：无障碍节点树 + 视觉坐标双路径感知、50+ 工具、操作安全闸、Root 虚拟屏后台执行、多模型配置、语音转写、应用内更新

[0.1.17]: https://github.com/KYRIE66nb/mobile-agent/releases/tag/v0.1.17
[0.1.16]: https://github.com/KYRIE66nb/mobile-agent/releases/tag/v0.1.16
[0.1.15]: https://github.com/KYRIE66nb/mobile-agent/releases/tag/v0.1.15
[0.1.14]: https://github.com/KYRIE66nb/mobile-agent/releases/tag/v0.1.14
[0.1.13]: https://github.com/KYRIE66nb/mobile-agent/releases/tag/v0.1.13
[0.1.12]: https://github.com/KYRIE66nb/mobile-agent/releases/tag/v0.1.12
[0.1.11]: https://github.com/KYRIE66nb/mobile-agent/releases/tag/v0.1.11
[0.1.10]: https://github.com/KYRIE66nb/mobile-agent/releases/tag/v0.1.10
[0.1.9]: https://github.com/KYRIE66nb/mobile-agent/releases/tag/v0.1.9
[0.1.8]: https://github.com/KYRIE66nb/mobile-agent/releases/tag/v0.1.8
