package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.AiArtifactDao
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
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

class AiArtifactRepositoryImplTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)

    private lateinit var dao: AiArtifactDao
    private lateinit var repository: AiArtifactRepositoryImpl

    @Before
    fun setup() {
        dao = mockk(relaxed = true)
        repository = AiArtifactRepositoryImpl(dao)
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
        capability: AiCapability = AiCapability.REVIEW_EXPLANATION
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
        expiresAt = null
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

    @Test
    fun `observeLatest passes capability name for DASHBOARD_BRIEFING`() = runTest(testDispatcher) {
        every {
            dao.observeLatest("dashboard_home:2026-03-16", AiCapability.DASHBOARD_BRIEFING.name)
        } returns flowOf(null)

        val result = repository
            .observeLatest("dashboard_home:2026-03-16", AiCapability.DASHBOARD_BRIEFING)
            .first()

        assertNull(result)
        verify { dao.observeLatest("dashboard_home:2026-03-16", AiCapability.DASHBOARD_BRIEFING.name) }
    }

    // ── getLatest ─────────────────────────────────────────────────────────────

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `getLatest delegates to dao with capability name`() = runTest(testDispatcher) {
        val entity = fakeEntity()
        val expected = fakeRecord()
        coEvery {
            dao.getLatest("pending_review:1", AiCapability.REVIEW_EXPLANATION.name)
        } returns entity

        val result = repository.getLatest("pending_review:1", AiCapability.REVIEW_EXPLANATION)

        assertEquals(expected, result)
        coVerify { dao.getLatest("pending_review:1", AiCapability.REVIEW_EXPLANATION.name) }
    }

    @Test
    fun `getLatest returns null when dao returns null`() = runTest(testDispatcher) {
        coEvery {
            dao.getLatest(any(), any())
        } returns null

        val result = repository.getLatest("missing:key", AiCapability.REVIEW_EXPLANATION)

        assertNull(result)
    }

    // ── upsert ────────────────────────────────────────────────────────────────

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `upsert delegates to dao and returns row id`() = runTest(testDispatcher) {
        val record = fakeRecord()
        coEvery { dao.upsert(any()) } returns 42L

        val id = repository.upsert(record)

        assertEquals(42L, id)
        coVerify { dao.upsert(any()) }
    }

    // ── markDismissed ─────────────────────────────────────────────────────────

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `markDismissed delegates to dao`() = runTest(testDispatcher) {
        repository.markDismissed(7L)
        coVerify { dao.markDismissed(id = 7L, dismissed = any(), now = any()) }
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `markApplied delegates to dao`() = runTest(testDispatcher) {
        repository.markApplied(8L)
        coVerify { dao.markApplied(id = 8L, applied = any(), now = any()) }
    }

    // ── deleteExpired ─────────────────────────────────────────────────────────

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `deleteExpired delegates to dao with given timestamp`() = runTest(testDispatcher) {
        val now = 999_999L
        repository.deleteExpired(now)
        coVerify { dao.deleteExpired(now) }
    }

    // ── deleteByTargetKey ─────────────────────────────────────────────────────

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `deleteByTargetKey delegates to dao`() = runTest(testDispatcher) {
        repository.deleteByTargetKey("pending_review:55")
        coVerify { dao.deleteByTargetKey("pending_review:55") }
    }
}
