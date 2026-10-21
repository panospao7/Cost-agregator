package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.domain.categorization.AliasLinkResult
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MerchantNormalizationRepositoryTest {
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val dao = mockk<MerchantNormalizationDao>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var repository: MerchantNormalizationRepository

    @Before
    fun setup() {
        every { timeProvider.now() } returns 1_700_000_000_000L
        repository = MerchantNormalizationRepository(writeBarrier, dao, timeProvider)
    }

    @Test
    fun `linkAliasToCanonical maps DAO result codes to AliasLinkResult`() = runBlocking {
        val aliasCanonical1 = MerchantAlias(id = 5L, rawName = "Test", normalizedKey = "test", canonicalId = 1L)
        val aliasCanonical2 = MerchantAlias(id = 5L, rawName = "Test", normalizedKey = "test", canonicalId = 2L)

        // row label, DAO code, stubbed alias lookup, canonicalId argument, assertion on result
        data class Row(
            val label: String,
            val code: Int,
            val alias: MerchantAlias?,
            val canonicalId: Long,
            val check: (AliasLinkResult) -> Unit
        )
        val rows = listOf(
            Row("code 0 -> Created", 0, aliasCanonical1, 1L) { result ->
                assertTrue(result is AliasLinkResult.Created)
                assertEquals(5L, (result as AliasLinkResult.Created).aliasId)
            },
            Row("code 1 -> UpdatedExisting", 1, aliasCanonical1, 1L) { result ->
                assertTrue(result is AliasLinkResult.UpdatedExisting)
                assertEquals(5L, (result as AliasLinkResult.UpdatedExisting).aliasId)
            },
            Row("code 2 -> Conflict", 2, aliasCanonical2, 1L) { result ->
                assertTrue(result is AliasLinkResult.Conflict)
                assertEquals(2L, (result as AliasLinkResult.Conflict).existingCanonicalId)
                assertEquals(
                    "Alias with normalized key 'test' already linked to a different canonical",
                    result.message
                )
            },
            Row("code 2 with alias deleted -> Conflict with unknown canonical -1", 2, null, 1L) { result ->
                assertTrue(result is AliasLinkResult.Conflict)
                assertEquals(-1L, (result as AliasLinkResult.Conflict).existingCanonicalId)
            },
            Row("code 3 -> CanonicalMissing", 3, null, 999L) { result ->
                assertTrue(result is AliasLinkResult.CanonicalMissing)
                assertEquals(999L, (result as AliasLinkResult.CanonicalMissing).canonicalId)
            },
            Row("unknown code -> Ignored", -1, null, 1L) { result ->
                assertTrue(result is AliasLinkResult.Ignored)
                assertEquals("Unknown result code: -1", (result as AliasLinkResult.Ignored).reason)
            },
            Row("code 0 but alias not found -> Ignored", 0, null, 1L) { result ->
                assertTrue(result is AliasLinkResult.Ignored)
            },
            Row("code 1 but alias not found -> Ignored", 1, null, 1L) { result ->
                assertTrue(result is AliasLinkResult.Ignored)
            }
        )
        assertEquals("linkAliasToCanonical rows size", 8, rows.size)
        rows.forEach { row ->
            coEvery { dao.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns row.code
            coEvery { dao.getAliasByNormalizedKey(any()) } returns row.alias

            val result = repository.linkAliasToCanonical("Test", "test", row.canonicalId, false, 1_700_000_000_000L)
            try {
                row.check(result)
            } catch (e: AssertionError) {
                throw AssertionError("Row '${row.label}' failed: ${e.message}", e)
            }
        }
    }

    @Test
    fun `insertAlias coerces zero timestamps`() = runBlocking {
        val alias = MerchantAlias(rawName = "Test", normalizedKey = "test", canonicalId = 1L, createdAt = 0L, lastUsedAt = 0L)
        coEvery { dao.insertAlias(any()) } returns 5L

        val id = repository.insertAlias(alias)
        assertEquals(5L, id)
        coVerify { dao.insertAlias(match { it.createdAt == 1_700_000_000_000L && it.lastUsedAt == 1_700_000_000_000L }) }
    }

    @Test
    fun `insertAlias bothFallbacksFail_returnsNegativeId`() = runBlocking {
        val alias = MerchantAlias(rawName = "Test", normalizedKey = "test", canonicalId = 1L, createdAt = 1_700_000_000_000L, lastUsedAt = 1_700_000_000_000L)
        coEvery { dao.insertAlias(any()) } returns -1L
        coEvery { dao.incrementAliasOccurrence(any(), any()) } returns null
        coEvery { dao.getAliasByRawName(any()) } returns null

        val id = repository.insertAlias(alias)
        assertEquals(-1L, id)
    }

    @Test
    fun `insertAlias fallback invokes normalizedKey increment`() = runBlocking {
        val alias = MerchantAlias(rawName = "Test", normalizedKey = "test", canonicalId = 1L, createdAt = 1_700_000_000_000L, lastUsedAt = 1_700_000_000_000L)
        coEvery { dao.insertAlias(any()) } returns -1L
        coEvery { dao.incrementAliasOccurrence(any(), any()) } returns MerchantAlias(id = 7L, rawName = "Test", normalizedKey = "test", canonicalId = 1L, occurrenceCount = 2)

        val id = repository.insertAlias(alias)
        assertEquals(7L, id)
        coVerify { dao.incrementAliasOccurrence("test", 1_700_000_000_000L) }
    }

    @Test
    fun `insertAlias fallback falls through to rawName when normalizedKey fails`() = runBlocking {
        val alias = MerchantAlias(rawName = "Test", normalizedKey = "test", canonicalId = 1L, createdAt = 1_700_000_000_000L, lastUsedAt = 1_700_000_000_000L)
        coEvery { dao.insertAlias(any()) } returns -1L
        coEvery { dao.incrementAliasOccurrence(any(), any()) } returns null
        coEvery { dao.getAliasByRawName("Test") } returns MerchantAlias(id = 8L, rawName = "Test", normalizedKey = "test_old", canonicalId = 1L, occurrenceCount = 1)

        val id = repository.insertAlias(alias)
        assertEquals(8L, id)
        coVerify { dao.updateAlias(match { it.occurrenceCount == 2 && it.lastUsedAt == 1_700_000_000_000L }) }
    }
}
