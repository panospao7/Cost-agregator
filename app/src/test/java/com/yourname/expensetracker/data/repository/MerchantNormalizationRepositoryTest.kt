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
    fun `linkAliasToCanonical maps code 0 to Created`() = runBlocking {
        coEvery { dao.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns 0
        coEvery { dao.getAliasByNormalizedKey(any()) } returns MerchantAlias(id = 5L, rawName = "Test", normalizedKey = "test", canonicalId = 1L)

        val result = repository.linkAliasToCanonical("Test", "test", 1L, false, 1_700_000_000_000L)
        assertTrue(result is AliasLinkResult.Created)
        assertEquals(5L, (result as AliasLinkResult.Created).aliasId)
    }

    @Test
    fun `linkAliasToCanonical maps code 1 to UpdatedExisting`() = runBlocking {
        coEvery { dao.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns 1
        coEvery { dao.getAliasByNormalizedKey(any()) } returns MerchantAlias(id = 5L, rawName = "Test", normalizedKey = "test", canonicalId = 1L)

        val result = repository.linkAliasToCanonical("Test", "test", 1L, false, 1_700_000_000_000L)
        assertTrue(result is AliasLinkResult.UpdatedExisting)
        assertEquals(5L, (result as AliasLinkResult.UpdatedExisting).aliasId)
    }

    @Test
    fun `linkAliasToCanonical maps code 2 to Conflict`() = runBlocking {
        coEvery { dao.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns 2
        coEvery { dao.getAliasByNormalizedKey(any()) } returns MerchantAlias(id = 5L, rawName = "Test", normalizedKey = "test", canonicalId = 2L)

        val result = repository.linkAliasToCanonical("Test", "test", 1L, false, 1_700_000_000_000L)
        assertTrue(result is AliasLinkResult.Conflict)
        assertEquals(2L, (result as AliasLinkResult.Conflict).existingCanonicalId)
        assertEquals("Alias with normalized key 'test' already linked to a different canonical", (result as AliasLinkResult.Conflict).message)
    }

    @Test
    fun `linkAliasToCanonical maps code 3 to CanonicalMissing`() = runBlocking {
        coEvery { dao.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns 3

        val result = repository.linkAliasToCanonical("Test", "test", 999L, false, 1_700_000_000_000L)
        assertTrue(result is AliasLinkResult.CanonicalMissing)
        assertEquals(999L, (result as AliasLinkResult.CanonicalMissing).canonicalId)
    }

    @Test
    fun `linkAliasToCanonical maps unknown code to Ignored`() = runBlocking {
        coEvery { dao.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns -1

        val result = repository.linkAliasToCanonical("Test", "test", 1L, false, 1_700_000_000_000L)
        assertTrue(result is AliasLinkResult.Ignored)
        assertEquals("Unknown result code: -1", (result as AliasLinkResult.Ignored).reason)
    }

    @Test
    fun `linkAliasToCanonical createdButNotFound_returnsIgnored`() = runBlocking {
        coEvery { dao.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns 0
        coEvery { dao.getAliasByNormalizedKey(any()) } returns null

        val result = repository.linkAliasToCanonical("Test", "test", 1L, false, 1_700_000_000_000L)
        assertTrue(result is AliasLinkResult.Ignored)
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
