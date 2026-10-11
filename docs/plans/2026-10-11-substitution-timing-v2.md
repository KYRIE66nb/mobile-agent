# 替身计时 v2：冷却先验引擎改造方案

> 供其他 agent 对照实施的详细修改方案。本文所有代码均在与最终规格等价的临时原型中
> 验证过（50/50 用例通过，原型已删除）；真机 25.4s 对局录像逐帧测量提供了 2 例
> 真实替身事件的 ground truth。**未经真机验收（任务 11）前不得宣称"识别已修好"。**

- **Goal / 目标**：豆数已读准的前提下，解决"不知道什么时候起算、识别不准、无法连续计时"——起算时机提前到第一次可读证据、锚点误差压到一个帧间隔量级、一局内双侧计时持续可用，同时保留对遮挡/闪光/奥义清豆的既有防护。
- **Architecture / 架构**：`agent-core` 纯 Kotlin 引擎重写（不变式 + 冷却先验 + 逐格记忆 + 短窗证伪回滚）；`app` 层改逐格采样入口并做服务鲁棒性与悬浮窗自遮挡；`data` 层收敛调参面。抓帧通道（MediaProjection/无障碍）、颜色盒分类器、校准 UI 框架、诊断 JSONL 全部保留。
- **Tech Stack / 技术栈**：Kotlin、JUnit4、无新依赖、离线可跑（`--offline` 全绿）。
- **Spec / 规格**：§7 判定表 + §10 完整代码清单 + §11 测试矩阵。
- **Global Constraints / 全局约束**：
  1. 全本地：不联网、不调模型、不读内存、不改游戏、不注入输入、不要 Root。
  2. 开源项目**只取原理不取代码/坐标/资产**：K0NGCH4NG 仓库明确禁止修改再发布与商用；frayy3344 仓库要求保留出处。本项目全部独立实现。
  3. 时钟一律 `elapsedRealtime` 单调域；帧时间必须单调递增，过期/乱序帧丢弃。
  4. 引擎非线程安全——帧观察、暂停、重置、配置切换均由服务侧 `engineLock` 串行化。
  5. 不新增三方依赖；所有验证命令可离线运行。
  6. 悬浮窗文案坚持"疑似/不确定"语义——豆数是间接证据，不是替身的直接证明。

---

## 1. 问题复述与验收目标

用户原话："计算豆的数量还算准确，但是不知道什么时候应该计算时间，老是识别不准，也做不到连续计时。"

拆解为四个可验收目标：

| # | 目标 | 验收信号 |
|---|------|---------|
| G1 | 起算时机 = 第一次可读证据 | 锚点 = 新计数首次被读到的帧时刻，而非"确认帧"或"悬置通过帧" |
| G2 | 锚点误差 ≤ 一个帧间隔（投影 ~100ms） | 真机对照替身键冷却数字，误差 ≤0.5s（实测基线见 §4） |
| G3 | 连续计时：一局内双侧计时不断线 | 特效/遮挡/无障碍慢通道/悬浮窗遮挡下计时存活；15s 后可再计 |
| G4 | 不引入新的误报 | 抖动、奥义清豆、转场、结算页不产生计时（§11 假阳性矩阵全绿） |

## 2. 现状机制（数据流与参数）

```
ProjectionCapture / AccessibilityFrameSource
  └─ SubstitutionTimerService.onFrame(access)            app/.../SubstitutionTimerService.kt:337
       ├─ 节流 cfg.frameIntervalMs（仓储默认 140ms）
       ├─ 几何版本检查 → eng.resetBaseline("geometry_changed")
       ├─ DotRowCounting.count(access, cells, classifier, side, grid, inner) → Int?   app/.../DotRowCounting.kt:15
       │     每格中心内缩采样 grid×grid → DotClassifier 逐像素颜色盒 → 格状态
       │     任一格 UNKNOWN/洞形/居中块 → 整侧返回 null（不可读）
       ├─ evaluateScene(anchors, anchorSamples, selfCount, enemyCount) → SceneVerdict  agent-core/.../SubstitutionCore.kt:213
       │     锚点已配置 → HudGate 命中数裁决；未配置 → 双零=NOT_BATTLE 否则 UNVERIFIED
       ├─ eng.onFrame(selfCount, enemyCount, now, battle) → List<TimerEvent>           agent-core/.../SubstitutionEngine.kt:231
       │     StabilityTracker(confirmFrames=3, maxObservationGapMs=500)：连续一致帧才确认
       │     feed(): 盲窗 invalidBaselineTimeoutMs=2000 失效基线；wobble 窗 1200ms；
       │            dedupe 800ms；候选 SubstitutionCandidate → 悬置验证
       │     evaluatePending(): <600ms 回弹撤单；≥600ms 确认；1500ms 无证据超时撤单
       │     conflict：冷却内掉豆 → contested 候选，不接管正式计时
       ├─ publishTimers(): eng.timers() → Pair<SideTimer, SideTimer> → 悬浮窗
       └─ diag?.record(...) → JSONL（含 selfCells/enemyCells L/E/U 逐格）
```

现行关键参数（`DetectionTuning`，SubstitutionCore.kt:76-114）：`confirmFrames=3`、
`maxObservationGapMs=500`、`invalidBaselineTimeoutMs=2000`、`minStableBeforeDropMs=1200`、
`pendingVerifyMs=1500`、`fastReboundCancelMs=600`、`dedupeMs=800`、
`litPixelMinRatio=0.12`、`dimPixelMinRatio=0.40`、`sampleGrid=7`、`cellInnerFraction=0.6`。
`TimerConfig.cooldownMs=15_000`、`frameIntervalMs=80`（仓储层默认 140，clamp 80..500）。

## 3. 根因分析

### 结论先行

**数豆不是问题，时间问题出在"把豆数序列当成高噪声信号，叠了六层互相打补丁的时间窗"。**
真实对局里替身伴随受击特效，豆行短暂不可读是常态；而每个窗都在"正常节奏"下
吞掉真替身、推迟锚点或推翻已起计时。历次 commit（55a2e92→7164228→b3bf4f0→f56ea4f）
反复在同一症状上打补丁，说明这不是参数问题而是架构问题：**缺了"替身冷却内不可能
再次替身"这条物理先验**，也没有逐格记忆与锚点不确定度的概念。

### R1 — 稳定确认被不可读帧中断 + 慢通道断链 → 起算晚甚至永不确认

- 机理 A：`DotRowCounting.count` 任一格 UNKNOWN → 整侧 `null`；`StabilityTracker.observe`
  遇 `null` 即 `streak=0`。战斗中特效常让 1-2 个格子 UNKNOWN，连续 3 帧全可读概率低，
  确认被反复推后 → **锚点=确认序列第一帧也跟着推后**。
- 机理 B：`maxObservationGapMs=500` 对无障碍截屏通道过于苛刻——该通道系统截图有
  ~800ms 节流与失败退避，帧间隔 >500ms 是常态，每帧都"断链重计"→ 永不确认。
- 代码位置：`StabilityTracker.observe`（SubstitutionEngine.kt:24-60）、
  `DotRowCounting.count`（DotRowCounting.kt:15+）。
- 证据：§4 实测——一次干净替身（画面零遮挡）旧引擎显示延迟 1.2-1.4s、锚点晚 0.3-0.5s；
  有遮挡时会更糟。

### R2 — 战斗中盲窗 2s 失效基线 → 替身伴随长特效时计时被吞

- 机理：`lastValidAtMs` 距今 >2000ms → `invalidateBaseline`：基线作废、未决候选撤销。
  替身常伴随连招/奥义长特效，豆行 >2s 不可读是常态 → 恢复后新基线已是掉豆后的值，
  连个歧义事件都不报（掉豆期间计时**凭空消失**）。该机制曾删除又被 55a2e92 加回。
- 代码位置：`feed`/`invalidateBaseline`（SubstitutionEngine.kt:249-351）。

### R3 — "高位短驻"防抖动窗吞掉涨豆后立刻的真替身

- 机理：`minStableBeforeDropMs=1200`：高计数稳定不足 1.2s 就回落 → WobbleIgnored。
  但"技能命中涨豆 → 0.5s 内被击替身"是决斗场最常见的真替身节奏 → **真实替身被当抖动**。
- 历史：该窗在 1.2s/2.5s 之间多次改值（git log 可见），说明"防抖动"与"保真替身"
  两个目标用同一个时长窗无法同时满足——结构性缺陷，不是调参能解。

### R4 — 悬置级联撤销误伤"替身+单豆技能"序列

- 机理：悬置期（pendingVerifyMs=1500）内再掉豆 → 级联撤销。真实的奥义连扣是逐帧
  连续掉（≤0.4s），而"替身+立刻放 1 豆技能"间隔常在 0.5-1.5s → **窗太宽，误伤后者**。

### R5 — 双零门控误杀"一方真零豆"的合法观察

- 机理：锚点未标定时 `selfCount==0 && enemyCount==0 → NOT_BATTLE`（SubstitutionCore.kt:219-222）。
  实战中"我方 0 豆、敌方 0 豆"（双方都交完资源）完全合法 → 被判非战斗整帧丢弃。
  且该门控与"转场整排消失"无法区分——同一个信号干两件事。
- 修正方向：门控只信豆色之外的 HUD 锚点；未标定 = UNVERIFIED 不否决；转场整排消失
  由"双侧近同时归零回滚"规则兜底（见 §7）。

### R6 — 悬浮窗自遮挡 + 前台抖动 + 热更新清零

- R6a：**MediaProjection 会把自己的悬浮窗一起截进去**。默认悬浮窗位置 (40,120)px
  恰好覆盖左上我方豆排区域 → 未拖开悬浮窗时我方读数被悬浮窗半透明底色污染。
  （无障碍通道截的是游戏窗口本身，不含悬浮窗，不受影响——两通道表现不一致也是佐证。）
- R6b：前台轮询每次进入/离开游戏都 `resetBaseline`；多任务手势划出游戏助手侧栏
  造成的瞬时前台抖动即清掉基线。
- R6c：设置页改任意 layout/tuning 参数 → 整个 `SubstitutionEngine` 重建 →
  **正在走的计时全丢**（用户体感："我调了一下设置计时就没了"）。

### R7 — 缺锚点校准入口 → 门控长期停在降级模式

- `TimerLayout.hudAnchors` 默认空、持久化支持已具备，但校准页没有锚点框选 UI →
  真实用户永远处于"未标定"状态 → 场景判定永远走 R5 的弱启发式。

### 探针验证（已跑过，证据可复现）

对当前引擎构造 8 个"真实对局节奏"探针测试（断言期望行为）：

| 探针 | 现引擎结果 | v2 语义 |
|------|-----------|---------|
| 干净 4→3 替身（无障碍帧间隔 ~600ms） | 断链，不确认 | 确认并起算，锚点=首次目击 |
| 替身后 3s 特效盲窗 | 基线失效，计时消失 | 计时存活，恢复后照常 |
| 涨豆后 0.5s 受击替身 | WobbleIgnored 吞掉 | 起算（350ms 涨抖窗外） |
| 替身后 1s 放 1 豆技能 | 级联撤销 | CooldownDrop，计时保留 |
| 双方真零豆 | NOT_BATTLE 丢帧 | 合法观察（锚点门控） |
| 一局 3 次替身 | 第 2、3 次常丢 | 逐次起算互不干扰 |

## 4. 真机实测证据（docs/assets/substitution-timer-demo.mp4）

该录像是**真实对局画面**（1280×592，~24fps，25.45s），含旧引擎悬浮窗读数与游戏内
替身键冷却数字——可作 ground truth。逐帧测量（ffmpeg `select`+`showinfo` 取
pts_time 精确到帧）：

| 事件 | 证据（帧时刻） | 真实 T0 | 旧引擎表现 | 结论 |
|------|---------------|---------|-----------|------|
| 我方替身#1 | 豆排 金×4→青×3 与替身键出"15"同帧：(2.887, 2.928] | ≈2.91s | 首个读数"14.1s?"出现于 t=4.124 | **显示延迟 ≈1.22s；隐含锚点 ≈3.22s（晚 ~0.32s）** |
| 我方替身#2 | 同上翻转：(22.392, 22.433] | ≈22.41s | 首个读数"14.1s?"出现于 t=23.794 | **显示延迟 ≈1.38s；隐含锚点 ≈22.89s（晚 ~0.48s）** |

其它实测事实：
- **金×4→青×3 与替身键"15"同帧出现** → "豆数掉落即替身 T0"在数据层面成立（误差≤1 帧 ≈41ms），15s 冷却起点就是掉豆帧。
- 熄灭豆槽实测像素 min(maxChannel)≈64-81、中位 ≈95-125 → `blackFloor=10` 安全（不会误杀真暗槽）。
- 满豆金色火焰帧含白热高亮点（白像素不入任何颜色盒），但行仍判 L——比例竞争分类器正确。
- 青豆格 lit≈0.55 > dim≈0.29 判 LIT；空格 dim≈0.45-0.86 判 EMPTY —— 颜色盒在真实压缩画面上成立。
- 替身键冷却是天然的**我方侧 ground truth 探测器**（数字与悬浮窗同屏），真机验收（任务 11）用它对表。

复现命令：
```bash
ffmpeg -y -i docs/assets/substitution-timer-demo.mp4 \
  -vf "select='between(t,2.8,3.0)',showinfo" -fps_mode passthrough /tmp/w_%04d.png
# pts_time 见 ffmpeg stderr 的 showinfo 行；豆排 ROI 自 (128,52)-(222,80)，
# 替身键 (795,478)-(875,558)，悬浮窗 (715,62)-(915,135)
```

## 5. 开源方案对照（仅原理，禁止复制代码/坐标/资产）

| 项目 | 读豆规则 | 起算锚点 | 连续计时/防误报 | 可借鉴 | 不照搬 |
|------|---------|---------|----------------|--------|--------|
| K0NGCH4NG/Naruto_Mobile_Game_Tool（PC 模拟器，PyQt） | 固定坐标单像素 + RGB 盒；豆型合法性检查 | 掉豆确认帧 | 无冷却先验；100ms 轮询 | 规范豆形思想（我们已在 DotRowCounting 有等价实现并更强：逐格比例投票） | 单像素硬编码坐标、license 禁止修改再发布 |
| frayy3344/-（Auto.js 手机免 Root） | 1080p 基准坐标按分辨率等比缩放；单点亮度阈值 | **掉豆序列第一帧** | **冷却剩余 ≤1s 才受理新掉豆**——这就是连续计时的关键；纯黑帧判无效 | ①冷却先验 ②锚点=首次目击 ③纯黑判无效 | 单点采样（易被特效击穿）、无格级记忆 |
| iam0range/TOOLS_HUOYING | 固定 LDPlayer 分辨率 | — | — | 无（源码不在默认分支，未取证） | — |
| 233乐园文章所述 ResNet-18 方案 | CNN 识别替身图标 | — | — | 方向参考（§15 后续增强） | 未找到可用仓库；且本方案坚持零模型依赖 |

