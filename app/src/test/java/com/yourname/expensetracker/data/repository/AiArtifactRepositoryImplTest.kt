package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.AiArtifactDao
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AiArtifactRepositoryImplTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)

    private lateinit var dao: AiArtifactDao
    private lateinit var repository: AiArtifactRepositoryImpl

    @Before
    fun setup() {
        dao = mockk(relaxed = true)
        repository = AiArtifactRepositoryImpl(mockk<DatabaseWriteBarrier>(relaxed = true), dao, timeProvider = mockk<TimeProvider>(relaxed = true))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

private fun fakeEntity(
        targetKey: String = "pending_review:1",
        capability: AiCapability = AiCapability.REVIEW_EXPLANATION
    ) = AiArtifactEntity(
        id = 1L,
        targetType = AiTargetType.PENDING_REVIEW,
        targetId = null,
        targetKey = targetKey,
        capability = capability,
        status = AiArtifactStatus.READY,
        mode = AiMode.AUTO,
        provider = null,
        modelName = null,
        promptVersion = "v1",
        summaryText = null,
        explanationText = null,
        payloadJson = null,
        confidence = null,
        sourceHash = "hash",
        errorMessage = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        expiresAt = null
    )

private fun fakeRecord(
        targetKey: String = "pending_review:1",
        capability: AiCapability = AiCapability.REVIEW_EXPLANATION,
        expiresAt: Long? = null
    ) = AiArtifactRecord(
        id = 1L,
        targetType = AiTargetType.PENDING_REVIEW,
        targetId = null,
        targetKey = targetKey,
        capability = capability,
        status = AiArtifactStatus.READY,
        mode = AiMode.AUTO,
        provider = null,
        modelName = null,
        promptVersion = "v1",
        summaryText = null,
        explanationText = null,
        payloadJson = null,
        confidence = null,
        sourceHash = "hash",
        errorMessage = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        expiresAt = expiresAt
    )

    // ── observeLatest ─────────────────────────────────────────────────────────

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `observeLatest delegates to dao with capability name`() = runTest(testDispatcher) {
        val entity = fakeEntity()
        val expected = fakeRecord()
        every {
            dao.observeLatest("pending_review:1", AiCapability.REVIEW_EXPLANATION.name)
        } returns flowOf(entity)

        val result = repository
            .observeLatest("pending_review:1", AiCapability.REVIEW_EXPLANATION)
            .first()

        assertEquals(expected, result)
        verify { dao.observeLatest("pending_review:1", AiCapability.REVIEW_EXPLANATION.name) }
    }

    // ── getLatest ─────────────────────────────────────────────────────────────

    @Test
    fun `getLatest returns null when dao returns null`() = runTest(testDispatcher) {
        coEvery {
            dao.getLatest(any(), any())
        } returns null

        val result = repository.getLatest("missing:key", AiCapability.REVIEW_EXPLANATION)

        assertNull(result)
    }

    // ── upsert (RP-14 P8-004: durable artifacts require a non-null expiry) ────

    @Test
    fun `upsert rejects null-expiry artifacts and never touches the dao`() = runTest(testDispatcher) {
        val record = fakeRecord(expiresAt = null)

        val thrown = runCatching { repository.upsert(record) }.exceptionOrNull()

        assertTrue("null expiry must fail closed", thrown is IllegalArgumentException)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    // ── deleteExpired (RP-14 P8-004: explicit null-expiry backstop cutoff) ────

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `deleteExpired delegates to dao with now and backstop cutoff`() = runTest(testDispatcher) {
        val now = 999_999L
        repository.deleteExpired(now)
        coVerify {
            dao.deleteExpired(now, now - AppConfig.Ai.NULL_EXPIRY_BACKSTOP_MS)
        }
    }

    // ── RP-14 P8-004: gateway writer coverage (static source assertion) ──────

    /**
     * Every production writer of [AiArtifactRecord] must derive a non-null
     * `expiresAt` from an AppConfig.Ai TTL — the gateway now fails closed on
     * null (upsert rejection test above), so this guards the writers against
     * regression. Source-scan mechanism mirrors DataRetentionWorkerTest's
     * no-legacy-helpers assertion.
     */
    @Test
    fun `every production AiArtifactRecord writer passes a non-null expiresAt`() {
        val useCaseDir = "src/main/java/com/yourname/expensetracker/domain/ai/usecase/"
        val writerFiles = listOf(
            "GenerateDashboardBriefingUseCase.kt",
            "CategorizeReceiptItemsUseCase.kt",
            "SuggestReceiptExtractionUseCase.kt",
            "SuggestCategoryFallbackUseCase.kt",
            "JudgePendingReviewDuplicateUseCase.kt",
            "ExplainPendingReviewUseCase.kt",
            "GenerateTransactionInsightUseCase.kt"
        )
        for (file in writerFiles) {
            val source = File(useCaseDir + file).readText()
            assertTrue(
                "$file must construct AiArtifactRecord with an explicit non-null expiresAt",
                source.contains(Regex("expiresAt\\s*=\\s*now\\s*\\+"))
            )
        }
    }
}