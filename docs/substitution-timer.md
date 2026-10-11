# 火影替身计时器（Substitution Timer）

火影忍者手游「替身术」冷却的本地观察/推断/计时悬浮工具。**纯本地识别**：不调用大模型、Laya/Jev 或任何远端服务；截图不落盘不上传；不做自动战斗/连点/按键注入/读内存/Hook。只观察像素并显示推测倒计时。

## 使用流程

1. 设置 → 「替身计时」Tab：确认"启用替身计时"+"打开 App 时自动进入启动流程"（默认开），选择目标游戏（从已安装应用列表选火影渠道服包名）。
2. 打开 App → 自动弹系统录屏授权（MediaProjection，每次会话授权一次，可选单应用共享选"火影"）。
3. 授权后进入待机；进入游戏对局 → 自动开始识别，游戏上方出现倒计时胶囊。
4. **首次必须校准**：在游戏画面停留片刻后回到设置页 → "豆槽校准"用最后一帧游戏画面 + 绿/橙滑杆框出双方豆槽 → 保存。未校准前不会显示"识别中"。建议**在战斗中**再框选"战斗 HUD 锚点"（暂停键/血条框等常驻元素），用于区分战斗/非战斗画面；在菜单页标定会导致判反。
5. 通知栏提供 暂停本次 / 停止；离开游戏自动隐藏计时。

## 检测原理（独立实现，v2）

- 采集：MediaProjection + ImageReader（RGB_8888），mediaProjection 类型前台服务，只留最新帧、慢设备丢帧。
- 每帧走五层管线：
  1. **逐格采样**：左右豆槽矩形（归一化坐标，含内容区黑边映射）等分 N 格，只取格中心 60% 区域做 7×7 逐像素分类：命中点亮青蓝/金色盒占比占优 → LIT、命中熄灭暗青盒占优 → EMPTY，否则 UNKNOWN（逐像素表决不被背景稀释；max(r,g,b)≤blackFloor 的近黑像素不投票，防黑屏转场"假全灭"）。
  2. **逐格记忆/规范豆形**：单格 UNKNOWN ≤cellHoldMs 沿用记忆值，超期整行不可读；豆只许连续点亮（洞形/缺口行判不可读），行可读才产出计数。
  3. **计数确认**：新计数须被可读帧连续见到 confirmFrames 次才确认；不可读帧与稀疏帧不推进也不重置证据。
  4. **判定表 D0–D5**（掉豆按序短路）：证据空白 >maxAnchorGapMs → AmbiguousDrop 不计时；双侧近同步归零（≤collapseWindowMs）→ 判转场整排消失，回滚对方归零计时；涨豆 ≤gainFlickerMs 内回落 → 奥义清豆/残影不计时；一步多豆或 cascadeWindowMs 内连扣 → MultiDrop 并回滚链内计时；原计时剩余 >cooldownGraceMs → **冷却先验**：冷却内掉豆不可能是替身（CooldownDrop，只记备选截止）；其余 −1 → SubstitutionStarted 起算。
  5. **已接受计时 + 短窗证伪回滚**：计时即确认即起算、默认有效；仅"回弹（≤reboundRetractMs）/连扣/双侧同塌"三种短窗证据可回滚并恢复前任计时。
- **冷却先验**：冷却剩余 >1s 内的掉豆不是替身——原计时保留，该掉豆标 conflict 并记录 altEndAtMs 备选截止。
- 起算锚点=新计数首次目击帧；证据间隔 >uncertainGapMs 时取中点并标 uncertain（悬浮窗显"≈"），>maxAnchorGapMs 报歧义不计时（SystemClock 单调钟）。
- 场景门控：只信豆色之外的 HUD 锚点证据——校准页框选常驻元素生成颜色盒，命中则战斗中；未标定（UNVERIFIED）不否决任何帧，由 collapse/match_end 兜底。
- 悬浮窗"重置"=清计时+重建基线+解除暂停；双侧不可读持续 >hudLostResetMs 重建基线但**保留计时**（hud_lost），持续非战斗超时才清计时（match_end）；离开/回到游戏去抖后重建基线；设置热更新保留在途计时。

### 关键参数（DetectionTuning / TimerConfig）

