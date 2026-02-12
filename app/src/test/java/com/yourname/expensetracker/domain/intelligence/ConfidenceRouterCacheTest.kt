package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConfidenceRouterCacheTest {

    private lateinit var router: ConfidenceRouter
    private val sourceStatsDao = mockk<SourceStatsDao>(relaxed = true)
    private val userCorrectionDao = mockk<UserCorrectionDao>(relaxed = true)
    private val classifier = FakeClassifier()

    class FakeClassifier : ITransactionClassifier {
        override suspend fun initialize() {}
        override suspend fun predict(text: String): Float = 0.5f
        override suspend fun train(text: String, isTransaction: Boolean) {}
        override fun retrainFromCorrections() {}
        override fun getStats(): ClassifierStats = ClassifierStats(0, 0, 0, false)
        override val stats: StateFlow<ClassifierStats> = MutableStateFlow(ClassifierStats(0, 0, 0, false))
    }

    @Before
    fun setup() {
        router = ConfidenceRouter(sourceStatsDao, userCorrectionDao, classifier)

        // Ensure cache is clear before test
        router.invalidateCache()
    }

    private fun makeParsed() =
        ParsedTransaction(10.0, "EUR", "Merchant", TransactionType.PURCHASE, 0.9f)

    @Test
    fun `source stats are cached`() = runBlocking {
        // Setup DAO to return stats
        val stats = SourceStats("com.pkg", totalNotifications = 20, acceptedAsExpense = 18)
        coEvery { sourceStatsDao.getByPackage("com.pkg") } returns stats

        // First call - should hit DAO
        router.route(makeParsed(), "com.pkg")
        coVerify(exactly = 1) { sourceStatsDao.getByPackage("com.pkg") }

        // Second call - should hit cache
        router.route(makeParsed(), "com.pkg")
        coVerify(exactly = 1) { sourceStatsDao.getByPackage("com.pkg") }
    }

    @Test
    fun `merchant rejection rate is cached`() = runBlocking {
        coEvery { userCorrectionDao.getMerchantTotalCorrections("Merchant") } returns 10

        // First call
        router.route(makeParsed(), "com.pkg")
        coVerify(exactly = 1) { userCorrectionDao.getMerchantTotalCorrections("Merchant") }

        // Second call
        router.route(makeParsed(), "com.pkg")
        coVerify(exactly = 1) { userCorrectionDao.getMerchantTotalCorrections("Merchant") }
    }
}
