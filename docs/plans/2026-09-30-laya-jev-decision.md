# Mobile Agent：Laya / Jev 可选接入实施指令

> 本文交给修改 `KYRIE66nb/mobile-agent` 的开发 Agent。目标是实施、测试和交付，不是再写一遍架构建议。
> 如环境安装了 Superpowers，按 executing-plans 或 subagent-driven-development 分任务执行，并使用 TDD、代码审查与完成前验证。不得以缺少某个插件为由停止普通开发。

**Goal：** 在现有 Android 客户端实现“不接入 / Laya / Jev”，完整接通专用安全裁决和默认关闭的低风险导航加速，并保留原运行方式。

**Architecture：** 复用现有 DecisionGate、ChatRuntime、工具执行器、权限审批、设备网关和持久化。Laya 自建 HTTP 服务与 Jev 托管 API 使用独立的决策客户端；专用模型只返回裁决或候选 ID，不直接控制设备。

**Tech Stack：** 沿用仓库实际使用的 Kotlin、协程、Compose、OkHttp、序列化、DataStore、Room 和 Keystore；不因此升级整个技术栈。

**Spec：** 本文第 1—10 节是确定的需求规格，第 11—13 节是实施与验收计划。执行时将本文放入 `docs/superpowers/plans/` 或项目已有等价目录。

**核查基线：** `main` 提交 `5a5420ed959563640d408b5644f2759c516922a9`。这是规划时读取到的版本，不要求回退到它；实际修改必须基于当前工作区并重新核查差异。

## 0. 执行授权与边界

请在当前仓库实施以下改造并自行验证，不要只交付分析、界面截图、空接口、伪实现或 TODO。

先检查工作区、项目约束、模块与测试命令，再做增量修改。不确定但不影响安全的细节，采用保守实现并写入报告，不要反复询问已经明确的需求。

保留用户未提交的改动。不要 reset --hard、clean -fd、强推、重写历史、擅自升级依赖、改包名、改签名或卸载旧应用。优先隔离分支/工作区；无法安全隔离时明确记录，不覆盖原改动。允许本地开发、测试和逐阶段本地提交；本指令不授权推送、合并、发布 Release、部署收费服务或操作真实账户。

缺少 API Key、服务、SDK、真机等条件时，继续完成不依赖这些条件的实现与测试，明确列出未验证部分。真实密钥不得索要到源码文件、提交历史或普通日志中。

## 1. 全局约束

1. 不重写 Agent，不更换主聊天模型，不改变现有 Root / Shizuku / 无障碍 / 虚拟屏控制体系。
2. 新安装和已有安装升级后，专用后端均默认 NONE；原 safetyGateEnabled 的值原样保留。
3. 不接入不是关闭安全闸：NONE 时仍按原开关决定是否使用 LlmDecisionGate。
4. 新功能不得绕过工具权限、用户审批、RunPolicy.allowedToolIds、执行位置偏好、观察版本校验、步数上限或停止操作。
5. 第一版只在交互式任务中使用专用模型；后台触发器保留原 RunPolicy / ScopeGate 及数据出站行为，明确显示“不适用本版本专用决策”。不静默改变后台授权。
6. 不修改 Ad Guard 的确定性执行回路，不改写 Recipe 或 device_batch 的内部执行；它们不纳入导航加速。原执行路径仍按已有规则运行。
7. 第一版 Laya、Jev 二选一。禁止未经独立授权的 Laya→Jev、Jev→Laya 跨服务回退。
8. 不把 Python、PyTorch、Node 或模型权重嵌入 APK，不把完整聊天记录或截图发到决策接口。
9. 不承诺固定准确率、加速倍数或省 Token 比例；必须区分功能正确性、模型效果和端到端实测。

## 2. 先阅读实际代码

至少阅读以下文件及它们的直接调用方、存储实现和关联测试。文件迁移时通过符号查找，不按旧路径机械创建重复实现。

- `agent-core/src/main/kotlin/xyz/chouxuewei/mobile_agent/core/DecisionGate.kt`
- `agent-core/src/main/kotlin/xyz/chouxuewei/mobile_agent/core/ChatRuntime.kt`
- `agent-core/src/main/kotlin/xyz/chouxuewei/mobile_agent/core/DeviceContract.kt`
- `agent-core/src/main/kotlin/xyz/chouxuewei/mobile_agent/core/ToolContract.kt`
- `agent-core/src/main/kotlin/xyz/chouxuewei/mobile_agent/core/TriggerContract.kt`
- `data/src/main/kotlin/xyz/chouxuewei/mobile_agent/data/AgentExecutionSettingsRepository.kt`
- `app/src/main/java/xyz/chouxuewei/mobile_agent/chat/ChatSettings.kt`
- `app/src/main/java/xyz/chouxuewei/mobile_agent/prototype/PrototypeApplication.kt`
- `tools/src/main/java/xyz/chouxuewei/mobile_agent/tools/DeviceToolProvider.kt`
- 模型网关、网络取消实现、Room schema/migrations、密钥加密和 backup rules。

