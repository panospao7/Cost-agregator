package com.yourname.expensetracker.domain.intelligence.ml

import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.categorization.AliasLinkResult
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * E-03-8: learning a merchant alias logs a fixed message only. The raw POS name and the brand
 * name (user merchant text) must never reach the log, as message text or throwable.
 */
class MerchantNormalizerAliasLogPrivacyTest {

    private class Entry(val priority: Int, val message: String, val throwable: Throwable?)

    private val repository = mockk<MerchantNormalizationRepository>(relaxed = true)
    private val greeklishNormalizer = mockk<GreeklishNormalizer>(relaxed = true)
    private val context = mockk<android.content.Context>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var normalizer: MerchantNormalizer

    private val brand = MerchantCanonical(id = 5L, normalizedName = "Starbucks", searchKey = "starbucks")

    @Before
    fun setup() {
        every { timeProvider.now() } returns 1_712_000_000_000L
        normalizer = MerchantNormalizer(
            repository, MerchantRulesRepository(), greeklishNormalizer, context, timeProvider
        )
        coEvery { repository.getAliasByNormalizedKey(any()) } returns null
        coEvery { repository.getCanonicalBySearchKey(any()) } returns null
        coEvery { repository.getCanonicalBySearchKey("starbucks") } returns brand
        coEvery { repository.getTopMerchants(any()) } returns listOf(brand)
        coEvery { repository.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns AliasLinkResult.Created(1L)
    }

    private fun captureLogs(block: () -> Unit): List<Entry> {
        val entries = mutableListOf<Entry>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                entries += Entry(priority, message, t)
            }
        }
        Timber.plant(tree)
        try {
            block()
        } finally {
            Timber.uproot(tree)
        }
        return entries
    }

    @Test
    fun `E-03-8 learnMerchantAlias logs the fixed message and no merchant text`() {
        val rawName = "SBUX Synthetic Store 4412"

        val entries = captureLogs {
            runBlocking { normalizer.learnMerchantAlias(rawName, "Starbucks") }
        }

        // Control: the learn path really ran and logged.
        assertTrue(
            "expected the fixed 'Learned alias' entry, got ${entries.map { it.message }}",
            entries.any { it.message == "Learned alias" }
        )
        entries.forEach { entry ->
            assertNull("no throwable may be attached", entry.throwable)
            listOf("SBUX", "Synthetic", "4412", "Starbucks", "starbucks").forEach { leaked ->
                assertFalse(
                    "log leaked merchant text '$leaked': ${entry.message}",
                    entry.message.contains(leaked, ignoreCase = true)
                )
            }
        }
        assertEquals(
            "exactly one alias log entry",
            1,
            entries.count { it.message.startsWith("Learned alias") }
        )
    }

    @Test
    fun `E-03-8 learnMerchantAlias with a blank brand logs nothing`() {
        val entries = captureLogs {
            runBlocking { normalizer.learnMerchantAlias("SBUX Synthetic Store 4412", "  ") }
        }

        assertTrue(entries.none { it.message.contains("Learned alias") })
    }
}