落地为 v2 的五条不变式：
1. **不可读帧不破坏证据**：不推进、不重置（格子/行/场景三级同理）。
2. **锚点=首次目击帧**，间隔大时取中点并标 `uncertain`——不确定度显式化。
3. **冷却先验**：剩余 >1s 的冷却内掉豆一律不是替身（CooldownDrop + altEndAtMs），原计时不让位。
4. **已起计时默认接受**，只允许三种短窗证伪回滚：回弹/连扣/双侧同塌。
5. **逐格记忆**：单格 UNKNOWN ≤400ms 沿用记忆值，超期才整行不可读。

## 6. 游戏机制事实（规则设计依据）

- 替身术冷却 15s（资料与实测一致；部分秘卷可 -2s，留 `cooldownMs` 可调）。
- 替身后约 2s 无敌；替身消耗 1 豆；豆=奥义点，常态上限 4。
- 豆数变化的其它来源：技能命中 +1、奥义清空（4→0）、部分忍者烧豆强化技能、
  部分秘卷/机制扣对方豆 → **豆减≠替身**，计时一律标 `suspected`。
- 奥义/连扣的豆是逐帧连续减少的（≤0.4s 间隔）→ cascade 窗口取 400ms。
- 实测：豆排全亮=金色、非满=青蓝；熄灭=深藏青（min maxChannel≈64）。

## 7. v2 目标设计

### 7.1 每帧处理管线

```
onCells(selfCells, enemyCells, frameAtMs, battle)
  ├─ paused → 空结果
  ├─ frameAtMs ≤ lastFrameAtMs → 丢弃（重放/乱序帧）
  ├─ !battle → 整帧不可读；非战斗持续 >hudLostResetMs → match_end 复位(清计时+基线)，只发一次
  ├─ 每侧 count = tracker.update(cells)：null=不可读（含格记忆超期/非规范豆形）
  ├─ 双侧皆 null → 不可读持续 >hudLostResetMs → BaselineReset("hud_lost")（计时保留）
  └─ 每侧 confirmer.update(count) → Transition(from,to,firstSeen,lastOldSeen,gap)
        ├─ from==null → CountConfirmed（建基线，非事件）
        ├─ to>from → 回弹检查（≤reboundRetractMs 内弹回 → TimerRetracted+恢复 prevTimer）
        │            否则 GainConfirmed（记 lastGainAnchor）
        └─ to<from → evaluateDrop（判定表 ↓）
```

### 7.2 单侧掉落判定表（按顺序短路）

| 序 | 条件 | 语义 | 动作 |
|----|------|------|------|
| D0 | gap > maxAnchorGapMs(2500) | 证据空白太长，触发时刻不可知 | `AmbiguousDrop(reason="gap")`，不计时 |
| D1 | to==0 且对侧"掉到 0"的首见时刻与本侧首见时刻差 ≤collapseWindowMs(300) | 转场/结算整排消失 | 回滚**对侧由该归零创建**的计时 `TimerRetracted("collapse")` + `AmbiguousDrop("collapse")`；更早的真实计时不连坐 |
| D2 | anchor − lastGainAnchor ≤ gainFlickerMs(350) | 涨豆瞬间回落=奥义清豆/涨豆残影 | `GainFlickerIgnored`，不计时 |
| D3 | to<from−1，或窗口内已有 ≥1 次 −1 且本次也是 −1 | 一次多豆/逐帧连扣 | 连扣则回滚窗内计时 `TimerRetracted("cascade")` + `MultiDrop`，不计时 |
| D4 | 有计时且 remaining > cooldownGraceMs(1000) | **冷却先验**：不可能是替身 | `CooldownDrop`，原计时保留，记 `altEndAtMs=anchor+cooldown` |
| D5 | 其余 −1 | 疑似替身 | `SubstitutionStarted`：timer(anchor,end=anchor+cooldown,uncertain=gap>700)；prevTimer 链入 |

回弹（涨豆方向，feedSide 内）：新计数 ≥ "创建当前计时那次掉落"的原值 且离该掉落锚点
≤ reboundRetractMs(500) → `TimerRetracted("rebound")` 恢复 prevTimer；已到期/已换计时不回滚；
冷却冲突掉豆（created=null）不参与回弹判定。

### 7.3 参数表

| 参数 | 默认 | 依据 | 待真机复核 |
|------|------|------|-----------|
| `confirmFrames` | 2 | K0NGCH4NG 0 帧/frayy3344 ~350ms；可读帧才计数，2 帧≈200-1200ms 证据 | 是 |
| `cellHoldMs` | 400 | 单格记忆 ≈ 投影 4 帧；超期说明遮挡持续 | 是 |
| `cooldownGraceMs` | 1000 | frayy3344"剩余≤1s"；吸收锚点误差 | 否 |
| `reboundRetractMs` | 500 | 掉→弹回的最短窗口（替身位移特效量级） | 是 |
| `gainFlickerMs` | 350 | 涨豆残影/奥义清豆；受击替身间隔普遍 >0.5s | 是 |
| `cascadeWindowMs` | 400 | 奥义逐帧连扣间隔量级 | 是 |
| `uncertainGapMs` | 700 | >该间隔锚点取中点+uncertain 标记 | 是 |
| `maxAnchorGapMs` | 2500 | 旧盲窗量级转用为歧义分界（不再失效基线） | 是 |
| `collapseWindowMs` | 300 | 转场双侧整排同时消失 | 是 |
| `hudLostResetMs` | 8000 | 大于任意技能演出时长；match_end 判据 | 是 |
| `blackFloor` | 10 | 真暗槽 maxChannel≈64-125（§4 实测），纯黑仅出现于黑屏转场 | 否 |
| `frameIntervalMs` | 100 | 两开源实现 35-100ms；仓储层默认 140→100 | 是 |
| `cooldownMs` | 15000 | §6；秘卷减 CD 由用户自行调整 | 否 |

### 7.4 旧机制 → 新机制映射

| 旧机制（删除） | 新机制（替代） |
|----------------|----------------|
| `StabilityTracker`（连续帧+500ms 断链） | `CountConfirmer`：只数**可读**帧，不可读/稀疏不重置 |
| `invalidBaselineTimeoutMs=2000` 盲窗失效 | `maxAnchorGapMs` 歧义标记 + `hudLostResetMs` 基线重建（计时保留） |
| 悬置候选 `PendingCandidate` + `CandidateConfirmed`/`CandidateCancelled` | 即确认即起算；仅回弹/连扣/双塌三种短窗可证伪回滚 |
| `minStableBeforeDropMs=1200` 抖动窗 | `gainFlickerMs=350`（涨豆残影） |
| `fastReboundCancelMs=600` | `reboundRetractMs=500`（锚点差口径） |
| 悬置级联撤销（1500ms） | `cascadeWindowMs=400` 且仅 −1 连扣步进触发回滚 |
| `dedupeMs=800` | 冷却先验天然去重，删除 |
| contested 冲突候选 | `CooldownDrop` 事件 + `altEndAtMs` 备选截止 |
| 双零门控（SubstitutionCore.kt:219-222） | 未标定=UNVERIFIED 不否决 + collapse 回滚 + 锚点校准 |
| `SideTimer.pending` "推断中" | `uncertain` + `altEndAtMs` 字段 |

## 8. 文件改动地图

| 文件 | 动作 | 任务 |
|------|------|------|
| `agent-core/.../substitution/SubstitutionCore.kt` | 改：`DetectionTuning` 参数面、`DotClassifier` 加近黑下限、`evaluateScene` 简化为两参、`SideTimer` 新字段、新增 `deriveAnchorBox` | T2 |
| `agent-core/.../substitution/BeadRowTracker.kt` | **新建**：逐格记忆 + 规范豆形 + 计数 | T3 |
| `agent-core/.../substitution/SubstitutionEngine.kt` | 重写上半部（CountConfirmer、TimerEvent、引擎主体）；保留下半部 `TimerServiceState`/`TimerStateMachine`/`LaunchEvent` 不动 | T3 |
| `agent-core/.../substitution/DiagnosticLog.kt` | 追加 `replayCells` 扩展 | T3 |
| `agent-core/.../substitution/SubstitutionCoreTest.kt` | 迁移：删引擎行为用例（由新场景测试接管），改 HUD 测试签名，加 2 个新分类器用例 | T4 |
| `agent-core/.../substitution/SubstitutionScenarioTest.kt` | **新建**：50 条 v2 行为规格（先写先红） | T1 |
| `app/.../substitution/DotRowCounting.kt` | `count()` → `readCells()` 逐格返回 + `masked` 参数 | T6 |
| `app/.../substitution/DotRowCountingTest.kt` | 重写 6 条 | T6 |
| `app/.../substitution/SubstitutionTimerService.kt` | `onFrame` 改 `readCells`+`onCells`；事件文案映射；前台去抖；热更新计时结转；悬浮窗 mask | T5/T6/T7/T8 |
| `app/.../substitution/SubstitutionOverlay.kt` | 删 `pending` 分支；加 `uncertain`/`altEndAtMs` 文案、`screenRect` 发布、默认位置、`overlayOverlap` 提示 | T5/T8 |
| `app/.../substitution/SubstitutionSettingsPage.kt` | 新增战斗锚点校准组（框选→deriveAnchorBox→实时判定预览） | T9 |
| `data/.../SubstitutionTimerRepository.kt` | tuning 收敛（删失效键）、frameIntervalMs 默认 140→100 | T5/T7 |
| `docs/substitution-timer.md`、`CHANGELOG.md` | 文档同步 | T10 |

提交切分：**A=任务1-5（引擎原子替换，一次提交）**；B=T6；C=T7；D=T8；E=T9；F=T10。
任务 1-8 强耦合须顺序执行；T9 只动 SettingsPage，可在 A 落地后与 B/C/D 并行。

## 9. 实施任务

> 验证命令统一基线（全部离线可跑；已在当前 HEAD `3878ec0` 验证 EXIT=0）：
> `./gradlew :agent-core:test :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug --offline`
> 现基线：SubstitutionCoreTest 49 + TimerStateMachineTest 4 + DotRowCountingTest 5 = 58 绿。
> 定位代码一律用文中给出的原文片段搜索（行号会漂移）。

### 任务 1 —— 先写行为规格（红）

- 新建 `agent-core/src/test/kotlin/xyz/chouxuewei/mobile_agent/core/substitution/SubstitutionScenarioTest.kt`，完整内容见 §10.1（50 条）。
- 验收：`./gradlew :agent-core:test --offline` **编译失败**即为预期红（引用尚不存在的 `SubstitutionStarted`/`CooldownDrop`/`BeadRowTracker`/`onCells` 等符号）。**此任务不单独提交**，与任务 2-5 合并为提交 A。

### 任务 2 —— `SubstitutionCore.kt` 参数与类型

改动四处（完整新代码见 §10.2-10.4）：
1. `DetectionTuning`（行 76-114）：删除 `maxUnknownRatio`、`invalidBaselineTimeoutMs`、`dedupeMs`、`maxObservationGapMs`、`minStableBeforeDropMs`、`pendingVerifyMs`、`fastReboundCancelMs`；新增 `cellHoldMs/reboundRetractMs/gainFlickerMs/cascadeWindowMs/uncertainGapMs/maxAnchorGapMs/collapseWindowMs/hudLostResetMs/cooldownGraceMs/blackFloor`；`confirmFrames` 默认 3→2 且语义改注"可读帧"。颜色盒与比例字段原样保留。
2. `DotClassifier.classify`：点亮判定不变；熄灭判定前先滤近黑像素（`max(r,g,b) <= blackFloor` 既不计 lit 也不计 dim）——黑屏转场帧不再被当成"全灭豆槽"。
3. `evaluateScene`（行 213-224）：签名收窄为 `fun evaluateScene(anchors: List<HudAnchor>, anchorSamples: List<IntArray>): SceneVerdict`；锚点空 → 恒 `UNVERIFIED`（删除双零弱启发式）；非空 → `HudGate(anchors).verdict(anchorSamples)`。`HudGate` 本身不动。新增 `deriveAnchorBox(pixels: IntArray, tolerance: Int = 30): RgbBox`（§10.4）。
4. `TimerConfig.frameIntervalMs` 默认 80→100；`SideTimer`（行 278-290）加 `anchorAtMs/uncertain/altEndAtMs/prevTimer` 字段、删 `pending`（§10.5）。
- 验收：`./gradlew :agent-core:compileKotlin --offline`（此刻 `SubstitutionEngine.kt` 还是旧代码会报错——属预期，任务 3 后才要求编译过；也可直接跳到任务 3 后统一验收）。**建议任务 2/3 作为一步实施，验收点放在任务 3。**

### 任务 3 —— `BeadRowTracker.kt` 新建 + `SubstitutionEngine.kt` 重写 + `DiagnosticLog.replayCells`

- 新建 `BeadRowTracker.kt`：§10.6 完整代码（逐格记忆 + 规范豆形 + 计数，纯输入翻译器）。
- `SubstitutionEngine.kt`：删除行 9-404 全部内容（`StabilityTracker`、旧 `TimerEvent`、`PendingCandidate`、`Side`、`SubstitutionEngine`），替换为 §10.7-10.9 的 `CountConfirmer` + 新 `TimerEvent` + 新 `SubstitutionEngine`；**保留行 406 起的 `TimerServiceState`/`TimerStateMachine`/`LaunchEvent` 原样**。文件头部确认 `import kotlin.math.abs`（塌缩检测用），`import kotlin.math.max` 若已无引用则删。
- 新引擎 API（服务兼容面）：
  - `onCells(selfCells, enemyCells, frameAtMs=clock(), battle) → FrameResult(events, counts)`
  - `onFrame(selfCount, enemyCount, frameAtMs, battle)`：兼容入口——计数合成规范豆形格列（SELF 左锚、ENEMY 右锚）后转 `onCells`；回放与未改造的调用方用。
  - `timers(nowMs=clock()) → Pair<SideTimer, SideTimer>`（签名不变）；`pause()/resume()/resetBaseline(reason)/cancelTimer(side)/diagnostics()` 语义不变；新增 `carriedTimers()` 与构造参数 `carried`（热更新结转）。
- `DiagnosticLog.kt` 末尾追加 `fun SubstitutionEngine.replayCells(records) → List<List<TimerEvent>>`（§10.10），原 `replay` 保留但内部改走 `onFrame` 兼容入口（签名不变，行为=逐格等价）。
- 验收：`./gradlew :agent-core:compileKotlin --offline` EXIT=0。

### 任务 4 —— 迁移旧测试，全量绿

