# 火影替身计时器 — 接入计划（2026-10-02）

按 `mobile-agent-naruto-timer-prompt.md` 实施。原则：纯本地识别、不碰聊天/模型/Root 链路、参考仓库只取原理不取代码（LICENSE 限制商用与二次发布，已核查，仅参考"豆数变化→替身候选→计时"思路与"主动扣豆歧义"提示）。

## 接入点

| 层 | 新增 | 复用 |
|---|---|---|
| agent-core（纯 Kotlin） | `substitution/`：ROI 模型、DotSample 统计、HSV 分类器、稳定计数器、单侧判定、冷却钟、运行状态机 | 无 Android 依赖，全部可 JVM 单测 |
| device | `capture/projection/`：MediaProjection+ImageReader 采集会话、行首取样器 | 不用 Root FrameSource（那是虚拟屏预览） |
| data | `SubstitutionTimerRepository`（独立 DataStore 文件） | DecisionSettingsRepository 同款模式 |
| app | `substitution/`：前台服务(mediaProjection 类型)、自动启动协调器、拖拽悬浮窗、设置页+校准页 | WindowManager ComposeView 悬浮窗写法、触发器通知写法、settings tab 分发 |

## 关键设计

- **自动启动语义**：`enabled && autoStartOnAppOpen` → MainActivity.onStart 且由用户打开时，协调器幂等进入流程：有会话复用 → 无则 MediaProjection 系统授权 → 成功进待机/校准。拒绝→NEEDS_PERMISSION 不循环弹；暂停≠关闭。
- **采集**：Android 14+ createScreenCaptureIntent 每会话一次，支持单应用共享；FGS 先 startForeground 再 getMediaProjection；先注册 stop 回调再建 VirtualDisplay；ImageReader 只留最新帧。
- **检测**：每侧豆槽一个归一化矩形 → N 个等分采样点 → 网格采样 ARGB → 均值/方差/HSV → LIT/EMPTY/UNKNOWN → 稳定窗确认计数 → 仅 n→n−1 产生疑似替身候选 → 单调钟倒计时 `endAt=eventAt+cooldownMs`。首次稳定只建基线；无效帧超时重建基线。
- **悬浮窗**：独立小窗口（不复用 Agent 悬浮服务、不索取麦克风权限），FLAG_NOT_FOCUSABLE，可拖存位，仅在前台包名==所选游戏且状态 MONITORING 时显示。
- **前台包名**：UsageStats 轮询（PACKAGE_USAGE_STATS 已声明）；缺 usage access 时在状态里明示并引导。
- **参数**：cooldownMs 默认 15000（决斗场经验值，标注实验配置可调）；确认窗/阈值/频率可配。

## 许可处理

K0NGCH4NG/Naruto_Mobile_Game_Tool 为限制性 LICENSE——不复制代码/坐标表/素材；iam0range/TOOLS_HUOYING 同方式仅看原理。本实现独立编写。

## 未验证声明

无火影真实对局样本与真机对局实测——合成样本过逻辑测试，校准页+实机联调留待用户。坐标默认值是校准起点，不是已验证参数。