核实的重点：DecisionGate 的调用时机；审批后是否修改 argumentsJson；SINGLE_MODEL_STEP 的清理边界；DeviceGateway 对 observation_id 和 contentRevision 的真正校验；触发器专属 gate 的优先级；并行只读工具与设备会话锁。

先记录 `git status --short`、当前分支、HEAD 和基线测试结果。既有失败与本次引入失败分别记录。

## 3. 客户端配置与明确语义

### 3.1 配置模型

使用以下逻辑枚举；序列化保存稳定字符串，不保存 ordinal：

- DecisionBackend：NONE / LAYA / JEV。
- DecisionMode：OFF / SHADOW / ENFORCE。
- DecisionPurpose：SAFETY_GATE / FAST_PATH。

DecisionSettings 至少包含：backend、gateMode、fastPathMode、独立的 layaProfile / jevProfile、配置修订号和按端点绑定的数据传输同意状态。

默认值：backend=NONE，gateMode=SHADOW，fastPathMode=OFF。backend=NONE 优先于两项模式，所有专用调用都旁路。

LayaProfile：服务 origin、模型/路由值、可选的 API Key 加密引用、请求总超时、独立阈值配置、经过验证的输入预算配置。
JevProfile：官方服务 origin、模型值、API Key 加密引用、请求总超时、独立阈值配置。

Laya 与 Jev 的设置切换后各自保留；普通配置与密钥分开保护。不得复用主聊天模型的 Key 或配置仓库来冒充接入完成。

### 3.2 界面

在现有 Agent 设置中增加“专用决策模型”，提供三选一后端、两个用途各自的模式、对应服务配置、显示/隐藏密钥、测试连接、保存、清除密钥。

文案明确：

- 不接入：保持原流程，不向 Laya/Jev 发送数据。
- SHADOW：仅评估与记录，不采用该结果改变操作，不减少原审批。
- ENFORCE：正式参与，但不能降低本地硬性限制；导航加速标为实验功能。
- 原安全闸关闭时，专用安全裁决不生效；导航 ENFORCE 也不得生效。提示用户主动开启，禁止自动改开关。
- 关闭后台触发器中的专用调用，说明第一版只用于交互任务。

SHADOW 同样涉及数据出站，首次使用必须展示目的地与数据范围并获得同意。换 origin 需要重新同意。清除 Key 或普通数据管理操作不得把密文当有效连接继续使用。

“测试连接”只能由用户明确点击触发；使用固定、无敏感数据的 choice 请求验证鉴权、接口和响应结构。不能仅用 HTTP 200 或 /health 宣布模型可用，更不能将连接测试称为准确率测试。NONE 时禁用测试请求；用户须先选择待测试的后端。

### 3.3 配置生效与关闭

普通参数/后端切换在新 Run 开始时读取完整快照，运行中不拼接旧 Key、新 URL、新模型。

例外：选择 NONE 是立即撤销新功能的开关。立即停止所有 Run 的后续专用请求，取消在途专用网络请求，拒收晚到结果，并退出快路径；不能撤回已发送的数据，应在说明中写明。当前尚未批准的安全动作转人工确认，后续按原安全闸设置运行。重新启用只影响新 Run，不恢复旧请求。

删除当前使用的 Key 或撤销对应出站同意同样立即撤销相关专用请求。停止任务则取消整个 Run，不能作为普通回退后继续操作。

## 4. 模块边界与接口

尽量按下述职责组织，不要求在已有等价抽象存在时重复建类。

`agent-core`：
- DecisionContract.kt：纯 Kotlin 请求/响应、错误、来源与配置快照契约。
- DecisionGateFactory.kt：选择 legacy / shadow / 专用 gate，处理适用范围。
- SystemOneDecisionGate.kt：映射为 GateVerdict。
- DecisionPolicy.kt：本地底线、结果可采纳性与阈值策略。
- FastPathController.kt：有限导航循环、回退、预算、取消。
- ActionCandidateBuilder.kt：从当前真实观察产生可执行候选。