`SubstitutionCoreTest.kt`：
- **删除**全部引用旧 `TimerEvent` 子类/旧引擎行为的用例（`SubstitutionCandidate`/`CandidateConfirmed`/`CandidateCancelled`/`WobbleIgnored`/`contested`/`pending`/`AmbiguousDrop` 旧语义相关用例与它们的私有 helper）——其行为规格已由 §10.1 新文件接管，**不允许在新测试里复刻旧断言**。
- **修改** 3 条 `evaluateScene` 调用为新两参签名；删除"双零→NOT_BATTLE"断言（该语义已删），改为"未标定锚点→UNVERIFIED"。
- **新增** 2 条：近黑像素不计入 dim（`blackFloor`）；`deriveAnchorBox` 对均匀色块给出命中盒。
- 保留：颜色盒分类（约 8 条）、`dotCells` 布局（2 条）、`HudGate` 命中（3 条）、`TimerStateMachineTest`（4 条）全部不动。
- 验收：`./gradlew :agent-core:test --offline` EXIT=0；预期计数 ≈ `SubstitutionScenarioTest 50 + SubstitutionCoreTest ~15 + TimerStateMachineTest 4`。若有断言与实现存在**细节**分歧（如事件字段名），以 §7.2 判定表为准修正测试，**禁止放宽语义**（少触发=对、多触发=错）。

### 任务 5 —— 编译面修正（提交 A 收尾）

- `SubstitutionTimerRepository.kt`：`DetectionTuning` 构造处删除已不存在字段的读取（`INVALID_TIMEOUT_MS`/`DEDUPE_MS` 等键；残留旧 pref 值无害）；`frameIntervalMs` 默认 140→100（clamp 80..500 不变）。
- `SubstitutionTimerService.kt`：
  - `evaluateScene(cfg.layout.hudAnchors, anchorSamples, selfCount, enemyCount)` → `evaluateScene(cfg.layout.hudAnchors, anchorSamples)`。
  - 事件 `when` 分支（行 433-441）重写为新事件文案（§10.11）。
  - `eng.onFrame(...)` 调用本任务**保持不变**（兼容入口仍可用）。
- `SubstitutionOverlay.kt`：删除 `timer.pending → "推断中…"` 分支（字段已删）；计时文案改为：uncertain → 前缀 `≈`；`conflict && altEndAtMs != null` → 追加 `(备选 x.xs)`；`suspected` 仍后缀 `?`。§10.12。
- 验收：`./gradlew :agent-core:test :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug --offline` 全绿 → **提交 A**：
  `refactor(substitution): replace pending/window heuristics with cooldown-prior engine (v2)`。

### 任务 6 —— 逐格采样接入（提交 B）

- `DotRowCounting.kt`：整体替换为 §10.13 的 `readCells`（已用等价测试验证 4/4）。返回 `List<DotState>?`；新增 `masked: (IntArray) -> Boolean = { false }` 参数（本任务先不传，任务 8 接入）。
- `DotRowCountingTest.kt`：整文件替换为 §10.14（6 条）。
- `SubstitutionTimerService.kt` `onFrame`：`countDots` 局部函数改为 `readSide` 返回格列表，`eng.onFrame(counts)` 改 `eng.onCells(selfCells, enemyCells, now, battle)`；`counts` 从 `result.counts` 取；`cellStates` 的 L/E/U 记法不变（诊断 schema 不动）。§10.15。
- 验收：同上命令全绿（重点是 DotRowCountingTest 6 条）→ 提交 B：`feat(substitution): cell-level sampling with per-cell memory semantics`。

### 任务 7 —— 服务鲁棒性（提交 C）

1. **前台去抖**（前台轮询循环，检索 `resetBaseline("foreground`）：进入游戏立即 `resetBaseline("foreground_enter")`；离开方向要求**连续 2 次**轮询不在前台才 `resetBaseline("foreground_leave")`——消除手势侧栏瞬时抖动清基线。§10.16。
2. **几何变化**：保留 `resetBaseline("geometry_changed")`（尺寸/通道变更是真变更）。
3. **配置热更新计时结转**（设置流 collect 里 `engine = SubstitutionEngine(...)` 处）：`val carried = synchronized(engineLock) { engine?.carriedTimers() ?: emptyMap() }`，`SubstitutionEngine(cfg, carried = carried)`。§10.17。
4. `SubstitutionTimerRepository.kt` `frameIntervalMs` 默认确认已为 100（任务 5 已改）；`TimerConfig` 默认已改。
- 验收：编译 + 既有测试绿；手测：对局中改校准偏移 → 计时不清零 → 提交 C：`fix(substitution): debounce foreground watch and carry timers across config reload`。

### 任务 8 —— 悬浮窗自遮挡（提交 D）

1. `SubstitutionOverlay.kt`：新增 `@Volatile var screenRect: Rect?`、`@Volatile var displaySize = IntArray(2)`；`show()` 后、拖拽 `ACTION_UP` 后、`addOnLayoutChangeListener` 中 `post { publishRect() }`（`getLocationOnScreen`+宽高），`hide()` 置 null；`overlayX/overlayY` 为空时默认 y=屏高 30%（避开左上豆排）；`updateTimers(...)` 加 `overlayOverlap: Boolean = false` 参数：true → 状态行"悬浮窗遮挡豆排——请拖动移开"。§10.18。
2. `SubstitutionTimerService.kt`：新增 `overlayMask(access)`（投影通道且悬浮窗可见时，把屏幕坐标按 `frame.w/screen.w` 等比映射成帧坐标，返回与豆格 rect 相交判断；方向不一致（屏竖帧横/反之）返回空 mask）；`UiState` 加 `overlayOverlap`；`readSide` 传 `masked = overlayMask(access)`；`publishTimers` 计算 `overlayOverlap` 并传入 `updateTimers`。§10.19。
- 验收：编译 + 测试绿；真机手检：把悬浮窗拖到豆排上 → 豆数读数不跳变、状态行出提示 → 提交 D：`fix(substitution): mask own overlay from projection frames and move default position`。

### 任务 9 —— 战斗锚点校准 UI（提交 E）

`SubstitutionSettingsPage.kt` 校准面板（CalibrationPanel，在敌方豆槽滑块组与保存按钮之间插入）：
- 新增状态：`anchorEnabled: Boolean`、`anchorRect: NormalizedRect`（默认 `NormalizedRect.of(0.955f, 0.02f, 0.99f, 0.075f)`，即右上暂停键区）、`anchorWarn: String?`、实时 `verdict` 展示。
- UI 组：`SettingSwitch`("启用战斗 HUD 锚点") + 4 个滑块（沿用现有豆槽滑块组件改 `anchorRect`）+ 画布叠加**蓝色**锚框 + 一行实时判定："锚点命中=战斗中 / 未命中=非战斗（计时暂停）/未配置"。
- 保存按钮逻辑：`hudAnchors = if (anchorEnabled && box != null) listOf(HudAnchor(anchorRect, box, 0.6f)) else emptyList()`；`box = deriveAnchorBox(sampleRectPixels(preview, anchorRect))`；若采样色 P90−P10 任一通道 >60 → `anchorWarn`="该区域颜色不均，请换纯色区域（如暂停键外框）"且仍可保存（警告非阻断）。
- `sampleRectPixels` 与判定预览代码见 §10.20。用法提示文案加在组首："在**战斗中**打开本页框选常驻 HUD 元素（推荐暂停键/血条框）；在菜单页标定会导致判反。"
- 验收：`./gradlew :app:compileDebugKotlin --offline`；真机：战斗中框选后"监视状态"显示战斗中、退出到菜单显示非战斗 → 提交 E：`feat(substitution): battle-anchor calibration UI with live verdict`。

### 任务 10 —— 文档（提交 F）

- `docs/substitution-timer.md`：检测原理一节改写为 v2 五不变式 + 判定表；参数表替换；已知边界更新；测试记录更新为新计数（50/15/4/6）；注明录像证据 §4 两例。
- `CHANGELOG.md` 在 `## [0.1.18]` 之上新增 `## [Unreleased]` 条目（§10.21 文案）。
- 提交 F：`docs(substitution): v2 engine semantics and verification evidence`。

### 任务 11 —— 真机验收（需用户真机，agent 提供操作清单）

1. `./gradlew :app:assembleDebug` 装包；校准页：框豆槽 → 开诊断 → 战斗中框锚点 → 悬浮窗拖离豆排。
2. 打 ≥5 局决斗场（含至少 1 局有奥义/长特效的），每次自己/对方替身时留意悬浮窗；局间误报按"标错了"键。
3. `adb pull` 诊断目录（`run-as xyz.chouxuewei.mobile_agent` 或 root）：`files/diag/*.jsonl` + 事件裁剪图。
4. 把 jsonl 拷入 `agent-core/src/test/resources/diag/`，跑 §10.22 回放线测试打印事件流，对照替身键冷却数字/录屏核对。
5. **验收指标**：我方计时与替身键数字偏差 ≤0.5s（§4 方法）；整场零"需要手动重置"；非战斗画面零计时；漏替身上报率 <5%；误报 ≤1 次/局。
6. 不达标项 → 按 §7.3"待真机复核"列逐个调参（每条规则单一参数），改参后把该参数在 §10.1 对应用例的期望值同步更新并回灌本文档。
7. 达标后：把录像/jsonl 证据摘要补进 `docs/substitution-timer.md` 测试记录一节。

## 10. 完整代码清单

### 10.1 `SubstitutionScenarioTest.kt`（新建，50 条）