| 参数 | 默认 | 含义 |
|------|------|------|
| confirmFrames | 2 | 新计数需连续被可读帧确认的次数 |
| cellHoldMs | 400 | 单格 UNKNOWN 沿用记忆值的最长时长 |
| reboundRetractMs | 500 | 掉豆→弹回原值判假掉落并回滚的窗口 |
| gainFlickerMs | 350 | 涨豆→掉豆判奥义清豆/涨豆残影的窗口 |
| cascadeWindowMs | 400 | 窗口内连续两次 −1 判连扣（奥义逐帧扣豆） |
| uncertainGapMs | 700 | 证据空白超此值：锚点取中点 + uncertain 标记 |
| maxAnchorGapMs | 2500 | 证据空白超此值：触发时刻不可知，报歧义不计时 |
| collapseWindowMs | 300 | 双侧"掉到 0"首见时差在此值内判转场整排消失 |
| hudLostResetMs | 8000 | 双侧不可读/非战斗持续超此值：hud_lost 重建基线（留计时）/ match_end（清计时） |
| cooldownGraceMs | 1000 | 冷却剩余超此值的掉豆不可能是替身（冷却先验） |
| blackFloor | 10 | max(r,g,b) 不高于此值的像素不投票（黑屏转场过滤） |
| frameIntervalMs | 100 | 帧采样间隔（设置页可调 80–500） |
| cooldownMs | 15000 | 替身冷却（设置页可调；秘卷减 CD 请改准） |

## 已知边界

- **豆减≠替身**：主动扣豆技能/秘卷与替身同为 −1 → 计时一律标"疑似"（suspected），冷却内掉豆仅记备选截止；可用悬浮窗"重置"纠错。
- 证据空白大时起算锚点为中点估计 → 悬浮窗显"≈"（uncertain）；空白 >2.5s 报歧义不计时。
- 替身后 <400ms 内再放单豆技能会被连扣规则回滚；涨豆后 <350ms 内受击替身会被奥义清豆规则忽略——两个窗口是 FP/FN 权衡点，按真机数据回调。
- 冷却默认 15s 为决斗场经验值（秘卷减 CD 需在设置页改准）；未标定 HUD 锚点时场景判定降级为"不否决"，建议战斗中完成锚点框选。
- 仅验证标准决斗场布局思路；特殊忍者/多豆形态/非横屏未适配。
- 需要"使用情况访问"权限检测游戏前台（PACKAGE_USAGE_STATS 已声明，设置-能力页可引导）；悬浮窗需 overlay 权限；通知权限拒绝不影响识别，仅隐藏常驻通知。

## 参考与许可

- 原理参考 K0NGCH4NG/Naruto_Mobile_Game_Tool 与 iam0range/TOOLS_HUOYING 的"豆数变化→替身候选"思路，及 frayy3344 实现的"冷却剩余 ≤1s 才受理新掉豆"冷却先验与"掉豆序列第一帧"锚点口径；前者 LICENSE 限制商用与二次发布——**未复制其代码、坐标表、图片或任何素材**，本实现独立编写。

## 测试记录（合成样本 + 真机录像）

`./gradlew :agent-core:test` → SubstitutionScenarioTest 50 + SubstitutionCoreTest 15 + TimerStateMachineTest 4；`:app:testDebugUnitTest` → DotRowCountingTest 6。覆盖：可读帧/稀疏帧确认、首次目击锚点与 uncertain 中点标记、冷却先验（CooldownDrop + altEndAtMs）、回弹/连扣/双侧同塌回滚与前任计时恢复、涨豆残影、格记忆超期整行不可读、hud_lost/match_end 基线重建、暂停撤销、热更新 carried 结转、回放诊断、格级颜色盒分类（含近黑）、HUD 门控三值、deriveAnchorBox、布局编解码、状态机全路径、逐格读取与 mask。

真机录像证据（docs/assets/substitution-timer-demo.mp4，ffmpeg 逐帧测量）：我方两次替身中"豆排 金×4→青×3"与替身键冷却数字"15"**同帧出现**（t≈2.91s 与 t≈22.41s，误差 ≤1 帧 ≈41ms）——"豆数掉落即替身 T0"在数据层面成立，v2 起算锚点取掉落首次目击帧正是此口径；旧引擎两次显示延迟 1.2–1.4s 为本次改造动机。

**仍缺整场对局验收**（按改造方案任务 11 协议执行）；坐标默认值与分类阈值以真机复核为准。
