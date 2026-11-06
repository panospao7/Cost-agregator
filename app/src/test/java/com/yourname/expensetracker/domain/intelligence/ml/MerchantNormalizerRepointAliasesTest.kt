package com.yourname.expensetracker.domain.intelligence.ml

import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.categorization.AliasLinkResult
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * E-03-6: learnMerchantAlias repoints the old canonical's aliases with ONE repository call
 * (a single UPDATE) instead of a per-alias updateAlias loop.
 */
class MerchantNormalizerRepointAliasesTest {

    private val repository = mockk<MerchantNormalizationRepository>(relaxed = true)
    private val greeklishNormalizer = mockk<GreeklishNormalizer>(relaxed = true)
    private val context = mockk<android.content.Context>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var normalizer: MerchantNormalizer

    private val now = 1_712_000_000_000L
    private val brand = MerchantCanonical(id = 5L, normalizedName = "Starbucks", searchKey = "starbucks")
    private val oldCanonical = MerchantCanonical(id = 9L, normalizedName = "Old Pos", searchKey = "oldpos")

    @Before
    fun setup() {
        every { timeProvider.now() } returns now
        normalizer = MerchantNormalizer(
            repository, MerchantRulesRepository(), greeklishNormalizer, context, timeProvider
        )
        coEvery { repository.getAliasByNormalizedKey(any()) } returns null
        coEvery { repository.getTopMerchants(any()) } returns listOf(brand)
        coEvery { repository.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns AliasLinkResult.Created(1L)
    }

    @Test
    fun `E-03-6 learnMerchantAlias repoints old canonical aliases once and never calls updateAlias`() = runBlocking {
        coEvery { repository.getCanonicalBySearchKey(any()) } answers {
            if (firstArg<String>() == "starbucks") brand else oldCanonical
        }

        normalizer.learnMerchantAlias("SBUX Synthetic Store 4412", "Starbucks")

        coVerify(exactly = 1) { repository.repointAliases(oldCanonical.id, brand.id, now) }
        coVerify(exactly = 0) { repository.updateAlias(any()) }
        coVerify(exactly = 0) { repository.getAliasesForCanonical(any()) }
    }

    @Test
    fun `E-03-6 learnMerchantAlias with no old canonical does not repoint`() = runBlocking {
        coEvery { repository.getCanonicalBySearchKey(any()) } answers {
            if (firstArg<String>() == "starbucks") brand else null
        }

        normalizer.learnMerchantAlias("SBUX Synthetic Store 4412", "Starbucks")

        coVerify(exactly = 0) { repository.repointAliases(any(), any(), any()) }
        coVerify(exactly = 0) { repository.updateAlias(any()) }
    }

    @Test
    fun `E-03-6 learnMerchantAlias when old canonical is the brand itself does not repoint`() = runBlocking {
        coEvery { repository.getCanonicalBySearchKey(any()) } returns brand

        normalizer.learnMerchantAlias("SBUX Synthetic Store 4412", "Starbucks")

        coVerify(exactly = 0) { repository.repointAliases(any(), any(), any()) }
        coVerify(exactly = 0) { repository.updateAlias(any()) }
    }

    @Test
    fun `E-03-6 learnMerchantAlias with a blank brand touches no alias repository write`() = runBlocking {
        normalizer.learnMerchantAlias("SBUX Synthetic Store 4412", "  ")

        coVerify(exactly = 0) { repository.repointAliases(any(), any(), any()) }
        coVerify(exactly = 0) { repository.updateAlias(any()) }
    }
}