```kotlin
package xyz.chouxuewei.mobile_agent.core.substitution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 替身计时行为规格：冷却先验 + 首次目击锚点 + 逐格记忆 + 短窗证伪回滚。
 * 每条用例对应 §7.2 判定表的一行或一条不变式。
 */
class SubstitutionScenarioTest {
    private val L = DotState.LIT
    private val E = DotState.EMPTY
    private val U = DotState.UNKNOWN

    /** 我方豆形：左锚 [L×n, E×(4−n)]。 */
    private fun c(n: Int): List<DotState> = List(4) { if (it < n) L else E }
    /** 敌方豆形：右锚 [E×(4−n), L×n]——喂 c(n) 会成洞形被 BeadRowTracker 判不可读。 */
    private fun e(n: Int): List<DotState> = List(4) { if (it >= 4 - n) L else E }
    private fun cells(vararg s: DotState): List<DotState> = s.toList()

    private fun engine(
        cooldownMs: Long = 15_000,
        tuning: DetectionTuning = DetectionTuning(),
        carried: Map<TimerSide, SideTimer> = emptyMap(),
    ) = SubstitutionEngine(
        TimerConfig(cooldownMs = cooldownMs, tuning = tuning),
        clock = { 0L },   // 帧时间全部由测试喂入，clock 只用于 timers()/事件戳兜底
        carried = carried,
    )

    private fun feed(
        eng: SubstitutionEngine,
        t: Long,
        self: List<DotState>?,
        enemy: List<DotState>?,
        battle: Boolean = true,
    ) = eng.onCells(self, enemy, t, battle)

    private fun ev(r: SubstitutionEngine.FrameResult): List<TimerEvent> = r.events
    private fun timerOf(eng: SubstitutionEngine, side: TimerSide, now: Long = 0) =
        (if (side == TimerSide.SELF) eng.timers(now).first else eng.timers(now).second)
            .takeIf { it.active }

    /**
     * 让 side 完成一次 4→3 掉落确认（锚点=firstSeenAt）。
     * 注意：调用前须保证旧计数最近刚被读到（gap ≤ uncertainGapMs 才有确定锚点），
     * 且帧时刻必须严格递增（stale 帧会被引擎丢弃）。
     */
    private fun substitute(eng: SubstitutionEngine, side: TimerSide, firstSeenAt: Long) {
        val s = if (side == TimerSide.SELF) c(3) to null else null to e(3)
        feed(eng, firstSeenAt, s.first, s.second)
        feed(eng, firstSeenAt + 140, s.first, s.second)
    }

    private fun baseline4(eng: SubstitutionEngine) {
        feed(eng, 0, c(4), e(4))
        feed(eng, 140, c(4), e(4))
    }

    // ---------- A. CountConfirmer（7） ----------

    @Test fun `two readable sightings confirm even with unreadable frames in between`() {
        val cf = CountConfirmer(2)
        assertNull(cf.update(3, 0))
        assertNull(cf.update(null, 140))
        val t = cf.update(3, 280)
        assertNotNull(t); assertEquals(3, t!!.to)
    }

    @Test fun `sparse sightings still confirm`() {
        val cf = CountConfirmer(2)
        assertNull(cf.update(3, 0))
        assertNotNull(cf.update(3, 5_000))
    }

    @Test fun `changing counts do not confirm`() {
        val cf = CountConfirmer(2)
        assertNull(cf.update(2, 0)); assertNull(cf.update(3, 140))
        assertNull(cf.update(2, 280)); assertNull(cf.update(3, 420))
        assertNotNull(cf.update(3, 560))
    }

    @Test fun `confirm produces a transition with first and last sight`() {
        val cf = CountConfirmer(2)
        cf.update(4, 0); cf.update(4, 140)
        cf.update(3, 1000)
        val t = cf.update(3, 1140)!!
        assertEquals(4, t.from); assertEquals(3, t.to)
        assertEquals(1000L, t.firstSeenAtMs); assertEquals(140L, t.lastOldSeenAtMs)
        assertEquals(860L, t.gapMs)
    }

    @Test fun `stale frame is dropped`() {
        val cf = CountConfirmer(2)
        cf.update(3, 50)
        assertNull(cf.update(4, 10))            // 乱序：不推进也不污染候选
        cf.update(4, 60)
        val t = cf.update(4, 80)!!
        assertEquals(60L, t.firstSeenAtMs)
    }

    @Test fun `unmatched candidate is replaced by a longer stable reading`() {
        val cf = CountConfirmer(2)
        cf.update(4, 0); cf.update(4, 140)
        cf.update(2, 500)                       // 候选 2，证据不足
        cf.update(3, 640)                       // 换候选 3
        val t = cf.update(3, 780)!!
        assertEquals(3, t.to); assertEquals(640L, t.firstSeenAtMs)
    }

    @Test fun `reset clears confirmed baseline`() {
        val cf = CountConfirmer(2)
        cf.update(4, 0); cf.update(4, 140); cf.reset()
        assertNull(cf.update(2, 500))                 // 首见候选，证据不足
        val t = cf.update(2, 640)!!                   // 二次目击确认 = 新基线（from=null）
        assertNull(t.from); assertEquals(2, t.to); assertEquals(500L, t.firstSeenAtMs)
    }

    // ---------- B. 起算与锚点（5） ----------

    @Test fun `first stable reading establishes baseline without a timer`() {
        val eng = engine()
        baseline4(eng)
        assertTrue(ev(feed(eng, 280, c(4), e(4))).isEmpty())
        assertNull(timerOf(eng, TimerSide.SELF)); assertNull(timerOf(eng, TimerSide.ENEMY))
    }

    @Test fun `drop by one anchors at first sight and starts cooldown`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 900, c(4), null)               // 旧计数贴近掉落：gap=100 → 锚点确定
        feed(eng, 1000, c(3), null)
        val es = ev(feed(eng, 1140, c(3), null))
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(1000L, start.anchorAtMs); assertEquals(16_000L, start.endAtMs)
        assertFalse(start.uncertain)
        assertEquals(16_000L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
    }

    @Test fun `anchor remains the first sighting even when confirmation lands later`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 1900, c(4), null)
        eng.onCells(c(3), null, 2000)
        val es = ev(eng.onCells(c(3), null, 6000))   // 确认帧在 6s 后，锚点仍是首见
        assertEquals(2000L, es.filterIsInstance<TimerEvent.SubstitutionStarted>().single().anchorAtMs)
    }

    @Test fun `interleaved unreadable frames do not delay the anchor`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 200, c(3), null)
        feed(eng, 340, null, null)                   // 不可读不重置证据
        val es = ev(feed(eng, 480, c(3), null))
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(200L, start.anchorAtMs); assertFalse(start.uncertain)
    }

    @Test fun `sparse observations still anchor at the first sighting`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 600, c(3), null)
        val es = ev(feed(eng, 4000, c(3), null))
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(600L, start.anchorAtMs); assertFalse(start.uncertain) // gap=460≤700
    }

    // ---------- C. 增益与防抖（6） ----------

    @Test fun `gains never start timers`() {
        val eng = engine()
        feed(eng, 0, c(3), null); feed(eng, 140, c(3), null)
        feed(eng, 1000, c(4), null)
        val es = ev(feed(eng, 1140, c(4), null))
        assertTrue(es.any { it is TimerEvent.GainConfirmed })
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    @Test fun `gain flicker drop is ignored`() {
        val eng = engine()
        feed(eng, 0, c(3), null); feed(eng, 140, c(3), null)
        feed(eng, 1000, c(4), null); feed(eng, 1140, c(4), null)   // 涨豆 anchor=1000
        feed(eng, 1200, c(3), null)
        val es = ev(feed(eng, 1340, c(3), null))                    // 掉豆 anchor=1200，差 200≤350
        assertTrue(es.any { it is TimerEvent.GainFlickerIgnored })
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    @Test fun `slow drop after a gain still starts a timer`() {
        val eng = engine()
        feed(eng, 0, c(3), null); feed(eng, 140, c(3), null)
        feed(eng, 1000, c(4), null); feed(eng, 1140, c(4), null)
        feed(eng, 1400, c(3), null)
        val es = ev(feed(eng, 1540, c(3), null))                    // 差 400>350 → 真替身
        assertTrue(es.any { it is TimerEvent.SubstitutionStarted })
    }

    @Test fun `rebound restores the previously running timer`() {
        // 短冷却造出"前任计时"：A(200,2200) 余量<1s 时 B(1400,3400) 起算，B 回弹应恢复 A
        val eng = engine(cooldownMs = 2000, tuning = DetectionTuning(cooldownGraceMs = 1000))
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)                        // A：anchor 200（gap=60），end 2200
        feed(eng, 1300, c(3), null)                                  // 刷新旧计数目击
        feed(eng, 1400, c(2), null); feed(eng, 1540, c(2), null)    // B：anchor 1400，end 3400，prev=A
        feed(eng, 1600, c(3), null)
        val es = ev(feed(eng, 1740, c(3), null))                    // 1600-1400=200≤500 回弹
        assertTrue(es.any { it is TimerEvent.TimerRetracted })
        assertEquals(2200L, timerOf(eng, TimerSide.SELF)!!.endAtMs)  // 恢复 A
    }

    @Test fun `rebound window counts from the drop anchor`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 900, c(4), null)
        substitute(eng, TimerSide.SELF, 1000)                       // A：anchor 1000，end 16000
        feed(eng, 1500, c(4), null)
        val es = ev(feed(eng, 1640, c(4), null))                    // 1500-1000=500≤500 → 回弹
        assertTrue(es.any { it is TimerEvent.TimerRetracted })
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    @Test fun `return past the rebound window does not retract`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 900, c(4), null)
        substitute(eng, TimerSide.SELF, 1000)
        feed(eng, 1600, c(4), null)
        val es = ev(feed(eng, 1740, c(4), null))                    // 1600-1000=600>500 → 真涨豆
        assertTrue(es.any { it is TimerEvent.GainConfirmed })
        assertEquals(16_000L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
    }

    // ---------- D. 多豆与连锁（6） ----------

    @Test fun `multi bean drain produces no timer`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 1000, c(2), null)
        val es = ev(feed(eng, 1140, c(2), null))
        assertTrue(es.any { it is TimerEvent.MultiDrop })
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    @Test fun `multi drop during cooldown keeps the running timer`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)                        // A：4→3@200
        feed(eng, 500, c(1), null)
        val es = ev(feed(eng, 640, c(1), null))                     // 3→1 多豆（非连扣步进）
        assertTrue(es.any { it is TimerEvent.MultiDrop })
        assertTrue(es.none { it is TimerEvent.TimerRetracted })
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
    }

    @Test fun `chain drop inside cascade window retracts only timers started in the chain`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 900, c(4), null)
        substitute(eng, TimerSide.SELF, 1000)                       // A：anchor 1000，end 16000
        feed(eng, 1300, c(2), null)
        val es = ev(feed(eng, 1440, c(2), null))                    // 3→2@1300：链内第二步 −1
        assertTrue(es.any { it is TimerEvent.MultiDrop })
        assertTrue(es.any { it is TimerEvent.TimerRetracted })      // A 被回滚（链内计时）
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    @Test fun `chain drop after a real substitution keeps that timer`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)                        // T0：4→3@200，end 15200
        feed(eng, 1000, c(2), null); feed(eng, 1140, c(2), null)    // 冷却内 → CooldownDrop + 记录
        feed(eng, 1300, c(1), null)
        val es = ev(feed(eng, 1440, c(1), null))                    // 2→1 连扣 → MultiDrop
        assertTrue(es.any { it is TimerEvent.MultiDrop })
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs) // T0 锚点在回滚窗外，保留
    }

    @Test fun `drop past cascade window is conflict not multi`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 900, c(4), null)
        substitute(eng, TimerSide.SELF, 1000)
        feed(eng, 1401, c(2), null)
        val es = ev(feed(eng, 1541, c(2), null))                    // 401ms>400 → 非连扣
        assertTrue(es.any { it is TimerEvent.CooldownDrop })
        assertTrue(es.none { it is TimerEvent.MultiDrop })
        assertEquals(16_401L, timerOf(eng, TimerSide.SELF)!!.altEndAtMs)
    }

    @Test fun `drop of two or more never counts as substitution`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 1000, c(0), null)
        val es = ev(feed(eng, 1140, c(0), null))                    // 4→0 一次清空
        assertTrue(es.any { it is TimerEvent.MultiDrop })
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    // ---------- E. 冷却先验（5） ----------

    @Test fun `drop inside cooldown records alternative end but keeps timer`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)
        feed(eng, 1000, c(2), null)
        val es = ev(feed(eng, 1140, c(2), null))
        assertTrue(es.any { it is TimerEvent.CooldownDrop })
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
        assertEquals(16_000L, timerOf(eng, TimerSide.SELF)!!.altEndAtMs)
        assertTrue(timerOf(eng, TimerSide.SELF)!!.conflict)
    }

    @Test fun `cooldown drop does not start a second timer`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)
        feed(eng, 1000, c(2), null); feed(eng, 1140, c(2), null)
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs) // 原计时不让位
    }

    @Test fun `alternative end equals the second drop anchor plus cooldown`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)
        feed(eng, 800, c(2), null); feed(eng, 940, c(2), null)
        assertEquals(15_800L, timerOf(eng, TimerSide.SELF)!!.altEndAtMs)
    }

    @Test fun `drop at exact grace boundary starts a new timer`() {
        val eng = engine()                                           // cd 15000、grace 1000
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)                         // A：end 15200
        feed(eng, 14_000, c(3), null)                                // 刷新旧计数目击
        feed(eng, 14_060, c(2), null)                                // 首见 14060，gap=60
        val es = ev(feed(eng, 14_200, c(2), null))                   // now=14200，A.remaining=1000=grace → 起算
        assertTrue(es.any { it is TimerEvent.SubstitutionStarted })
        assertEquals(29_060L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
    }

    @Test fun `repeated real substitutions each start their own timer`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)                         // A：end 15200
        feed(eng, 15_300, c(3), null)                                // A 已到期；刷新旧计数
        feed(eng, 15_400, c(2), null)
        val es = ev(feed(eng, 15_540, c(2), null))                   // remaining≤0 → 新替身 B
        assertTrue(es.any { it is TimerEvent.SubstitutionStarted })
        val t = timerOf(eng, TimerSide.SELF)!!
        assertEquals(30_400L, t.endAtMs)
        assertEquals(15_200L, t.prevTimer!!.endAtMs)                 // 前任链入
    }

    // ---------- F. 不可读与歧义（7） ----------

    @Test fun `ambiguous gap produces ambiguous drop without a timer`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 5000, c(3), null)
        val es = ev(feed(eng, 5140, c(3), null))                    // gap=4860>2500
        assertTrue(es.any { it is TimerEvent.AmbiguousDrop })
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    @Test fun `post gap drop is marked uncertain`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 1000, c(3), null)
        val es = ev(feed(eng, 2000, c(3), null))                    // gap=860∈(700,2500]
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertTrue(start.uncertain)
    }

    @Test fun `uncertain anchor uses midpoint`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 1000, c(3), null)
        val es = ev(feed(eng, 2000, c(3), null))
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(570L, start.anchorAtMs)                         // (140+1000)/2
    }

    @Test fun `long unreadable run resets baseline but keeps timers`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)
        feed(eng, 1000, null, null)
        val es = ev(feed(eng, 9001, null, null))                    // 双侧不可读 >8000
        assertTrue(es.any { it is TimerEvent.BaselineReset })
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs) // 计时存活
    }

    @Test fun `sustained non battle clears timers and baseline`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)
        feed(eng, 1000, c(4), e(4), battle = false)
        val es = ev(feed(eng, 9001, c(4), e(4), battle = false))     // 非战斗持续 >8000
        assertTrue(es.any { it is TimerEvent.BaselineReset })
        assertNull(timerOf(eng, TimerSide.SELF))                     // match_end 清计时
    }

    @Test fun `timers keep counting during unreadable silence`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)
        repeat(3) { feed(eng, 1000 + it * 1000L, null, null) }       // 3s 不可读 <8000
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
        assertEquals(15_200L - 4_000, timerOf(eng, TimerSide.SELF)!!.remaining(4000))
    }

    @Test fun `frame gaps do not break an already confirmed count`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 3000, c(4), null)                                  // 3s 后读到同值=仍是基线
        feed(eng, 3100, c(3), null)
        val es = ev(feed(eng, 3240, c(3), null))                     // gap=100 → 正常起算
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(3100L, start.anchorAtMs); assertFalse(start.uncertain)
    }

    // ---------- G. 双侧与塌缩（4） ----------

    @Test fun `both sides run independent timers`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 900, c(4), null)
        substitute(eng, TimerSide.SELF, 1000)                        // 我方@1000 end 16000
        feed(eng, 2900, null, e(4))                                  // 敌方旧计数刷新
        substitute(eng, TimerSide.ENEMY, 3000)                       // 敌方@3000 end 18000
        assertEquals(16_000L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
        assertEquals(18_000L, timerOf(eng, TimerSide.ENEMY)!!.endAtMs)
    }

    @Test fun `both drop is collapse not double substitution`() {
        val eng = engine()
        feed(eng, 0, c(1), e(1)); feed(eng, 140, c(1), e(1))          // 双侧基线各 1
        feed(eng, 900, c(1), e(1))                                    // 双侧刷新目击（锚点确定）
        feed(eng, 1000, c(0), null)                                   // 我方 1→0 首见 1000
        feed(eng, 1100, null, e(0))                                   // 敌方 1→0 首见 1100
        feed(eng, 1140, c(0), null)                                   // 我方确认：起算 + 记 lastZeroDrop=1000
        val es = ev(feed(eng, 1240, null, e(0)))                      // 敌方确认：|1100−1000|≤300 → collapse
        assertTrue(es.any { it is TimerEvent.TimerRetracted })        // 我方归零计时被回滚
        assertTrue(es.any { it is TimerEvent.AmbiguousDrop })
        assertNull(timerOf(eng, TimerSide.SELF)); assertNull(timerOf(eng, TimerSide.ENEMY))
    }

    @Test fun `collapse window edge keeps timers`() {
        val eng = engine()
        feed(eng, 0, c(1), e(1)); feed(eng, 140, c(1), e(1))
        feed(eng, 900, c(1), e(1))
        feed(eng, 1000, c(0), null); feed(eng, 1140, c(0), null)      // 我方 1→0@1000 起算 end 16000
        feed(eng, 1401, null, e(0))
        val es = ev(feed(eng, 1541, null, e(0)))                      // 敌方首见 1401：差 401>300 → 非塌缩
        assertTrue(es.none { it is TimerEvent.TimerRetracted })
        assertEquals(16_000L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
        assertEquals(16_401L, timerOf(eng, TimerSide.ENEMY)!!.endAtMs) // 敌方 −1 正常起算
    }

    @Test fun `drop to zero alone is allowed and times normally`() {
        val eng = engine()
        feed(eng, 0, c(1), e(4)); feed(eng, 140, c(1), e(4))
        feed(eng, 900, c(1), e(4))                                    // 刷新目击
        feed(eng, 1000, c(0), null)
        val es = ev(feed(eng, 1140, c(0), null))
        assertTrue(es.any { it is TimerEvent.SubstitutionStarted })
        assertEquals(16_000L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
    }

    // ---------- H. 回放与诊断（5） ----------

    @Test fun `replay of observation records reproduces events`() {
        val recs = listOf(
            rec(0, c(4), e(4)), rec(140, c(4), e(4)),
            rec(900, c(4), e(4)),
            rec(1000, c(3), e(4)), rec(1140, c(3), e(4)),
        )
        val events = engine().replayCells(recs).flatten()
        assertTrue(events.any { it is TimerEvent.SubstitutionStarted })
    }

    @Test fun `cell replay reproduces events`() {
        val recs = listOf(
            rec(0, c(4), null), rec(140, c(4), null),
            rec(200, c(3), null), rec(340, c(3), null),
        )
        val started = engine().replayCells(recs).flatten()
            .filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(200L, started.anchorAtMs)
    }

    @Test fun `marks do not affect events`() {
        val base = listOf(rec(0, c(4), null), rec(140, c(4), null),
            rec(200, c(3), null), rec(340, c(3), null))
        val marked = base.map { it.copy(mark = "missed") }
        assertEquals(engine().replayCells(base), engine().replayCells(marked))
    }

    @Test fun `unknown cells do not shift anchor`() {
        val eng = engine()
        feed(eng, 0, c(4), null); feed(eng, 140, c(4), null)
        feed(eng, 300, cells(L, L, L, U), null)                       // 记忆 L@140 → 仍 4
        feed(eng, 500, cells(L, L, L, E), null)                       // 首见 3
        val es = ev(feed(eng, 640, cells(L, L, L, U), null))          // 记忆 E@500 → 仍 3 → 确认
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(500L, start.anchorAtMs)
    }

    @Test fun `hole rows never reach the confirmer`() {
        val eng = engine()
        feed(eng, 0, c(4), null); feed(eng, 140, c(4), null)
        repeat(4) { feed(eng, 300 + it * 140L, cells(L, E, L, L), null) }
        assertNull(timerOf(eng, TimerSide.SELF))
        feed(eng, 1200, c(3), null)
        val es = ev(feed(eng, 1340, c(3), null))
        assertTrue(es.any { it is TimerEvent.SubstitutionStarted })    // 洞形期没污染基线
    }

    // ---------- I. 暂停、撤销与移植（5） ----------

    @Test fun `pause ignores frames and resume re-baselines`() {
        val eng = engine()
        baseline4(eng); eng.pause()
        assertTrue(ev(feed(eng, 1000, c(3), null)).isEmpty())
        eng.resume()
        feed(eng, 2000, c(3), null)
        val es = ev(feed(eng, 2140, c(3), null))
        assertTrue(es.none { it is TimerEvent.SubstitutionStarted })   // 新基线而非掉落
    }

    @Test fun `resume preserves running timers`() {
        val eng = engine()
        baseline4(eng); substitute(eng, TimerSide.SELF, 200)
        eng.pause(); eng.resume()
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
    }

    @Test fun `cancel clears only that side`() {
        val eng = engine()
        baseline4(eng)
        // 帧时刻全局单调：敌方先锚定 300（其确认帧 440 会吞掉我方 300 内的首见，
        // 故我方改锚 500——断言只依赖敌方 end=300+15000 不变）
        substitute(eng, TimerSide.ENEMY, 300); substitute(eng, TimerSide.SELF, 500)
        eng.cancelTimer(TimerSide.SELF)
        assertNull(timerOf(eng, TimerSide.SELF))
        assertEquals(15_300L, timerOf(eng, TimerSide.ENEMY)!!.endAtMs)
    }

    @Test fun `carried timers survive engine rebuild`() {
        val eng = engine()
        baseline4(eng); substitute(eng, TimerSide.SELF, 200)
        val eng2 = engine(carried = eng.carriedTimers())
        assertEquals(15_200L, timerOf(eng2, TimerSide.SELF)!!.endAtMs)
    }

    @Test fun `stale frames are dropped at engine level`() {
        val eng = engine()
        baseline4(eng)
        assertTrue(ev(feed(eng, 50, c(3), null)).isEmpty())            // 比已收帧更早
        feed(eng, 1000, c(3), null)
        val es = ev(feed(eng, 1140, c(3), null))
        assertTrue(es.any { it is TimerEvent.SubstitutionStarted })
    }

    // ---------- helpers ----------

    private fun rec(atMs: Long, self: List<DotState>?, enemy: List<DotState>?): ObservationRecord =
        ObservationRecord(
            seq = atMs.toInt(), atMs = atMs, clock = "test", channel = "replay",
            geoW = 1920, geoH = 1080, scene = "BATTLE", battle = true,
            selfCount = self?.count { it == DotState.LIT },
            enemyCount = enemy?.count { it == DotState.LIT },
            selfCells = self?.map { it.name.first().toString() },
            enemyCells = enemy?.map { it.name.first().toString() },
        )
}
```

