package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiArtifactDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: AiArtifactDao

    @Before
    fun setup() {
        database = AppDatabaseTestFactory.create(ApplicationProvider.getApplicationContext())
        dao = database.aiArtifactDao()
    }

    @After
    fun teardown() {
        if (::database.isInitialized) database.close()
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
        expiresAt: Long? = BASE_NOW + 30L * 24 * 60 * 60 * 1000L,
        now: Long = BASE_NOW
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
    fun upsert_insertsNewArtifactAndReturnsPositiveId() = runTest {
        val id = dao.upsert(makeArtifact())
        assertTrue("Returned id should be > 0", id > 0)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun upsert_replacesDuplicateOnUniqueKey() = runTest {
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
    fun upsert_doesNotReplaceWhenSourceHashDiffers() = runTest {
        dao.upsert(makeArtifact(sourceHash = "hash_1"))
        dao.upsert(makeArtifact(sourceHash = "hash_2"))

        // Two distinct rows because sourceHash differs
        assertEquals(2, dao.getAll().size)
    }

    // ── getLatest ─────────────────────────────────────────────────────────────

    @Test
    fun getLatest_returnsNullWhenTableEmpty() = runTest {
        val result = dao.getLatest("pending_review:999", AiCapability.REVIEW_EXPLANATION.name)
        assertNull(result)
    }

    @Test
    fun getLatest_returnsRowForMatchingKey() = runTest {
        val artifact = makeArtifact(targetKey = "pending_review:42")
        dao.upsert(artifact)

        val result = dao.getLatest("pending_review:42", AiCapability.REVIEW_EXPLANATION.name)
        assertNotNull(result)
        assertEquals("pending_review:42", result!!.targetKey)
    }

    @Test
    fun getLatest_doesNotReturnRowForDifferentCapability() = runTest {
        dao.upsert(makeArtifact(capability = AiCapability.DASHBOARD_BRIEFING))

        val result = dao.getLatest("pending_review:1", AiCapability.REVIEW_EXPLANATION.name)
        assertNull(result)
    }

    @Test
    fun getLatest_returnsMostRecentlyUpdatedWhenMultipleRows() = runTest {
        val base = BASE_NOW
        val older = makeArtifact(sourceHash = "hash_old", summaryText = "Older", now = base)
        val newer = makeArtifact(sourceHash = "hash_new", summaryText = "Newer", now = base + 1000)
        dao.upsert(older)
        dao.upsert(newer)

        val result = dao.getLatest("pending_review:1", AiCapability.REVIEW_EXPLANATION.name)
        assertEquals("Newer", result!!.summaryText)
    }

    // ── observeLatest ─────────────────────────────────────────────────────────

    @Test
    fun observeLatest_emitsNullWhenNoRowExists() = runTest {
        val result = dao.observeLatest("no_such:key", AiCapability.REVIEW_EXPLANATION.name).first()
        assertNull(result)
    }

    @Test
    fun observeLatest_emitsRowAfterUpsert() = runTest {
        dao.upsert(makeArtifact(targetKey = "pending_review:5"))
        val result = dao.observeLatest("pending_review:5", AiCapability.REVIEW_EXPLANATION.name).first()
        assertNotNull(result)
        assertEquals("pending_review:5", result!!.targetKey)
    }

    // ── deleteExpired (RP-14 P8-004: now + null-expiry backstop) ─────────────

    @Test
    fun deleteExpired_removesOnlyExpiredArtifacts() = runTest {
        val now = BASE_NOW
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

        // nullExpiryCutoff in the far future would sweep ALL null-expiry rows;
        // these rows have an expiry, so passing a backstop of `now` is safe here.
        dao.deleteExpired(now, nullExpiryCutoff = now)

        val remaining = dao.getAll()
        assertEquals(1, remaining.size)
        assertEquals("pending_review:11", remaining[0].targetKey)
    }

    @Test
    fun deleteExpired_keepsRecentNullExpiryArtifacts_andDeletesOldNullExpiryOnes() = runTest {
        val now = BASE_NOW
        // Recent null-expiry row: inside the backstop window — kept.
        val recentNoExpiry = makeArtifact(sourceHash = "recent_null", expiresAt = null, now = now)
        // Old null-expiry row: updatedAt past the backstop cutoff — deleted.
        val oldNoExpiry = makeArtifact(
            sourceHash = "old_null",
            expiresAt = null,
            now = now - 40L * 24 * 60 * 60 * 1000L
        )
        dao.upsert(recentNoExpiry)
        dao.upsert(oldNoExpiry)

        // RP-14 P8-004: pass now and the null-expiry backstop cutoff explicitly.
        dao.deleteExpired(now, nullExpiryCutoff = now - 30L * 24 * 60 * 60 * 1000L)

        val remaining = dao.getAll()
        assertEquals(1, remaining.size)
        assertEquals("recent_null", remaining[0].sourceHash)
    }

    // ── deleteByTargetKey ─────────────────────────────────────────────────────

    @Test
    fun deleteByTargetKey_removesAllArtifactsForKey() = runTest {
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
    fun deleteByTargetKey_isNoOpWhenKeyAbsent() = runTest {
        dao.upsert(makeArtifact())
        dao.deleteByTargetKey("nonexistent:key")
        assertEquals(1, dao.getAll().size)
    }

    // ── unique key constraint detail ──────────────────────────────────────────

    @Test
    fun upsert_uniqueKeyDistinguishesByPromptVersion() = runTest {
        dao.upsert(makeArtifact(promptVersion = "v1", summaryText = "V1 result"))
        dao.upsert(makeArtifact(promptVersion = "v2", summaryText = "V2 result"))

        // Both rows should coexist because promptVersion differs
        assertEquals(2, dao.getAll().size)
    }

    private companion object {
        /** Fixed base instant (2026-05-15 12:00 UTC); replaces the wall clock. */
        val BASE_NOW: Long = dateMs(2026, 5, 15, 12, 0)
    }
}