`model`：
- SystemOneHttpClient.kt：独立 HTTP/JSON、鉴权、取消、大小限制。
- LayaDecisionProvider.kt / JevDecisionProvider.kt：字段映射、后端差异和配置校验。

`data`：DecisionSettingsRepository.kt 与最小必要的决策审计持久化。
`app`：设置 UI、装配、状态与记录展示。
`tools`：有限导航目标的输入、瞬时结构化观察的输出，以及统一执行入口的必要适配。

核心接口统一为 `suspend fun choose(request: DecisionChoiceRequest): DecisionOutcome`。

DecisionChoiceRequest 包含 requestId、purpose、脱敏后的结构化 state、简短 instructions、options（候选 ID→说明）。身份、Key、URL 不由模型生成，Provider 从 Run 的配置快照获取。

DecisionOutcome 区分 Success、Unavailable、InvalidResponse、Timeout；协程取消向上抛出，不塞进普通错误结果。Success 包含 requestedModel、returnedModel、choice、完整 probabilities、可选 rawConfidence、可选 answerConfidence、服务真实 usage、elapsedMs。requestId/runId 在客户端关联，不要求服务回显未公开支持的字段。

不要在 agent-core 引入 Android、OkHttp、模型 SDK 或重型序列化运行时；网络序列化 DTO 留在 model 模块。新增文件应遵循仓库实际包路径。

## 5. HTTP 协议、输入预算与结果检查

### 5.1 协议

第一版只实现本功能所需的 choice；不要为“完整”额外实现未使用的 score / noul UI。

Jev 使用官方 `POST /v1/systemone` 与 Bearer Key，字段为 state、model、questions；问题内部为 type=choice、instructions、criteria。

Laya 使用经验证版本的官方 laya.serve 同名接口。默认中文配置选择 multilingual；高级设置可支持官方真实路由值。模型值必须经过适配器校验，不能把 Jev 模型字符串直接发给 Laya 后假定它固定加载了指定模型。

URL 输入定义为服务 origin（scheme+host+port），由客户端追加一次 `/v1/systemone`；拒绝 query、fragment、userinfo 和不支持的 path。避免 `/v1/v1/systemone`。第一版 Jev 不开放自定义第三方网关。

请求示例只作为协议说明，actual ID 和 state 必须由本轮运行生成：

    {"model":"jev-latest","state":{"step_goal":"进入设置的蓝牙详情，不修改开关"},
     "questions":{"next_action":{"type":"choice","instructions":"选择符合当前小目标的候选；不确定选择 escalate。",
     "criteria":{"candidate_1":"进入蓝牙详情页面","escalate":"不执行候选，交回原模型"}}}}

jev-latest 可用于联调；记录真实 returnedModel。生产评估应绑定验证过的模型版本；不要把官方文档示例版本写成当前最新版本。

### 5.2 客户端初始限制

以下是本项目初始工程参数，不是性能结论，必须集中定义并测试：

- 决策总超时默认 2500ms，可配置 500—10000ms；排队、连接、传输、解析共同消耗这个预算。
- 默认不自动重试专用请求，失败立即按用途降级；禁止复用聊天网关的重试或多模型 failover 链。
- 每 Run 同时最多一个专用请求；同一端点最多两个在途请求，排队同样受总超时限制。
- 请求 JSON 上限 32KiB，响应解压后读取上限 128KiB，必须流式限长而不是完整加载后才检查。
- 同一端点/模型/凭证版本连续三次服务故障后熔断 60 秒。冷却后仅在下一次合格用户任务中允许一次半开探测，不跑后台探活。
- 401/403 标记配置不可用直到编辑/手动测试恢复；429/529/5xx 记录退避信号和熔断状态，不在一轮内反复请求。
- 语义不确定/选择 escalate 不算网络故障，不能触发服务熔断。

### 5.3 严格解析

校验 answers 下的准确 question ID、type、choice、probabilities 和返回模型。模型校验须理解官方 alias/路由映射：例如 jev-latest 的返回值可以是具体版本；不能机械要求 requestedModel 与 returnedModel 字符串相同。未知映射记录并不采纳；已验证模型发生变化时撤销旧阈值配置的已验证标记。choice 必须属于本次 criteria；概率 key 集合必须与本次候选一致；概率必须有限且在 [0,1]；总和允许 1e-3 的浮点误差；choice 必须为最大概率项之一（允许浮点误差范围内并列）。

关键字段缺失、NaN/Infinity、非法 ID、概率矛盾、超大或非 JSON 响应一律不可采纳。未知非关键字段可忽略；缺 usage 可以标记 unavailable，不能补造 0 或虚构计费。

