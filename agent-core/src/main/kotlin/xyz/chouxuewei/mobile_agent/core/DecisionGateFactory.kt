package xyz.chouxuewei.mobile_agent.core

/**
 * 按快照与运行上下文解析实际生效的 DecisionGate。
 * 规则（与规格一致）：
 * - backend=NONE：完全沿用既有 safetyGateEnabled → LlmDecisionGate 行为；
 * - 触发器/无人值守运行（runPolicy != null）：专用后端不适用，回到既有路径；
 * - 专用后端已选但未同意出站 / 配置不可用：gate 内逐次解析 provider，
 *   解析不到则由 DecisionPolicy 落到安全的 Confirm，并带审计原因；
 * - Laya 与 Jev 互斥，不存在后端间回退。
 */
class DecisionGateFactory(
    private val settings: suspend () -> DecisionSettingsSnapshot,
    /**
     * 数据层按后端惰性解析 provider：内部重新读取当前持久化配置（同意/地址/密钥引用/
     * 代数全部即时生效），不可用返回 null。撤销密钥后下一次裁决即走 fail-safe。
     */
    private val resolveProvider: suspend (DecisionBackend) -> DecisionProvider?,
    /** 既有安全闸开关（AgentExecutionSettingsRepository 的 safetyGateEnabled）。 */
    private val legacyGateEnabled: suspend () -> Boolean,
    /** 既有闸的构造（拿到当前连接）；仅在 legacyGateEnabled 为真时被调用。 */
    private val legacyGate: (ChatConnection) -> DecisionGate?,
    private val audit: suspend (DecisionAuditEvent) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun safetyGate(connection: ChatConnection, runPolicy: RunPolicy?, runId: String? = null): DecisionGate? {
        val snapshot = settings()
        val legacy = if (legacyGateEnabled()) legacyGate(connection) else null
        if (snapshot.backend == DecisionBackend.NONE) return legacy
        if (runPolicy != null) {
            // 后台触发器版本一不接专用后端；不改变既有无人值守语义。
            audit(
                DecisionAuditEvent(
                    backend = snapshot.backend,
                    mode = snapshot.mode,
                    purpose = DecisionPurpose.SAFETY_GATE,
                    requestHash = "",
                    runId = runId,
                    verdict = "skipped",
                    fallbackReason = "unattended_run",
                    keyGeneration = snapshot.profileFor(snapshot.backend).keyGeneration,
                    createdAtEpochMillis = clock(),
                )
            )
            return legacy
        }
        return SystemOneDecisionGate(
            backend = snapshot.backend,
            providerResolver = { resolveProvider(snapshot.backend) },
            mode = snapshot.mode,
            keyGeneration = { settings().profileFor(snapshot.backend).keyGeneration },
            delegate = legacy,
            audit = audit,
            clock = clock,
        )
    }
}
