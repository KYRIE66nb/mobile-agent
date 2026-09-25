package xyz.chouxuewei.mobile_agent.data

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.ModelUsageRecord

class ModelUsageRepositoryTest {
    @Test
    fun accumulatesUsageByModelProfileAndRefreshesDisplayMetadata() {
        val first = emptyList<ModelUsageSummary>().record(
            ModelUsageRecord("profile-a", "旧名称", "model-a", 100, 20, 1L),
        )
        val updated = first.record(
            ModelUsageRecord("profile-a", "新名称", "model-a-v2", 40, 10, 2L),
        ).single()

        assertEquals("新名称", updated.modelName)
        assertEquals("model-a-v2", updated.modelId)
        assertEquals(2L, updated.measuredRequests)
        assertEquals(140L, updated.inputTokens)
        assertEquals(30L, updated.outputTokens)
        assertEquals(170L, updated.totalTokens)
        assertEquals(2L, updated.updatedAt)
    }

    @Test
    fun persistedUsageRoundTripsAndIgnoresNegativeProviderValues() {
        val usage = emptyList<ModelUsageSummary>().record(
            ModelUsageRecord("profile-a", "模型 A", "model-a", -1, 5, 3L),
        )

        assertEquals(usage, decodeModelUsage(encodeModelUsage(usage)))
        assertEquals(0L, usage.single().inputTokens)
        assertEquals(5L, usage.single().outputTokens)
    }

    @Test
    fun keepsDifferentModelProfilesSeparate() {
        val usage = emptyList<ModelUsageSummary>()
            .record(ModelUsageRecord("profile-a", "模型 A", "model-a", 10, 2, 1L))
            .record(ModelUsageRecord("profile-b", "模型 B", "model-b", 20, 4, 2L))

        assertEquals(2, usage.size)
        assertEquals(12L, usage.single { it.modelProfileId == "profile-a" }.totalTokens)
        assertEquals(24L, usage.single { it.modelProfileId == "profile-b" }.totalTokens)
    }
}