不要使用正则解析新接口 JSON，不执行响应中的任意动作、URL、代码或工具参数。状态说明使用本地 reasonCode 模板；不能声称服务生成了协议里没有提供的自然语言解释。

### 5.4 置信度与截断

不要共用 Laya/Jev 的 rawConfidence 阈值。保留原始字段，用 selectedProbability=probabilities[choice] 和 top1-top2 margin 做可解释的本地策略，并按后端、模型、用途分别保存设置。

初始实验值可采用 selectedProbability>=0.95 且 margin>=0.30；两项只影响经本地规则认可的低风险候选，绝不是授权依据或可靠性保证。对安全 block 也校验可采纳性；低可信异常裁决转 confirm，不直接产生任意 block。

Laya 不能按默认上下文长度直接吞下整棵 UI 树。短目标、原文否定限制、目标节点语义和候选说明必须完整保留。网络字节上限不等于模型 Token 预算。根据实际部署版本核查 max_len/head_max_len；不支持的扩展字段不得发给 Jev。

交付 Laya 部署/检查脚本，使用与服务一致的 tokenizer 和序列化方式，对本项目请求 fixture 检查 state 和 option 的实际 Token 预算。fixture 通过不是任意运行时输入均不会截断的证明：每次请求还要有经过验证的保守上界检查，或由自建服务的轻量输入检查层在推理前拒绝超预算输入；不要重写模型本身。无法确认关键内容未截断的配置只允许 SHADOW，ENFORCE 回退；不得静默裁掉“不发送”等约束后继续自动执行。

## 6. 安全闸的实际接入

保留 DecisionGate / GateRequest / GateVerdict 兼容性；新增字段有兼容默认值，原调用方无需虚构上下文。

GateFactory 的行为：

- 非交互任务或 RunPolicy 专属 gate：保留原链路，不新增外部调用。
- backend=NONE 或 gateMode=OFF：按原 safetyGateEnabled 返回原 LlmDecisionGate 或 null。
- safetyGateEnabled=false：无专用 gate；不自动重新开启。
- SHADOW：原 gate 决定结果，专用结果仅记录；不得等待专用结果阻止基线执行，不得添加审批或覆盖 baseline verdict。影子请求属于当前 Run 的有界子任务，Run 结束/取消时取消未完成请求；不得放入脱离 Run 的无限后台任务，也不得为等结果延长原始观察保留。
- ENFORCE：在原统一执行器的安全检查位置使用专用 gate，并保留所有原权限与审批要求。

对专用 gate 增加本地安全底线：明确禁止的支付、密码/验证码输入、账号安全、格式化等操作不得由专用模型放行；已识别的发送/发布/删除/配置修改等保留确认。完整判断基于实际参数与目标上下文，而不是仅凭工具 ID 或关键词。

普通导航只有在程序具有明确的低风险证据且模型结果可采纳时，才允许 GateVerdict.Allow；未知坐标点击、缺少目标含义或风险信息不完整时至少 Confirm。模型不能降低本地 Block / Confirm；Allow 也不取消原工具权限审批。

现有原模式没有的全局规则不要借此无限扩张到无关功能；NONE 的兼容性例外仅限明确修复取消/执行一致性缺陷并提供回归测试。

GateRequest 应获得当前操作的真实、必要、脱敏语义：原请求和限制、工具与动作、实际目标、前台包、运行方式。截图不发送。用户输入框内容与第三方页面文本不能被当成系统指令。

安全服务超时、错误、低可信、输入缺失：Confirm；需要人工确认而执行环境无人值守：拒绝并记录，不挂起也不静默 Allow。

审批与裁决绑定实际执行参数的指纹（含目标与观察版本）。审批界面改变参数/模式/目标，或等待期间页面变化，旧裁决失效；重新校验并在必要时重新确认。不得检查 A 动作却执行 B 动作。

取消处理必须区分：当前专用请求自身超时可降级；父 Run 取消、用户停止、观察任务取消必须传播。可用 withTimeoutOrNull 管理本层超时，并在副作用前检查协程状态。OkHttp Call 必须与协程取消绑定。现有 LlmDecisionGate 的 runCatching 捕获取消问题一并用最小改动及测试修复，不把所有 CancellationException 都降级为 Confirm。

## 7. 导航加速：目标来源与完整执行链

不能只写 CandidateBuilder 却没有真正接入 ChatRuntime，也不能每一步仍调用完整原模型再追加专用请求后宣称“减少主模型调用”。

### 7.1 明确小目标来源