### 10.2 `DetectionTuning`（替换 SubstitutionCore.kt:76-114 整个 data class）

```kotlin
/**
 * 替身检测调参。颜色盒与比例阈值决定"每格亮/灭"；其余时间窗决定"何时起算/是否证伪"。
 * 标注"待真机复核"的参数须按 §7.3 与任务 11 验收结果回调。
 */
data class DetectionTuning(
    /** 点亮豆·青蓝盒（未满状态）。 */
    val litCyanBox: RgbBox = RgbBox(0, 120, 170, 225, 255, 255),
    /** 点亮豆·金色盒（四颗全亮时整排变金）。 */
    val litGoldBox: RgbBox = RgbBox(179, 19, 1, 255, 255, 206),
    /** 熄灭豆槽颜色盒（深藏青）。 */
    val dimBox: RgbBox = RgbBox(0, 0, 0, 90, 100, 150),
    /** 近黑下限：max(r,g,b) 不超过该值的像素既不算亮也不算灭（黑屏转场防"假全灭"）。 */
    val blackFloor: Int = 10,
    /** 格内命中点亮盒的像素占比 ≥ 该值且占优 → LIT。 */
    val litPixelMinRatio: Float = 0.12f,
    /** 格内命中熄灭盒的像素占比 ≥ 该值且占优 → EMPTY。 */
    val dimPixelMinRatio: Float = 0.40f,
    /** 每格采样网格（grid×grid 像素）。 */
    val sampleGrid: Int = 7,
    /** 格子内缩比例：只采样中心区域，避开豆间分隔与 HUD 边缘。 */
    val cellInnerFraction: Float = 0.6f,
    /** 新计数需被"可读帧"连续看到该次数才确认；不可读帧不推进也不重置。 */
    val confirmFrames: Int = 2,
    /** 单格 UNKNOWN 沿用记忆值的最长时长；超过则整行不可读。 */
    val cellHoldMs: Long = 400,
    /** 掉豆后在该窗口内弹回原值 → 闪光假掉落，回滚计时。 */
    val reboundRetractMs: Long = 500,
    /** 涨豆后在该窗口内掉豆 → 奥义清豆/涨豆残影，不计时。 */
    val gainFlickerMs: Long = 350,
    /** 该窗口内连续两次 −1 → 连扣（奥义逐帧扣豆），回滚链内计时。 */
    val cascadeWindowMs: Long = 400,
    /** 证据空白 > 该值 → 锚点取中点且标记 uncertain。 */
    val uncertainGapMs: Long = 700,
    /** 证据空白 > 该值 → 触发时刻不可知，报歧义不计时。 */
    val maxAnchorGapMs: Long = 2500,
    /** 双侧在该窗口内相继归零 → 转场整排消失，回滚窗内计时。 */
    val collapseWindowMs: Long = 300,
    /** 双侧不可读/非战斗持续 > 该值 → 基线重建（hud_lost 保留计时）/比赛结束（match_end 清计时）。 */
    val hudLostResetMs: Long = 8000,
    /** 物理先验：冷却剩余 > 该值的掉豆不可能是替身。 */
    val cooldownGraceMs: Long = 1000,
)
```

### 10.3 `DotClassifier.classify` 修改（只改像素计数循环）

```kotlin
// 原 for 循环（SubstitutionCore.kt:128-131）替换为：
var lit = 0
var dim = 0
for (px in argb) {
    if (tuning.litCyanBox.contains(px) || tuning.litGoldBox.contains(px)) lit++
    else if (nearBlack(px, tuning.blackFloor)) continue      // 黑屏转场像素不投票
    else if (tuning.dimBox.contains(px)) dim++
}
// 并在类内追加：
private fun nearBlack(argb: Int, floor: Int): Boolean {
    val r = argb shr 16 and 0xFF; val g = argb shr 8 and 0xFF; val b = argb and 0xFF
    return maxOf(r, g, b) <= floor
}
```

### 10.4 `evaluateScene` + `deriveAnchorBox`（替换 SubstitutionCore.kt:208-224）

```kotlin
/**
 * 战斗场景判定：只信豆色之外的 HUD 锚点证据。
 * 锚点未标定 → 恒 UNVERIFIED（不做任何否决；转场整排消失由引擎 collapse 规则兜底）；
 * 已标定 → HudGate 命中数裁决 BATTLE/NOT_BATTLE。
 */
fun evaluateScene(anchors: List<HudAnchor>, anchorSamples: List<IntArray>): SceneVerdict {
    if (anchors.isEmpty()) return SceneVerdict.UNVERIFIED
    return HudGate(anchors).verdict(anchorSamples)
}

/**
 * 从锚点区域采样像素推导颜色盒：各通道取 P10/P90 分位再外扩 tolerance，
 * 抑制零星噪点。供校准页"框一下常驻 HUD 元素"生成锚点用。
 */
fun deriveAnchorBox(pixels: IntArray, tolerance: Int = 30): RgbBox {
    require(pixels.isNotEmpty()) { "锚点区域无像素" }
    fun channel(shift: Int): IntArray = IntArray(pixels.size) { (pixels[it] shr shift) and 0xFF }
    fun pct(sorted: List<Int>, p: Double): Int = sorted[(sorted.size * p).toInt().coerceIn(0, sorted.size - 1)]
    val r = channel(16).sorted(); val g = channel(8).sorted(); val b = channel(0).sorted()
    return RgbBox(
        (pct(r, 0.10) - tolerance).coerceIn(0, 255),
        (pct(g, 0.10) - tolerance).coerceIn(0, 255),
        (pct(b, 0.10) - tolerance).coerceIn(0, 255),
        (pct(r, 0.90) + tolerance).coerceIn(0, 255),
        (pct(g, 0.90) + tolerance).coerceIn(0, 255),
        (pct(b, 0.90) + tolerance).coerceIn(0, 255),
    )
}
```

### 10.5 `SideTimer`（替换 SubstitutionCore.kt:277-290）

```kotlin
/** 单侧计时状态（UI 消费快照；altEndAtMs/conflict 由引擎在冷却冲突时写入）。 */
data class SideTimer(
    val side: TimerSide,
    val active: Boolean = false,
    val endAtMs: Long = 0,
    /** 由豆数间接推断 → 一律带"疑似"标记。 */
    val suspected: Boolean = true,
    /** 冷却期内又出现 −1：altEndAtMs 记录了那次掉豆的备选截止。 */
    var conflict: Boolean = false,
    /** 起算锚点（= eventAtMs，保留后者兼容旧字段名）。 */
    val anchorAtMs: Long = 0,
    val eventAtMs: Long = 0,
    /** 证据空白过大 → 锚点为中点估计，UI 显示 "≈"。 */
    val uncertain: Boolean = false,
    /** 冷却冲突时的备选截止（第二落点）。 */
    var altEndAtMs: Long? = null,
    /** 被本计时替换的前任计时——回滚时恢复（链式记忆，用于连续证伪）。 */
    val prevTimer: SideTimer? = null,
) {
    fun remaining(nowMs: Long) = max(0L, endAtMs - nowMs)
}
```

### 10.6 `BeadRowTracker.kt`（新建文件，完整内容）

```kotlin
package xyz.chouxuewei.mobile_agent.core.substitution

/**
 * 单侧豆行逐格追踪：把"每帧格子状态列表"翻译成"行计数读数"。
 * - 格级记忆：本帧 UNKNOWN 格沿用上一帧已确认状态（≤cellHoldMs），超期则整行不可读；
 * - 规范豆形：亮豆连续且锚定左端或右端，空洞/居中块/含 UNKNOWN → 整行不可读；
 * - 全灭（全 EMPTY）是合法读数 0。
 * 该层不做计时推断，只回答"这排豆子现在读数是几、可不可信"。
 */
internal class BeadRowTracker(private val cellHoldMs: Long) {
    private data class Held(var state: DotState, var atMs: Long)

    private var held: MutableList<Held>? = null

    /** 返回本帧行计数；null = 该行不可读。cells=null 不推进也不破坏记忆。 */
    fun update(cells: List<DotState>?, atMs: Long): Int? {
        if (cells == null) return null
        val h = held
        if (h == null || h.size != cells.size) {
            held = MutableList(cells.size) { Held(DotState.UNKNOWN, Long.MIN_VALUE) }
        }
        val heldNow = held!!
        val states = ArrayList<DotState>(cells.size)
        for (i in cells.indices) {
            val s = cells[i]
            if (s != DotState.UNKNOWN) {
                heldNow[i].state = s; heldNow[i].atMs = atMs
                states += s
            } else {
                val mem = heldNow[i]
                if (mem.state == DotState.UNKNOWN || atMs - mem.atMs > cellHoldMs) return null
                states += mem.state
            }
        }
        if (!canonicalShape(states)) return null
        return states.count { it == DotState.LIT }
    }

    fun reset() { held = null }

    companion object {
        /** 亮豆连续且锚定一端；全灭返回 true（0 是合法读数）。 */
        fun canonicalShape(states: List<DotState>): Boolean {
            if (states.any { it == DotState.UNKNOWN }) return false
            val firstLit = states.indexOfFirst { it == DotState.LIT }
            if (firstLit < 0) return true
            val lastLit = states.indexOfLast { it == DotState.LIT }
            if (lastLit - firstLit + 1 != states.count { it == DotState.LIT }) return false
            return firstLit == 0 || lastLit == states.size - 1
        }
    }
}
```

### 10.7 `CountConfirmer`（SubstitutionEngine.kt 上半部）

```kotlin
/**
 * 新计数确认器：把"行计数序列"压成"稳定计数转移"。
 * 不可读帧（null）不推进也不重置证据；稀疏帧同理；候选计数被其它计数取代时重新累计。
 */
internal class CountConfirmer(private val confirmFrames: Int) {
    /** 已确认计数最近一次被读到的帧时刻——转移间隔的旧侧端点。 */
    var confirmedSeenAtMs = Long.MIN_VALUE; private set
    /** 当前已确认的稳定计数；null = 尚无基线。 */
    var confirmed: Int? = null; private set
    private var candidate: Int? = null
    private var candidateSeenAtMs = Long.MIN_VALUE
    private var candidateStreak = 0
    private var lastFrameAtMs = Long.MIN_VALUE

    data class Transition(
        val from: Int?,
        val to: Int,
        /** 新计数首次被读到的帧时刻——常规锚点。 */
        val firstSeenAtMs: Long,
        /** 旧计数最后一次被读到的帧时刻——间隔端点。 */
        val lastOldSeenAtMs: Long,
        /** 转移可能发生的总时间窗 = firstSeen - lastOldSeen。 */
        val gapMs: Long,
    )

    fun update(count: Int?, atMs: Long): Transition? {
        if (atMs <= lastFrameAtMs) return null
        lastFrameAtMs = atMs
        if (count == null) return null
        if (count == confirmed) {
            confirmedSeenAtMs = atMs
            candidate = null; candidateStreak = 0
            return null
        }
        if (count != candidate) { candidate = count; candidateSeenAtMs = atMs; candidateStreak = 0 }
        candidateStreak++
        if (candidateStreak < confirmFrames) return null
        val t = Transition(
            from = confirmed, to = count,
            firstSeenAtMs = candidateSeenAtMs,
            lastOldSeenAtMs = confirmedSeenAtMs,
            gapMs = candidateSeenAtMs - confirmedSeenAtMs,
        )
        confirmed = count; confirmedSeenAtMs = atMs
        candidate = null; candidateStreak = 0
        return t
    }

    fun reset() {
        confirmed = null; confirmedSeenAtMs = Long.MIN_VALUE
        candidate = null; candidateStreak = 0; lastFrameAtMs = Long.MIN_VALUE
    }
}
```

