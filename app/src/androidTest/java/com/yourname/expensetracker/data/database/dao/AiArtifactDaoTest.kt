package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiArtifactDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: AiArtifactDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.aiArtifactDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun makeArtifact(
        targetKey: String = "pending_review:1",
        capability: AiCapability = AiCapability.REVIEW_EXPLANATION,
        status: AiArtifactStatus = AiArtifactStatus.READY,
        promptVersion: String = "v1",
        sourceHash: String = "hash_abc",
        summaryText: String? = "Headline",
        explanationText: String? = "Body text",
        expiresAt: Long? = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000L,
        now: Long = System.currentTimeMillis()
    ) = AiArtifactEntity(
        targetType      = AiTargetType.PENDING_REVIEW,
        targetKey       = targetKey,
        capability      = capability,
        status          = status,
        mode            = AiMode.AUTO,
        promptVersion   = promptVersion,
        sourceHash      = sourceHash,
        summaryText     = summaryText,
        explanationText = explanationText,
        createdAt       = now,
        updatedAt       = now,
        expiresAt       = expiresAt
    )

    // ── upsert ────────────────────────────────────────────────────────────────

    @Test
    fun upsert_insertsNewArtifactAndReturnsPositiveId() = runBlocking {
        val id = dao.upsert(makeArtifact())
        assertTrue("Returned id should be > 0", id > 0)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun upsert_replacesDuplicateOnUniqueKey() = runBlocking {
        // Insert first artifact
        val first = makeArtifact(summaryText = "First")
        dao.upsert(first)

        // Insert same (targetKey, capability, promptVersion, sourceHash) with updated text
        val second = makeArtifact(summaryText = "Updated")
        dao.upsert(second)

        // Only one row should remain and it should have the updated text
        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("Updated", all[0].summaryText)
    }

    @Test
    fun upsert_doesNotReplaceWhenSourceHashDiffers() = runBlocking {
        dao.upsert(makeArtifact(sourceHash = "hash_1"))
        dao.upsert(makeArtifact(sourceHash = "hash_2"))

        // Two distinct rows because sourceHash differs
        assertEquals(2, dao.getAll().size)
    }

    // ── getLatest ─────────────────────────────────────────────────────────────

    @Test
    fun getLatest_returnsNullWhenTableEmpty() = runBlocking {
        val result = dao.getLatest("pending_review:999", AiCapability.REVIEW_EXPLANATION.name)
        assertNull(result)
    }

    @Test
    fun getLatest_returnsRowForMatchingKey() = runBlocking {
        val artifact = makeArtifact(targetKey = "pending_review:42")
        dao.upsert(artifact)

        val result = dao.getLatest("pending_review:42", AiCapability.REVIEW_EXPLANATION.name)
        assertNotNull(result)
        assertEquals("pending_review:42", result!!.targetKey)
    }

    @Test
    fun getLatest_doesNotReturnRowForDifferentCapability() = runBlocking {
        dao.upsert(makeArtifact(capability = AiCapability.DASHBOARD_BRIEFING))

        val result = dao.getLatest("pending_review:1", AiCapability.REVIEW_EXPLANATION.name)
        assertNull(result)
    }

    @Test
    fun getLatest_returnsMostRecentlyUpdatedWhenMultipleRows() = runBlocking {
        val base = System.currentTimeMillis()
        val older = makeArtifact(sourceHash = "hash_old", summaryText = "Older", now = base)
        val newer = makeArtifact(sourceHash = "hash_new", summaryText = "Newer", now = base + 1000)
        dao.upsert(older)
        dao.upsert(newer)

        val result = dao.getLatest("pending_review:1", AiCapability.REVIEW_EXPLANATION.name)
        assertEquals("Newer", result!!.summaryText)
    }

    // ── observeLatest ─────────────────────────────────────────────────────────

    @Test
    fun observeLatest_emitsNullWhenNoRowExists() = runBlocking {
        val result = dao.observeLatest("no_such:key", AiCapability.REVIEW_EXPLANATION.name).first()
        assertNull(result)
    }

    @Test
    fun observeLatest_emitsRowAfterUpsert() = runBlocking {
        dao.upsert(makeArtifact(targetKey = "pending_review:5"))
        val result = dao.observeLatest("pending_review:5", AiCapability.REVIEW_EXPLANATION.name).first()
        assertNotNull(result)
        assertEquals("pending_review:5", result!!.targetKey)
    }

    // ── deleteExpired ─────────────────────────────────────────────────────────

    @Test
    fun deleteExpired_removesOnlyExpiredArtifacts() = runBlocking {
        val now = System.currentTimeMillis()
        val expired = makeArtifact(
            sourceHash = "expired",
            targetKey  = "pending_review:10",
            expiresAt  = now - 1000L
        )
        val fresh = makeArtifact(
            sourceHash = "fresh",
            targetKey  = "pending_review:11",
            expiresAt  = now + 1_000_000L
        )
        dao.upsert(expired)
        dao.upsert(fresh)

        dao.deleteExpired(now)

        val remaining = dao.getAll()
        assertEquals(1, remaining.size)
        assertEquals("pending_review:11", remaining[0].targetKey)
    }

    @Test
    fun deleteExpired_doesNotDeleteArtifactsWithNullExpiresAt() = runBlocking {
        val noExpiry = makeArtifact(expiresAt = null)
        dao.upsert(noExpiry)

        dao.deleteExpired(Long.MAX_VALUE)

        assertEquals(1, dao.getAll().size)
    }

    // ── deleteByTargetKey ─────────────────────────────────────────────────────

    @Test
    fun deleteByTargetKey_removesAllArtifactsForKey() = runBlocking {
        dao.upsert(makeArtifact(
            targetKey  = "pending_review:99",
            capability = AiCapability.REVIEW_EXPLANATION,
            sourceHash = "s1"
        ))
        dao.upsert(makeArtifact(
            targetKey  = "pending_review:99",
            capability = AiCapability.REVIEW_EXPLANATION,
            sourceHash = "s2"
        ))
        // Unrelated key — should survive
        dao.upsert(makeArtifact(targetKey = "pending_review:100", sourceHash = "s3"))

        dao.deleteByTargetKey("pending_review:99")

        val remaining = dao.getAll()
        assertEquals(1, remaining.size)
        assertEquals("pending_review:100", remaining[0].targetKey)
    }

    @Test
    fun deleteByTargetKey_isNoOpWhenKeyAbsent() = runBlocking {
        dao.upsert(makeArtifact())
        dao.deleteByTargetKey("nonexistent:key")
        assertEquals(1, dao.getAll().size)
    }

    // ── unique key constraint detail ──────────────────────────────────────────

    @Test
    fun upsert_uniqueKeyDistinguishesByPromptVersion() = runBlocking {
        dao.upsert(makeArtifact(promptVersion = "v1", summaryText = "V1 result"))
        dao.upsert(makeArtifact(promptVersion = "v2", summaryText = "V2 result"))

        // Both rows should coexist because promptVersion differs
        assertEquals(2, dao.getAll().size)
    }
}