ENFORCE 下，为当前 Run 暴露的 device_observe schema 增加可选 navigation_goal 对象（描述、expected_package），由原模型在规划一次后提出。描述限制为一个短导航目标；不能包含发送、输入新内容、改设置等授权扩张。Run 和源用户请求 ID 由运行时绑定，不信任模型自行提供的权限字段。

这是对现有工具的运行时 schema 扩展，不是要求主聊天 API 支持某个不存在的结构化输出接口。NONE、fastPathMode=OFF 与 SHADOW 时保持原工具 schema 和原系统提示不变。

没有合法 navigation_goal 就不进入 ENFORCE 快路径，不能从模型思考文本猜目标。原用户限制始终附带，navigation_goal 不是用户许可。

SHADOW 不为制造评估样本增加原模型调用或改变工具 schema；仅在现有观察和明确的单一用户导航请求足以形成评估输入时运行，否则记录 skipped_no_goal。不得触发额外设备操作或额外观察。

### 7.2 观察与候选

从成功的 device_observe 返回内部瞬时结构化上下文，复用 Observation / NodeSnapshot / sessionId / observationId / contentRevision。通过 ToolResult 的可选内部元数据或等价纯核心契约传递，不解析被截断或压缩后的展示字符串，不另留整棵树到数据库。

第一版最多 7 个真实可执行候选，加一个保留选项 escalate，总数最多 8。只有一个候选时仍保留 escalate。候选说明短且区分明确；候选 ID 不携带可执行代码。

候选只允许程序明确登记并验证过的普通页面导航/滚动；排除不可见、不可用、可编辑、可切换/可勾选控件、授权弹窗、支付、发送、删除、账号安全及语义不明目标。不能仅因文本像“设置/蓝牙”就把实际开关当成普通导航。

没有可靠页面规则、目标歧义、候选遗漏、纯图片/自绘页面、需生成文字或跨多步骤规划时回原模型。没有真实设备证据时不得编造资源 ID 或宣称已适配某厂商页面。

每个 candidateId 在本地绑定不可变完整动作、参数、runId、sessionId、observationId、contentRevision 和来源；服务只能选择 ID，不能重写 node_ref、坐标、包名、文本或权限。

### 7.3 快路径循环

原模型提出导航目标并观察 → 构造候选 → 专用 choice → 本地结果与权限校验 → 同一安全闸/审批 → 同一工具执行器 → 记录结果 → 通过受控工具入口重新观察。

每次只执行一个动作，完成后重观测。最多连续 3 个快路径动作就交回原模型确认目标和后续规划。目标完成时停止加速并由原模型根据真实结果总结；模型不能仅凭高置信度宣布任务完成。

禁止专用 Provider 直接访问 DeviceGateway。内部重新观察也要受工具权限与 scope 限制；观察权限被撤回不能继续。当前待处理一组主模型工具调用未完成时不要插入快路径，避免并行读/写或多个观察结果竞争。

所有快路径动作进入现有 ToolCallRecord / Run 生命周期，标记执行来源；传给主模型的后续上下文保留真实操作和结果，不能伪装成主模型思考。若网关要求成对的 assistant tool_call / tool result，构造有明确运行时来源的合法事件对，不能留下孤立 tool 消息。

主模型规划调用、原安全闸调用、专用模型调用分别统计。快路径不能绕过原步数与总执行预算；正常原路径的原有计数语义不变。

### 7.4 状态与回退

每个观察只供一个逻辑决策周期使用。专用模型选择 escalate/异常后，允许原模型在同一周期使用仍然有效的观察；不得第一次网络返回就清掉回退所需上下文。观察已变更或过期则先重新观察，不复用旧节点。

将“提供给下一次模型的观察”和“当前待执行动作的最小校验证据”分开管理。裁决结束/动作完成/取消/Run 终止后及时释放；不得因此无限延长截图或 UI 内容保留。

执行前校验当前活动 Run、配置撤销代次、会话、观察 ID、内容版本与目标。使用设备侧真实有效性检查和既有串行化，不能只比较请求与响应中同一份旧 revision。无法证明目标仍有效则重新观察。

同一观察不得被两个并发动作重复消费。执行后超时或进程中断导致结果未知，标记 unknown/interrupted 并重新观察，不自动重放点击。

明确重复动作、无进展、候选失效、服务故障、权限变化、审批参数变化或达到预算：停止快路径，携带真实已完成动作和错误回原模型。父任务取消则整个任务停止，不回退继续运行。

## 8. 网络、隐私与部署

新增决策 HTTP 客户端默认仅接受 HTTPS，禁止 trust-all、跳过主机校验或向跨 origin 重定向携带 Authorization；第一版直接拒绝决策接口重定向。