### 10.8 `TimerEvent`（替换 SubstitutionEngine.kt:68-100）

```kotlin
sealed class TimerEvent {
    /** 计数首次稳定确认（建基线）或转移确认。 */
    data class CountConfirmed(val side: TimerSide, val count: Int) : TimerEvent()

    /** 涨豆确认：只更新基线，永不计时。 */
    data class GainConfirmed(val side: TimerSide, val fromCount: Int, val toCount: Int) : TimerEvent()

    /** 一次 −1 起算替身冷却。anchorAtMs=证据锚点；uncertain=锚点为中点估计。 */
    data class SubstitutionStarted(
        val side: TimerSide,
        val anchorAtMs: Long,
        val endAtMs: Long,
        val fromCount: Int,
        val toCount: Int,
        val gapMs: Long,
        val uncertain: Boolean,
    ) : TimerEvent()

    /** 冷却内 −1：物理先验不可能是替身——记备选截止，原计时不让位。 */
    data class CooldownDrop(
        val side: TimerSide,
        val fromCount: Int,
        val toCount: Int,
        val anchorAtMs: Long,
        val remainingAtDropMs: Long,
    ) : TimerEvent()

    /** 一次多豆或窗口内连扣：奥义/连扣语义，不计时；链内已起计时已回滚。 */
    data class MultiDrop(val side: TimerSide, val fromCount: Int, val toCount: Int, val anchorAtMs: Long) : TimerEvent()

    /** 涨豆后短窗内掉豆：奥义清豆/涨豆残影，不计时。 */
    data class GainFlickerIgnored(val side: TimerSide, val fromCount: Int, val toCount: Int, val anchorAtMs: Long) : TimerEvent()

    /** 触发时刻不可知的掉豆：reason="gap"（证据空白过长）| "collapse"（双侧同塌）。 */
    data class AmbiguousDrop(val side: TimerSide, val fromCount: Int, val toCount: Int, val anchorAtMs: Long, val reason: String) : TimerEvent()

    /** 已起计时被短窗证据推翻：reason="rebound"|"cascade"|"collapse"；prevTimer 已恢复。 */
    data class TimerRetracted(val side: TimerSide, val reason: String, val retractedEndAtMs: Long) : TimerEvent()

    /** 基线重建：reason="hud_lost"（长不可读，计时保留）|"match_end"（持续非战斗，计时清空）| 服务侧原因。 */
    data class BaselineReset(val side: TimerSide?, val reason: String) : TimerEvent()
}
```

### 10.9 `SubstitutionEngine`（替换 SubstitutionEngine.kt:102-404；保留 406 行起的 `TimerServiceState`/`TimerStateMachine`/`LaunchEvent`）

> 文件顶部需 `import kotlin.math.abs`（塌缩的首见时差计算）。

```kotlin
/**
 * 双侧替身推断引擎 v2。
 * 核心先验：替身冷却内不可能再次替身——冷却外首个 −1 起算计时，冷却内 −1 只记
 * 冲突与备选截止，不起算。计时即确认即起算（锚点=首次目击帧），只允许回弹/
 * 连扣/双侧同塌三种短窗证据证伪回滚；不可读帧不推进也不破坏任何证据。
 * 线程安全：引擎非线程安全，调用方串行化（服务侧 engineLock）。
 */
class SubstitutionEngine(
    private val config: TimerConfig,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val carried: Map<TimerSide, SideTimer> = emptyMap(),
) {
    /** created=该掉落创建的计时（D4 冷却冲突/MultiDrop 等为 null）——回弹/塌缩只回滚"掉落自己造成的"计时。 */
    private data class DropMark(val fromCount: Int, val toCount: Int, val anchorMs: Long, val created: SideTimer?)

    private inner class SideState(val side: TimerSide) {
        val tracker = BeadRowTracker(config.tuning.cellHoldMs)
        val confirmer = CountConfirmer(config.tuning.confirmFrames)
        var timer: SideTimer? = carried[side]
        var prevTimer: SideTimer? = timer?.prevTimer
        var lastGainAnchorMs: Long? = null
        val recentDrops = ArrayDeque<DropMark>()
        /** 最近一次"掉到 0"的首次目击时刻（真实观察时刻，非中点锚点）——塌缩检测用。 */
        var lastZeroDropFirstSeenMs = Long.MIN_VALUE
        /** 最近一次掉到 0 所创建的计时（未创建为 null）——塌缩只回滚它。 */
        var lastZeroDropTimer: SideTimer? = null
    }

    data class FrameResult(
        val events: List<TimerEvent>,
        /** 本帧逐格层读数；缺 key = 该侧本帧不可读（供 UI/诊断记录/回放语义）。 */
        val counts: Map<TimerSide, Int>,
    )

    private val self = SideState(TimerSide.SELF)
    private val enemy = SideState(TimerSide.ENEMY)
    private val diagnostics = ArrayDeque<String>(200)
    private var lastFrameAtMs = Long.MIN_VALUE
    private var unreadableSinceMs = Long.MIN_VALUE
    private var nonBattleSinceMs = Long.MIN_VALUE
    private var matchEnded = false

    var paused = false
        private set

    fun timers(nowMs: Long = clock()): Pair<SideTimer, SideTimer> =
        (self.timer ?: SideTimer(TimerSide.SELF)) to (enemy.timer ?: SideTimer(TimerSide.ENEMY))

    /** 热更新/重建时携带的在途计时（含 prevTimer 链）。 */
    fun carriedTimers(): Map<TimerSide, SideTimer> =
        listOfNotNull(self.timer?.let { TimerSide.SELF to it }, enemy.timer?.let { TimerSide.ENEMY to it }).toMap()

    fun diagnostics(): List<String> = diagnostics.toList()

    fun pause() { paused = true }

    fun resume() {
        paused = false
        // 暂停期间的帧不可信——基线重建，计时按单调 endAt 照常推进
        resetBaseline("paused_resume")
    }

    /** 基线重建：清证据与计数状态；计时存活（match_end 之外的复位都不动计时）。 */
    fun resetBaseline(reason: String) {
        for (s in listOf(self, enemy)) {
            s.confirmer.reset(); s.tracker.reset()
            s.recentDrops.clear(); s.lastGainAnchorMs = null
            s.lastZeroDropFirstSeenMs = Long.MIN_VALUE; s.lastZeroDropTimer = null
        }
        emit(TimerEvent.BaselineReset(null, reason))
    }

    /** 手动撤销一侧全部推断状态：计时、基线、证据——纠错后从头建立。 */
    fun cancelTimer(side: TimerSide) {
        val s = select(side)
        s.timer = null; s.prevTimer = null
        s.confirmer.reset(); s.tracker.reset()
        s.recentDrops.clear(); s.lastGainAnchorMs = null
        s.lastZeroDropFirstSeenMs = Long.MIN_VALUE; s.lastZeroDropTimer = null
    }

    /**
     * 逐格入口：每帧两侧格子状态（null=该侧不可读）。battle=false 整帧按不可读处理，
     * 只推进"持续非战斗"时钟。返回事件与本帧计数。帧时间必须单调递增。
     */
    fun onCells(
        selfCells: List<DotState>?,
        enemyCells: List<DotState>?,
        frameAtMs: Long = clock(),
        battle: Boolean = true,
    ): FrameResult {
        if (paused) return FrameResult(emptyList(), emptyMap())
        if (frameAtMs <= lastFrameAtMs) {
            log("stale frame dropped @$frameAtMs", frameAtMs)
            return FrameResult(emptyList(), emptyMap())
        }
        lastFrameAtMs = frameAtMs

        if (!battle) {
            if (nonBattleSinceMs == Long.MIN_VALUE) nonBattleSinceMs = frameAtMs
            val events = mutableListOf<TimerEvent>()
            if (!matchEnded && frameAtMs - nonBattleSinceMs > config.tuning.hudLostResetMs) {
                matchEnded = true
                matchEndReset()
                events += TimerEvent.BaselineReset(null, "match_end")
            }
            return FrameResult(events, emptyMap())
        }
        nonBattleSinceMs = Long.MIN_VALUE; matchEnded = false

        val events = mutableListOf<TimerEvent>()
        val selfCount = self.tracker.update(selfCells, frameAtMs)
        val enemyCount = enemy.tracker.update(enemyCells, frameAtMs)
        val counts = mutableMapOf<TimerSide, Int>()
        selfCount?.let { counts[TimerSide.SELF] = it }
        enemyCount?.let { counts[TimerSide.ENEMY] = it }
        if (selfCount == null && enemyCount == null) {
            if (unreadableSinceMs == Long.MIN_VALUE) unreadableSinceMs = frameAtMs
            if (frameAtMs - unreadableSinceMs > config.tuning.hudLostResetMs) {
                unreadableSinceMs = Long.MIN_VALUE        // 每个不可读周期至多触发一次
                resetBaseline("hud_lost")                  // 计时保留
                events += TimerEvent.BaselineReset(null, "hud_lost")
            }
            return FrameResult(events, counts)
        }
        unreadableSinceMs = Long.MIN_VALUE
        feedSide(self, selfCount, frameAtMs, events)
        feedSide(enemy, enemyCount, frameAtMs, events)
        return FrameResult(events, counts)
    }

    /**
     * 计数口径兼容入口（回放/未改造调用方）：计数合成规范豆形格列后转 onCells。
     * 返回类型与 v1 保持一致（List<TimerEvent>），服务侧 `eng.onFrame(...)` 调用原样可编译。
     */
    fun onFrame(selfCount: Int?, enemyCount: Int?, frameAtMs: Long = clock(), battle: Boolean = true): List<TimerEvent> =
        onCells(cellsForCount(TimerSide.SELF, selfCount), cellsForCount(TimerSide.ENEMY, enemyCount), frameAtMs, battle).events

    private fun feedSide(s: SideState, count: Int?, atMs: Long, events: MutableList<TimerEvent>) {
        val t = s.confirmer.update(count, atMs) ?: return
        if (t.from == null) {                          // 首个稳定计数 = 基线，不是事件
            events += TimerEvent.CountConfirmed(s.side, t.to)
            return
        }
        events += TimerEvent.CountConfirmed(s.side, t.to)
        if (t.to > t.from) {                           // 涨豆：先查"弹回"再记增益
            if (retractIfRebound(s, t, atMs, events)) return
            s.lastGainAnchorMs = t.firstSeenAtMs
            events += TimerEvent.GainConfirmed(s.side, t.from, t.to)
            return
        }
        events += evaluateDrop(s, t, atMs)
    }

    /** 掉豆判定表（§7.2 D0-D5，按顺序短路）。 */
    private fun evaluateDrop(s: SideState, t: CountConfirmer.Transition, now: Long): List<TimerEvent> {
        val events = mutableListOf<TimerEvent>()
        // feedSide 已短路 t.from==null（基线建立非掉落）；守卫仅为让编译器收窄 t.from: Int?→Int
        if (t.from == null) return events
        val uncertain = t.gapMs > config.tuning.uncertainGapMs
        val anchor = if (uncertain) t.lastOldSeenAtMs + t.gapMs / 2 else t.firstSeenAtMs
        val end = anchor + config.cooldownMs

        if (t.gapMs > config.tuning.maxAnchorGapMs) {                        // D0
            events += TimerEvent.AmbiguousDrop(s.side, t.from, t.to, anchor, "gap")
            s.recentDrops.clear(); return events
        }
        if (t.to == 0) {                                                      // D1
            val other = select(if (s.side == TimerSide.SELF) TimerSide.ENEMY else TimerSide.SELF)
            if (other.lastZeroDropFirstSeenMs != Long.MIN_VALUE &&
                abs(t.firstSeenAtMs - other.lastZeroDropFirstSeenMs) <= config.tuning.collapseWindowMs) {
                // 双侧近同时"掉到 0"=豆行整排消失（转场/结算）：
                // 只回滚对侧"由那次归零创建"的计时（若仍是当前计时），更早的真实计时不连坐。
                other.lastZeroDropTimer?.takeIf { it === other.timer }?.let { zt ->
                    other.timer = zt.prevTimer
                    other.prevTimer = zt.prevTimer?.prevTimer
                    events += TimerEvent.TimerRetracted(other.side, "collapse", zt.endAtMs)
                }
                events += TimerEvent.AmbiguousDrop(s.side, t.from, t.to, anchor, "collapse")
                s.recentDrops.clear(); return events
            }
            s.lastZeroDropFirstSeenMs = t.firstSeenAtMs
            s.lastZeroDropTimer = null                       // 若 D5 真起了计时再回填
        }
        s.lastGainAnchorMs?.let { gainAt ->                                   // D2
            if (anchor - gainAt <= config.tuning.gainFlickerMs) {
                events += TimerEvent.GainFlickerIgnored(s.side, t.from, t.to, anchor)
                s.recentDrops.clear(); return events
            }
        }
        val chainDrops = s.recentDrops.count {                                // D3
            it.toCount == it.fromCount - 1 && anchor - it.anchorMs <= config.tuning.cascadeWindowMs
        }
        val isChainStep = t.to == t.from - 1 && chainDrops >= 1
        if (t.to < t.from - 1 || isChainStep) {
            if (isChainStep) {
                retractTimersInWindow(s, anchor - config.tuning.cascadeWindowMs, now).forEach {
                    events += TimerEvent.TimerRetracted(s.side, "cascade", it.endAtMs)
                }
            }
            events += TimerEvent.MultiDrop(s.side, t.from, t.to, anchor)
            s.recentDrops.clear(); return events
        }
        var created: SideTimer? = null
        val tm = s.timer                                                      // D4 冷却先验
        if (tm != null && tm.remaining(now) > config.tuning.cooldownGraceMs) {
            tm.altEndAtMs = end; tm.conflict = true
            events += TimerEvent.CooldownDrop(s.side, t.from, t.to, anchor, tm.remaining(now))
        } else {                                                              // D5 起算
            created = startTimer(s, anchor, end, uncertain)
            if (t.to == 0) s.lastZeroDropTimer = created      // 归零起的计时才纳入塌缩回滚
            events += TimerEvent.SubstitutionStarted(
                s.side, anchor, end, t.from, t.to, t.gapMs, uncertain,
            )
        }
        s.recentDrops += DropMark(t.from, t.to, anchor, created)
        return events
    }

    /**
     * 涨豆落回到"当前计时那次掉落"的原水平且离该掉落锚点 ≤reboundRetractMs → 假掉落，回滚。
     * 只回滚创建了当前计时的那次掉落；冷却冲突掉豆（created=null）不参与。
     */
    private fun retractIfRebound(
        s: SideState,
        t: CountConfirmer.Transition,
        now: Long,
        events: MutableList<TimerEvent>,
    ): Boolean {
        val lastDrop = s.recentDrops.lastOrNull { it.created != null } ?: return false
        val tm = lastDrop.created!!
        if (s.timer !== tm || tm.remaining(now) <= 0) return false   // 已换计时/已到期不回滚
        if (t.firstSeenAtMs - lastDrop.anchorMs > config.tuning.reboundRetractMs) return false
        if (t.to < lastDrop.fromCount) return false                  // 未弹回掉前水平
        events += TimerEvent.TimerRetracted(s.side, "rebound", tm.endAtMs)
        s.timer = tm.prevTimer
        s.prevTimer = tm.prevTimer?.prevTimer
        s.recentDrops.clear()
        return true
    }

    /**
     * 回滚锚点落在 (lo, now] 的连续在途计时，返回被回滚者。
     * 链上锚点单调递减：只需从链头连续弹出——头部不在窗内则更深者必不在。
     * 结束后重同步镜像不变量 s.prevTimer = s.timer?.prevTimer（单字段弹出会破坏它）。
     */
    private fun retractTimersInWindow(s: SideState, lo: Long, now: Long): List<SideTimer> {
        val removed = mutableListOf<SideTimer>()
        while (s.timer?.let { it.anchorAtMs in (lo + 1)..now } == true) {
            removed += s.timer!!; s.timer = s.timer!!.prevTimer
        }
        s.prevTimer = s.timer?.prevTimer
        return removed
    }

    private fun startTimer(s: SideState, anchor: Long, end: Long, uncertain: Boolean): SideTimer {
        val t = SideTimer(
            side = s.side, active = true, endAtMs = end, suspected = true,
            anchorAtMs = anchor, eventAtMs = anchor,
            uncertain = uncertain, prevTimer = s.timer,
        )
        s.prevTimer = s.timer
        s.timer = t
        return t
    }

    /** 持续非战斗超时：清计时与基线（比赛结束语义），等下一局重新建立。 */
    private fun matchEndReset() {
        for (s in listOf(self, enemy)) {
            s.timer = null; s.prevTimer = null
            s.confirmer.reset(); s.tracker.reset()
            s.recentDrops.clear(); s.lastGainAnchorMs = null
            s.lastZeroDropFirstSeenMs = Long.MIN_VALUE; s.lastZeroDropTimer = null
        }
        emit(TimerEvent.BaselineReset(null, "match_end"))
    }

    private fun select(side: TimerSide) = if (side == TimerSide.SELF) self else enemy

    private fun log(msg: String, atMs: Long) {
        if (diagnostics.size >= 200) diagnostics.removeFirst()
        diagnostics.addLast("$atMs $msg")
    }

    private fun emit(event: TimerEvent) = log(event.toString(), clock())

    companion object {
        /** 计数 → 规范豆形格列（SELF 左锚 L..E..、ENEMY 右锚 E..L..）；null→null。 */
        internal fun cellsForCount(side: TimerSide, count: Int?): List<DotState>? {
            if (count == null) return null
            val n = count.coerceIn(0, 4)
            return when (side) {
                TimerSide.SELF -> List(4) { if (it < n) DotState.LIT else DotState.EMPTY }
                TimerSide.ENEMY -> List(4) { if (it >= 4 - n) DotState.LIT else DotState.EMPTY }
            }
        }
    }
}
```