Release 不为此全局开放 cleartext；如需 Laya HTTP 联调，使用单独 debug 配置，限定开发目标并说明边界，不能影响其它服务连接。公共部署必须鉴权并提供 TLS；不能以“关闭 Android 安全校验”解决连不上。

决策出站仅包含本步必需数据；完整 UI 树、历史对话、通知全文、联系人列表、截图、密码、验证码、令牌禁止默认发送。脱敏后关键语义不足时回退，而不是移除限制后照样自动执行。

密钥复用现有 Keystore 加密机制并从系统备份/导出中排除。清 Key/重置数据/备份恢复后处理失效引用；不得崩溃、静默当空密钥继续运行或回显密文。

增加独立 `deploy/laya/` 或项目等价目录：锁定已验证依赖或提交、提供无真实密钥的 `.env.example`、最小启动说明、合成 choice smoke test、输入 Token 预算检查。尽量复用官方 laya.serve，不重写推理后端。

明确“服务器运行在电脑/云端，手机用可访问的地址连接”；不要把手机 localhost 当成电脑，也不能把服务器绑定地址 0.0.0.0 当成客户端目标。真实部署、模型权重下载和收费请求需用户明确同意，缺少条件时只交付脚本与标明未运行的步骤。

README、PRIVACY 和第三方说明同步更新。客户端协议使用和服务端权重分发是不同问题；本次不分发模型权重、不引入未使用的第三方 SDK。

## 9. 审计与数据兼容

决策审计最少保存 runId、toolCallId（适用时）、requestId、purpose、backend、请求模型/返回模型、mode、selectedCandidateId、有限概率指标、耗时、采用/旁路/回退原因、服务真实 usage、schema/policy 版本。

默认不保存原始 state、请求/响应 body、完整 UI 文本或 Key。审计按必要最小信息保存，并纳入现有会话删除、数据清理和保留期规则。旧记录缺新增字段时以兼容默认值读取。

新增 Room 字段/表必须有迁移与迁移测试，禁止 destructive migration。导入导出保留旧格式兼容；敏感决策配置不混入聊天导出。

UI 至少能查看：本步由谁决定、是否采纳、为什么回退、耗时、当前模型与适用模式。SHADOW 记录不得显示为已执行。

## 10. Review Focus：高风险边界

以下问题必须有所属任务的明确测试，不得只人工扫代码：

1. NONE / 撤销同意 / 清 Key 与晚到响应的竞态，不能产生新请求或误用旧裁决。
2. 用户停止与本层超时的区别，不能吞掉取消后继续审批或点击。
3. 审批中参数变更、屏幕变化、连续动作与观察消费，不能裁决 A 却执行 B。
4. SHADOW 不改变原工具 schema、主模型调用和设备行为，只增加用户同意的评估通信与本地记录。
5. 首次数据迁移、备份恢复、模型版本变化和 Laya 截断，不能破坏旧数据或把未验证配置当可靠配置。

## 11. 分任务实施（每项都按 TDD 完成）

统一步骤：先新增明确会失败的测试 → 运行并确认失败源于未实现需求而非环境错误 → 实现最小改动 → 跑目标测试及相关回归 → 检查 diff → 本地阶段提交。

### Task 1：配置与旧行为兼容

Files：新增 DecisionContract、DecisionSettingsRepository，修改设置装配的最小部分。
Interfaces：输出完整 DecisionSettingsSnapshot、撤销 generation 与独立凭证引用。

- [ ] 测试 `newAndMigratedInstallUsesNone`：新旧安装均 NONE，原 safetyGateEnabled true/false 各保留。
- [ ] 测试 `switchBackendPreservesIndependentProfiles` 和 `restoredInvalidSecretIsNotUsable`。
- [ ] 实现原子设置快照、加密引用和撤销信号。
- [ ] 跑 data/core 相关测试，检查无需 Key 就能使用原模式。
- [ ] 本地提交这一项，不推送。

### Task 2：两个真实协议适配器

Files：model 下的 SystemOneHttpClient、两个 Provider 与网络测试。
Interfaces：消费 DecisionChoiceRequest/Run 快照，输出 DecisionOutcome。

- [ ] MockWebServer 测试准确路径、method、Bearer、model 和 questions；两个 Provider 独立请求 fixture。
- [ ] 测试非法分布、缺关键字段、错误模型、未知候选、超大响应、重定向与缺 usage。
- [ ] 测试 timeout、parentCancellationCancelsCall、401/429/529、熔断与并发限制。
- [ ] 实现并跑相关 JVM/Android local tests；没有真实 Key 时不能标记真实联调通过。
- [ ] 本地提交。