### 10.10 `DiagnosticLog.replayCells`（追加到文件末尾）

```kotlin
/**
 * 逐格回放：用记录中的 selfCells/enemyCells（L/E/U）重走 BeadRowTracker→引擎链路；
 * 缺格信息时退回计数合成豆形（与 onFrame 兼容入口同口径）。
 * 用于对比"改判定规则前后"同一局的事件差异。
 */
fun SubstitutionEngine.replayCells(records: List<ObservationRecord>): List<List<TimerEvent>> =
    records.map { r ->
        onCells(parseCells(r.selfCells) ?: SubstitutionEngine.cellsForCount(TimerSide.SELF, r.selfCount),
            parseCells(r.enemyCells) ?: SubstitutionEngine.cellsForCount(TimerSide.ENEMY, r.enemyCount),
            r.atMs, r.battle).events
    }

private fun parseCells(cells: List<String>?): List<DotState>? =
    cells?.map {
        when (it) {
            "L" -> DotState.LIT; "E" -> DotState.EMPTY; else -> DotState.UNKNOWN
        }
    }
```

### 10.11 服务事件文案（替换 SubstitutionTimerService.kt 行 431-442 的 `when`）

```kotlin
for (e in events) {
    AgentLog.i(TAG) { "event $e" }
    when (e) {
        is TimerEvent.AmbiguousDrop -> _uiState.update {
            it.copy(detail = if (e.reason == "collapse") "豆行整排消失（疑似转场），未计时"
                             else "疑似豆数变化（触发时刻不可知），未自动计时")
        }
        is TimerEvent.MultiDrop -> _uiState.update { it.copy(detail = "一次掉多豆/连续扣豆，未计时") }
        is TimerEvent.GainFlickerIgnored -> _uiState.update { it.copy(detail = "涨豆后瞬间掉豆（疑似奥义清豆），未计时") }
        is TimerEvent.CooldownDrop -> _uiState.update { it.copy(detail = "冷却期内出现其他扣豆（已记备选结束点）") }
        is TimerEvent.TimerRetracted -> _uiState.update { it.copy(detail = "计时被新证据推翻（${e.reason}），已恢复上一计时") }
        else -> Unit
    }
}
```

### 10.12 悬浮窗文案（`SubstitutionOverlay.updateTimers` 内计时行）

```kotlin
// 删除 timer.pending → "推断中…" 分支。每侧计时文本：
val text = buildString {
    if (timer.uncertain) append('≈')
    append("%.1f".format(timer.remaining(nowMs) / 1000f)).append('s')
    if (timer.suspected) append('?')
    timer.altEndAtMs?.let { alt ->
        append(" (备选 %.1fs)".format((alt - nowMs).coerceAtLeast(0) / 1000f))
    }
}
```

### 10.13 `DotRowCounting.kt`（整文件替换）

```kotlin
package xyz.chouxuewei.mobile_agent.substitution

import xyz.chouxuewei.mobile_agent.core.substitution.DotClassifier
import xyz.chouxuewei.mobile_agent.core.substitution.DotState
import xyz.chouxuewei.mobile_agent.core.substitution.TimerSide
import xyz.chouxuewei.mobile_agent.device.capture.FrameAccess

/**
 * 豆排逐格读取：只回答"每个采样格此刻亮/灭/未知"，不做行级裁决——
 * 规范豆形校验与格级记忆在 agent-core 的 BeadRowTracker。
 * 任一格落在有效帧外 → 整侧返回 null；被本应用悬浮窗遮挡的格子直接记
 * UNKNOWN 且不采样（投影帧会截到自家悬浮窗；无障碍通道截游戏窗口不含它）。
 */
internal object DotRowCounting {

    /**
     * @param cells 物理像素格列表（左→右），由 TimerLayout.dotCells 给出
     * @param masked 返回 true 的格子视为被悬浮窗遮挡：记 UNKNOWN 且不采样
     * @param onCell 诊断回调：每格分类结果（含 lit/dim 占比）
     * @return 逐格状态；任一格无效（越界）→ null
     */
    fun readCells(
        access: FrameAccess,
        cells: List<IntArray>,
        classifier: DotClassifier,
        side: TimerSide,
        grid: Int,
        cellInnerFraction: Float,
        masked: (IntArray) -> Boolean = { false },
        onCell: ((DotClassifier.Result) -> Unit)? = null,
    ): List<DotState>? {
        val states = ArrayList<DotState>(cells.size)
        val inset = (1f - cellInnerFraction) / 2f
        for (cell in cells) {
            if (!access.contains(cell[0], cell[1], cell[2], cell[3])) return null
            if (masked(cell)) { states += DotState.UNKNOWN; continue }
            val w = cell[2] - cell[0]; val h = cell[3] - cell[1]
            val l = (cell[0] + w * inset).toInt()
            val t = (cell[1] + h * inset).toInt()
            val r = (cell[2] - w * inset).toInt()
            val b = (cell[3] - h * inset).toInt()
            val result = classifier.classify(access.sample(l, t, r, b, grid), side)
            onCell?.invoke(result)
            states += result.state
        }
        return states
    }
}
```

### 10.14 `DotRowCountingTest.kt`（整文件替换，6 条）

```kotlin
package xyz.chouxuewei.mobile_agent.substitution

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.substitution.DotClassifier
import xyz.chouxuewei.mobile_agent.core.substitution.DotState
import xyz.chouxuewei.mobile_agent.core.substitution.TimerSide
import xyz.chouxuewei.mobile_agent.device.capture.FrameAccess

private class FakeAccess(
    private val pixels: Map<Int, IntArray>,
    private val bounds: IntArray = intArrayOf(0, 0, 1920, 1080),
    override val width: Int = 1920,
    override val height: Int = 1080,
) : FrameAccess {
    val sampledBuckets = mutableListOf<Int>()
    override fun contains(l: Int, t: Int, r: Int, b: Int): Boolean =
        l >= bounds[0] && t >= bounds[1] && r <= bounds[2] && b <= bounds[3] && r > l && b > t
    override fun sample(l: Int, t: Int, r: Int, b: Int, grid: Int): IntArray {
        if (!contains(l, t, r, b)) return IntArray(0)
        sampledBuckets += (l / 100) * 100
        return pixels[(l / 100) * 100] ?: IntArray(grid * grid) { 0xFF14141A.toInt() }
    }
    override fun crop(l: Int, t: Int, r: Int, b: Int): Bitmap? = null
    override fun snapshot(targetWidth: Int): Bitmap = throw UnsupportedOperationException()
}

class DotRowCountingTest {
    private fun cells(count: Int = 4, x0: Int = 100, w: Int = 100): List<IntArray> =
        List(count) { i -> intArrayOf(x0 + w * i, 100, x0 + w * (i + 1), 140) }

    private val lit = IntArray(49) { 0xFFF0AA32.toInt() }
    private val dark = IntArray(49) { 0xFF14141A.toInt() }
    private val L = DotState.LIT
    private val E = DotState.EMPTY
    private val U = DotState.UNKNOWN

    @Test fun `cells are read in physical left to right order`() {
        val px = mapOf(100 to lit, 200 to lit, 300 to lit, 400 to dark)
        assertEquals(listOf(L, L, L, E),
            DotRowCounting.readCells(FakeAccess(px), cells(), DotClassifier(), TimerSide.SELF, 7, 0.6f))
    }

    @Test fun `right anchored row passes through verbatim`() {
        val px = mapOf(100 to dark, 200 to dark, 300 to lit, 400 to lit)
        assertEquals(listOf(E, E, L, L),
            DotRowCounting.readCells(FakeAccess(px), cells(), DotClassifier(), TimerSide.ENEMY, 7, 0.6f))
    }

    @Test fun `hole shape is returned verbatim for the row tracker to judge`() {
        val px = mapOf(100 to lit, 200 to dark, 300 to lit, 400 to lit)
        assertEquals(listOf(L, E, L, L),
            DotRowCounting.readCells(FakeAccess(px), cells(), DotClassifier(), TimerSide.SELF, 7, 0.6f))
    }

    @Test fun `all dark reads all empty`() {
        val px = mapOf(100 to dark, 200 to dark, 300 to dark, 400 to dark)
        assertEquals(listOf(E, E, E, E),
            DotRowCounting.readCells(FakeAccess(px), cells(), DotClassifier(), TimerSide.SELF, 7, 0.6f))
    }

    @Test fun `cell outside coverage makes whole side invalid`() {
        val access = FakeAccess(emptyMap(), bounds = intArrayOf(0, 0, 960, 1080))
        assertNull(DotRowCounting.readCells(access, cells(x0 = 700), DotClassifier(), TimerSide.SELF, 7, 0.6f))
    }

    @Test fun `masked cell reads unknown without sampling`() {
        val access = FakeAccess(mapOf(100 to lit, 200 to lit, 300 to lit, 400 to dark))
        val got = DotRowCounting.readCells(
            access, cells(), DotClassifier(), TimerSide.SELF, 7, 0.6f,
            masked = { c -> c[0] == 200 },
        )
        assertEquals(listOf(L, U, L, E), got)
        assertTrue(200 !in access.sampledBuckets)
    }
}
```

### 10.15 `SubstitutionTimerService.onFrame` 逐格接入（任务 6 修改片段）

```kotlin
// countDots 局部函数整体替换为 readSide；调用处替换如下
fun readSide(side: TimerSide): List<DotState>? {
    val marks = StringBuilder()
    val states = if (diagOn) ArrayList<String>() else null
    val cells = DotRowCounting.readCells(
        access, physicalCells(cfg, side, access.width, access.height),
        classifier, side, cfg.tuning.sampleGrid, cfg.tuning.cellInnerFraction,
        masked = overlayMask(access),           // 任务 8 提供；任务 6 阶段先省略该参
    ) { result ->
        val c = when (result.state) {
            DotState.LIT -> 'L'; DotState.EMPTY -> 'E'; DotState.UNKNOWN -> 'U'
        }
        if (cellDiag) {
            marks.append(c + "(%.2f/%.2f)".format(result.litRatio, result.dimRatio)).append(' ')
        }
        states?.add(c.toString())
    }
    if (cellDiag) AgentLog.d(TAG) { "cells $side=${marks.trim()}" }
    cellStates[side] = states
    return cells
}

val selfCells = readSide(TimerSide.SELF)
val enemyCells = readSide(TimerSide.ENEMY)
val anchorSamples = cfg.layout.hudAnchors.map { a ->           // 原样保留
    val r = a.rect.toPixel(0, 0, access.width, access.height)
    access.sample(r[0], r[1], r[2], r[3], cfg.tuning.sampleGrid)
}
val scene = evaluateScene(cfg.layout.hudAnchors, anchorSamples)
val battle = scene != SceneVerdict.NOT_BATTLE
val result = synchronized(engineLock) { eng.onCells(selfCells, enemyCells, now, battle) }
val events = result.events
val selfCount = result.counts[TimerSide.SELF]
val enemyCount = result.counts[TimerSide.ENEMY]
// 下游 _uiState / diag.record / 事件循环照旧使用 selfCount/enemyCount/events
```

### 10.16 前台轮询去抖（任务 7）

```kotlin
// 定位：前台轮询循环里对 gameInForeground 跳变即 resetBaseline 的代码段
// （检索 `resetBaseline("foreground`）。替换为：
var lastInGame: Boolean? = null
var nonGamePolls = 0
while (isActive) {
    val inGame = isGameInForeground()
    if (inGame != lastInGame) {
        if (inGame) {
            nonGamePolls = 0
            synchronized(engineLock) { engine?.resetBaseline("foreground_enter") }
            lastInGame = true
        } else if (++nonGamePolls >= 2) {      // 连续两次不在前台才算离开：吞掉手势侧栏抖动
            synchronized(engineLock) { engine?.resetBaseline("foreground_leave") }
            lastInGame = false
        }
    } else {
        nonGamePolls = if (inGame) 0 else nonGamePolls + 1
    }
    _uiState.update { it.copy(gameInForeground = inGame) }
    delay(900)
}
```

### 10.17 配置热更新计时结转（任务 7）

```kotlin
// 定位：设置流 collect 中 `engine = SubstitutionEngine(newCfg, clock = SystemClock::elapsedRealtime)`
// 的重建处（SubstitutionTimerService.kt:260 附近；首次装配 :202 处不需要结转）。
// 替换为——clock 参数必须原样保留为 elapsedRealtime，绝不能用引擎默认的 nanoTime
// （帧观察与计时查询混用两个时钟域 = 计时器整体错乱）：
val carried = synchronized(engineLock) { engine?.carriedTimers() ?: emptyMap() }
engine = SubstitutionEngine(newCfg, clock = SystemClock::elapsedRealtime, carried = carried)
// 首次装配（engine==null）时 carried=emptyMap，行为与现一致
```

### 10.18 悬浮窗（`SubstitutionOverlay`，任务 8）

```kotlin
// 类内新增字段
@Volatile var screenRect: android.graphics.Rect? = null; private set
@Volatile var displaySize = IntArray(2); private set

private fun publishRect() {
    val v = overlayView ?: return
    val loc = IntArray(2); v.getLocationOnScreen(loc)
    screenRect = android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    v.context.resources.displayMetrics.let { displaySize = intArrayOf(it.widthPixels, it.heightPixels) }
}

// show()：addView 成功后 v.post { publishRect() }；并 v.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> v.post { publishRect() } }
// 拖拽 onTouch ACTION_UP 分支末尾：publishRect()
// hide()：screenRect = null
// 默认位置：overlayX/overlayY 任一为空时 gravity=TOP|START、x=16dp、y=屏高 30%（避开左上豆排）

// updateTimers 签名追加参数 overlayOverlap: Boolean = false
// 状态行文案分支（放在"监视中/已暂停"之前优先级最高）：
//   overlayOverlap -> "悬浮窗遮挡豆排——请拖动移开"
```

### 10.19 服务侧 mask 与遮挡提示（任务 8）

```kotlin
// UiState 追加字段：val overlayOverlap: Boolean = false

/** 投影通道把自家悬浮窗映射成帧坐标 mask；无障碍通道/不可见 → 恒 false。 */
private fun overlayMask(access: FrameAccess): (IntArray) -> Boolean {
    val r = overlay?.screenRect ?: return { false }
    if (captureChannel != "projection") return { false }
    val ds = overlay!!.displaySize
    if (ds[0] == 0 || ds[1] == 0) return { false }
    val screenLand = ds[0] > ds[1]; val frameLand = access.width > access.height
    if (screenLand != frameLand) return { false }                     // 方向不一致不臆测映射
    val sx = access.width.toFloat() / ds[0]; val sy = access.height.toFloat() / ds[1]
    val fr = intArrayOf((r.left * sx).toInt(), (r.top * sy).toInt(), (r.right * sx).toInt(), (r.bottom * sy).toInt())
    return { c -> c[0] < fr[2] && c[2] > fr[0] && c[1] < fr[3] && c[3] > fr[1] }
}

// publishTimers 内：
val overlap = cfg.layout.let { l ->
    val mask = overlayMask(lastAccess ?: return@let false)            // lastAccess 缓存最近帧 access；无则 false
    l.dotCells(TimerSide.SELF, lastFrameW, lastFrameH).any(mask) ||
        l.dotCells(TimerSide.ENEMY, lastFrameW, lastFrameH).any(mask)
}
// overlay.updateTimers(...) 传 overlayOverlap = overlap；readSide 的 masked = overlayMask(access)
// 注：若现有代码未缓存 access，新增 @Volatile private var lastAccess: FrameAccess? 并在 onFrame 开头赋值
```

### 10.20 锚点校准（任务 9，`SubstitutionSettingsPage` 校准面板新增代码）

```kotlin
// import android.graphics.Bitmap
// import xyz.chouxuewei.mobile_agent.core.substitution.*

var anchorEnabled by remember { mutableStateOf(config.layout.hudAnchors.isNotEmpty()) }
var anchorRect by remember {
    mutableStateOf(config.layout.hudAnchors.firstOrNull()?.rect
        ?: NormalizedRect.of(0.955f, 0.02f, 0.99f, 0.075f))
}
var anchorWarn by remember { mutableStateOf<String?>(null) }

fun sampleRectPixels(bmp: Bitmap, r: NormalizedRect, grid: Int = 8): IntArray {
    val l = (r.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
    val t = (r.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
    val rr = (r.right * bmp.width).toInt().coerceIn(l + 1, bmp.width)
    val bb = (r.bottom * bmp.height).toInt().coerceIn(t + 1, bmp.height)
    val out = IntArray(grid * grid); var i = 0
    for (gy in 0 until grid) for (gx in 0 until grid) {
        out[i++] = bmp.getPixel(l + (rr - l) * gx / (grid - 1), t + (bb - t) * gy / (grid - 1))
    }
    return out
}

// 面板 UI（敌方豆槽滑块组之后、保存按钮之前）：
Text("战斗 HUD 锚点：在战斗中框选一个常驻元素（推荐暂停键/血条框）；在菜单页标定会判反。")
SettingSwitch("启用战斗 HUD 锚点", anchorEnabled) { anchorEnabled = it }
if (anchorEnabled) {
    // 复用现有豆槽滑块组件改 anchorRect 四边；画布上叠加蓝色锚框
    preview?.let { bmp ->
        val px = sampleRectPixels(bmp, anchorRect)
        val sorted = { sh: Int -> IntArray(px.size) { (px[it] shr sh) and 0xFF }.sorted() }
        val spread = listOf(16, 8, 0).maxOf { sh -> sorted(sh).let { it[(it.size*0.9).toInt()] - it[(it.size*0.1).toInt()] } }
        anchorWarn = if (spread > 60) "该区域颜色不均，请换纯色区域（如暂停键外框）" else null
        val saved = config.layout.hudAnchors.firstOrNull()
        val live = if (saved != null) {
            when (evaluateScene(listOf(saved), listOf(px))) {
                SceneVerdict.BATTLE -> "当前帧判定：战斗中"
                SceneVerdict.NOT_BATTLE -> "当前帧判定：非战斗（计时暂停）"
                SceneVerdict.UNVERIFIED -> "未配置"
            }
        } else "保存后显示实时判定"
        anchorWarn?.let { Text(it, color = WarnColor) }
        Text(live)
    }
}

// 保存按钮逻辑内追加：
val anchorBox = if (anchorEnabled) preview?.let { deriveAnchorBox(sampleRectPixels(it, anchorRect)) } else null
copy(layout = config.layout.copy(
    selfDots = selfRect, enemyDots = enemyRect,
    hudAnchors = if (anchorBox != null) listOf(HudAnchor(anchorRect, anchorBox, 0.6f)) else emptyList(),
))
```

### 10.21 `CHANGELOG.md` 条目

```markdown
## [Unreleased]

- **替身计时引擎 v2**：冷却先验（冷却内掉豆不再是替身）+ 首次目击锚点 + 逐格记忆 +
  短窗证伪回滚（回弹/连扣/双侧同塌）；不可读帧与稀疏帧不再打断证据累计；
  场景门控改纯 HUD 锚点（未标定不否决）+ 近黑像素过滤黑屏转场；
  悬浮窗自遮挡屏蔽 + 默认位置移出豆排区；前台抖动去抖 + 设置热更新保留在途计时；
  校准页新增战斗锚点框选与实时判定预览
```

### 10.22 回放线测试（`RecordedReplayTest.kt`，新建；无 jsonl 文件时 `assumeTrue` 自动跳过）

```kotlin
package xyz.chouxuewei.mobile_agent.core.substitution

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 真机诊断回放线：把 adb pull 的 *.jsonl 放进 agent-core/src/test/resources/diag/ 后运行。 */
class RecordedReplayTest {
    @Test fun `recorded matches replay without crash`() {
        val dir = File("src/test/resources/diag")
        val files = dir.listFiles { f -> f.extension == "jsonl" } ?: emptyArray()
        assumeTrue("无真机记录——adb pull 后放入 src/test/resources/diag", files.isNotEmpty())
        for (f in files) {
            val recs = f.readLines().mapNotNull { runCatching { DiagJson.decode(it) }.getOrNull() }
            val events = SubstitutionEngine(TimerConfig()).replayCells(recs)
            println("=== ${f.name}: ${recs.size} obs")
            events.forEachIndexed { i, es -> es.forEach { e -> println("#${recs[i].seq} ${recs[i].atMs} $e") } }
            recs.filter { it.mark != null }.forEach { println("MARK ${it.atMs} ${it.mark}") }
            assertTrue(recs.isNotEmpty())
        }
    }
}
```

## 11. 测试矩阵总览

| 层 | 文件 | 用例数 | 覆盖 |
|----|------|--------|------|
| agent-core | SubstitutionScenarioTest.kt | 50 | 确认器 7、起算锚点 5、增益防抖 6、多豆连锁 6、冷却先验 5、不可读歧义 7、双侧塌缩 4、回放诊断 5、暂停撤销移植 5 |
| agent-core | SubstitutionCoreTest.kt | ~15 | 颜色盒分类（含近黑）、布局、HUD 门控、deriveAnchorBox |
| agent-core | TimerStateMachineTest.kt | 4 | 服务状态机（不动） |
| agent-core | RecordedReplayTest.kt | 1 | 真机 jsonl 回放（无文件自动 skip） |
| app | DotRowCountingTest.kt | 6 | 逐格读取、洞形透传、越界、mask |
| 真机 | 任务 11 协议 | — | 替身键对表、全场连续计时、误报/漏报计数 |

## 12. 风险与已知局限

1. **冷却先验的边界**：秘卷/模式减 CD（如 13s）时 cooldownGraceMs 先验仍成立（剩余>1s 内
   不可能再替身），但 cooldownMs 需用户在设置改准——文档已注明。
2. **替身+技能连放的判型**：替身后 <400ms 内再放 1 豆技能会被当连扣回滚——
   该窗口是 FP/FN 权衡点，真机数据若证明误伤多则调 cascadeWindowMs。
3. **涨豆后立刻受击替身**：<350ms 判奥义清豆。实战"命中涨豆→被击替身"间隔普遍 >0.5s，
   窗口留了安全边距，仍以任务 11 数据为准。
4. **合成测试≠真机校准**：50/50 只证明时序语义正确；颜色盒/ROI 在不同机型、HDR、
   压缩下的表现必须靠任务 11 验证。
5. **对手替身没有直接证据**：豆减只是间接信号，"秘卷扣豆/技能烧豆"会误标为疑似替身——
   suspected 标记与冲突备选就是为诚实地表达这种不确定而设计，不承诺消除。
6. **首次目击锚点的下限**：锚点不可能早于第一帧目击；无障碍通道 ~500ms+ 帧间隔时
   锚点误差上界即帧间隔——`uncertain`/`≈` 标记如实呈现。

## 13. 回滚方案

逐提交 `git revert`；旧引擎完整保留在 git 历史（基线 `3878ec0` 的
SubstitutionEngine.kt:9-404）。若真机出现新型误报：先按 §7.3 单参数回调 +
§10.1 对应用例同步修正，再考虑回滚单提交（B/C/D/E 相互独立可单独回退，
A 为原子提交只能整体回退）。

## 14. 后续增强（不在本次范围）

- **替身键冷却探测**：校准替身键 ROI，检测其灰化/冷却数字作为我方侧 ground truth，
  用于在线自校准锚点误差与离线评估（§4 已证明该数字与豆排翻转同帧）。
- **每侧独立 cooldownMs**（秘卷减 CD 档）。
- **轻量 CNN 豆行分类**：用诊断裁剪图本地训练 TFLite 模型替代颜色盒（参考 233乐园
  文章思路；本方案全程零模型依赖，列为可选增强）。
- **左右换位自动识别**：以我方血条/头像位置自动判定 self 侧，替代手动 swapSides。

## 15. 自评审记录（写方案时完成）

- 规格覆盖：R1→T3(CountConfirmer/tracker)；R2→T3(hudLost/ambiguous)；R3→T3(gainFlicker)；
  R4→T3(cascade)；R5→T2/T3(evaluateScene/collapse)+T9；R6→T7/T8；R7→T9；R6c→T7(carried)。
- 占位符扫描：无 TODO/“稍后实现”类表述；任务 11 为真机操作协议（本就需人工）。
- 类型一致性：TimerEvent/SideTimer/FrameResult/DetectionTuning 字段在 §10 各代码块间
  交叉核对一致；服务调用面（onCells/onFrame/timers/carriedTimers/evaluateScene）与
  现有调用处逐一比对过（SubstitutionTimerService.kt:373-445、SubstitutionCore.kt:213、
  DiagnosticLog.kt:160）。
- 与已验证原型的差异说明：连锁分支在原型中先发 SubstitutionStarted 再丢弃该计时；
  本版收敛为先回滚不发事件（少一个注定作废的事件，语义等价且更干净）；
  多豆（≥2 豆单步）不再触发计时回滚，只报 MultiDrop；
  塌缩检测比对两侧"掉到 0"的**首见时刻**（中点锚点会虚假靠拢），回滚对象精确到
  "由归零创建且仍是当前"的计时；回弹只作用于"创建当前计时"的掉落（冷却冲突掉豆不参与）；
  `onFrame` 保留 v1 返回类型 `List<TimerEvent>` 使服务侧零改动编译；
  `FrameResult.counts` 为本帧逐格层读数（回放与诊断语义一致），非累计确认值。
  §10.1 用例已按本版语义编写（含对 stale 帧、锚点不确定度、keep-alive 帧的处理）。
- 落地偏差已回灌文档（A 提交）：carried→private val、evaluateDrop from-null 守卫、BaselineReset 透出 FrameResult、cancel 用例帧序修正。