### Task 3：安全闸接入与参数一致性

Files：DecisionGateFactory、SystemOneDecisionGate、DecisionPolicy，最小修改 DecisionGate/ChatRuntime。
Interfaces：消费 Provider，返回既有 GateVerdict；原权限执行器继续持有执行权。

- [ ] 测试 legacyEnabled/Disabled × NONE/OFF/SHADOW/ENFORCE 行为矩阵。
- [ ] 测试 localConfirmOrBlockCannotBeDowngraded、unknownTargetRequiresConfirmation。
- [ ] 测试 gateFailureConfirms、cancelStopsInsteadOfConfirms、parameterChangeInvalidatesVerdict。
- [ ] 测试 RunPolicy 专属 gate 与后台不出站；原权限关闭/FULL_ACCESS 均不能绕过必要确认。
- [ ] 接入真实装配位置、修复取消语义，运行既有 gate/runtime/trigger 回归。
- [ ] 本地提交。

### Task 4：配置 UI 与出站同意

Files：ChatSettings、PrototypeApplication 和 UI 状态/测试。
Interfaces：消费 SettingsRepository，保存原子配置；连接测试使用真实 Provider 而非固定成功返回。

- [ ] 测试界面状态、字段校验、保存失败、测试连接结果、原 gate 关闭提示。
- [ ] 测试 NONE 不探活、SHADOW 需要同意、origin 变化重新同意、清 Key/撤销取消请求。
- [ ] 测试 lateResponseAfterDisableIsIgnored 与新 Run 快照。
- [ ] 完成中英文文案与必要 UI 测试，不修改整体界面设计。
- [ ] 本地提交。

### Task 5：小目标与候选契约

Files：ActionCandidateBuilder、瞬时观察契约、DeviceToolProvider 的可选字段与 per-Run schema。
Interfaces：输入 Observation+NavigationGoal+原用户约束；输出带不可变动作绑定的候选集合。

- [ ] 测试无 goal 不加速；NONE/OFF/SHADOW 的原工具 schema 不变。
- [ ] 测试最大 8 项含 escalate、语义按钮/开关区分、重复或隐藏节点、未知页面回退。
- [ ] 测试模型不能指定新 node_ref、改包名、改坐标或提高授权。
- [ ] 实现瞬时上下文通道，不增加原始 UI 持久化。
- [ ] 本地提交。

### Task 6：真正的导航快路径

Files：FastPathController、ChatRuntime 的有限分支、设备结果/历史事件的必要适配。
Interfaces：消费 Provider/候选，输出执行既有工具的请求或带原因的原模型回退。

- [ ] 测试合格候选通过统一权限/gate/审批后恰好执行一次。
- [ ] 测试 fastPathCanSkipPlannerCall：用计数 FakeGateway 证明至少一个合格步骤未调用规划模型，不能仅测试返回候选。
- [ ] 测试 fallbackPreservesValidObservation、staleObservationReobserves、concurrentConsumeRunsOnce。
- [ ] 测试最多连续 3 步、总预算、无进展回退、父取消停止、结果未知不重放。
- [ ] 测试原模型接回真实历史且 tool-call 配对合法；不能把 metadata 标记当真实模型 reasoning。
- [ ] 本地提交。

### Task 7：审计、迁移与影子评估

Files：最小决策记录存储与迁移、UI 展示、清理/导入导出兼容测试。
Interfaces：接受结构化脱敏审计事件，不依赖原始 state 重建执行。

- [ ] 测试旧数据库升级与旧格式导入、删除会话同步清理、无原始 state/Key 泄漏。
- [ ] 测试 SHADOW 的主模型输入、调用次数与设备动作和基线一致；其异常不阻止基线。
- [ ] 测试 requested/returned model、缺 usage 显示 unavailable、来源不是主模型用量。
- [ ] 跑迁移测试和数据层回归后本地提交。

### Task 8：部署说明、综合验证与交付

Files：deploy/laya、使用说明、PRIVACY、变更说明与验收报告。
Interfaces：给用户一条从 APK 设置到测试服务的完整配置路径，无开发者生产 Key。

- [ ] 提供固定版本/提交来源、合成 smoke test、实际 tokenizer 的预算测试；将未执行项明确标出。
- [ ] 跑本文第 12 节所有测试和静态检查，检查基线问题与新增问题。
- [ ] 有环境时构建 debug APK，报告路径、构建命令和校验值；不要擅自安装到用户手机、删除旧应用或更换签名。
- [ ] 审查没有未接入的假实现、无关改动、敏感数据和真实副作用测试。
- [ ] 输出第 13 节验收报告；明确还有哪些真实环境验证需要用户完成。

## 12. 最终验收清单与命令

必须有自动化断言或明确的人工验证结果，不得笼统写“已测试”：

- [ ] NONE 下 Laya/Jev 请求计数为 0，原安全闸开关行为保留。
- [ ] Laya 与 Jev 均通过协议、鉴权、解析、异常与取消测试。
- [ ] NONE/撤销/清 Key 后的晚到响应无法影响执行；停任务后无新增副作用。
- [ ] SHADOW 不改变原 schema、主模型输入/调用次数、裁决与设备动作。
- [ ] ENFORCE 安全 gate 在真实 runtime 装配点生效，不是仅类单测。
- [ ] ENFORCE 快路径至少有一条覆盖 goal→观察→候选→服务→统一执行→历史的集成测试。
- [ ] 合格快路径在测试中跳过规划调用；对服务实际性能不作无证据承诺。
- [ ] 高风险/否定/未知语义/低可信输入不会被专用模型直接自动放行。
- [ ] 协程取消、超时、断网、401、429、529、500、非法 JSON 和输入过长均符合用途回退。
- [ ] 审批变参、过期观察、重复响应、屏幕切换、并发观察消费和执行结果未知有覆盖。
- [ ] 触发器 scope/专属 gate/预授权、仅后台模式、设备锁和原停止流程不回归。
- [ ] 旧数据库、旧导出、备份恢复、失效 Key、关闭再开启都可预测。
- [ ] Release 未新增全局 cleartext/trust-all，Key 不在源码、日志、APK 常量或导出中。
- [ ] Laya Token 预算与截断验证真实可复现；未验证配置不能宣称安全可自动执行。
- [ ] 增加“不要发送”“先别修改”“只打开页面”“取消操作”和页面注入内容 fixture；模型高分不覆盖用户限制。

优先确认仓库实际 Gradle task，再运行对应任务。以下仅为候选命令，不存在时使用查证到的等价任务，不编造执行结果：

    ./gradlew :agent-core:test
    ./gradlew :model:testDebugUnitTest :data:testDebugUnitTest :tools:testDebugUnitTest
    ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
    git diff --check

有模拟器/隔离测试设备再跑所需 instrumentation/UI/迁移测试。不能因命令无输出就宣布通过；保留退出码与测试摘要。网络测试默认使用本地 mock server，禁止自动向真实账号发消息、付款、改设置或上传私人界面。

## 13. 最终交付格式

请输出：

1. 修改摘要：哪些功能真实完成，哪些未完成，是否只在交互任务生效。
2. 文件清单：修改/新增路径与职责，是否有无关变更。
3. 配置方法：客户端入口、NONE/Laya/Jev、SHADOW/ENFORCE、生效和撤销语义、连接测试。
4. 测试表：命令、退出码、通过/失败/未执行、失败原因；既有与新增问题分开。
5. 联调等级：分别列出“代码/模拟服务”“真实 Laya”“真实 Jev”“模拟器”“真实手机”；没有做就写未验证。
6. APK：真实构建产物路径和校验值；未构建成功就说明实际阻塞，不提供不存在的路径。
7. 风险和后续人工验收：包括模型校准、页面适配范围、服务延迟、数据传输与本次未授权的动作。
8. 本地分支/提交及回滚说明；不擅自 push、合并或发正式版本。

现在开始实施，从仓库与基线检查进入 Task 1，逐项做到真实可验证。不要将未调用的接口、固定响应假服务、未连接的设置项或一张 UI 截图当作功能完成。

## 官方资料与核查来源

执行时重新读取官方资料，记录采用版本；以下是规划时的入口，不代表服务永远保持不变：

- Repo: https://github.com/KYRIE66nb/mobile-agent
- Laya: https://github.com/NandhaKishorM/laya
- Laya HTTP implementation: https://github.com/NandhaKishorM/laya/blob/main/laya/serve.py
- Jev API: https://docs.typesafe.ai/api
- Jev quickstart: https://docs.typesafe.ai/introduction/quickstart
- Jev confidence: https://docs.typesafe.ai/confidence
- Android network security: https://developer.android.com/privacy-and-security/security-config
- Kotlin cancellation: https://kotlinlang.org/docs/cancellation-and-timeouts.html

注意：模型项目 README 中的 benchmark、示例返回值和“高置信度”均不是本手机 Agent 的安全证明。协议兼容不等于模型性能、阈值和输入预算相同。
