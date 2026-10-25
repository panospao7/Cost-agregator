package com.yourname.expensetracker.domain.installment.lifecycle

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.ATHENS
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.athensMillis
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.day
import com.yourname.expensetracker.domain.installment.model.InstallmentStatusReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AE-23 subset (INV-31): after declare, confirm, link, adjust, skip, unlink, a lost compare-and-set,
 * a constraint hit, a contained reconcile failure and a reconcile, no captured log line contains a
 * sentinel display name, lender label, merchant, notes, amount, SQL message, exception message or
 * due date. The failing paths guarantee the log is not empty, so the test cannot pass vacuously.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentPlanLifecycleCoordinatorPrivacyLogTest {

    private lateinit var fx: InstallmentCoordinatorFixture
    private lateinit var coordinator: InstallmentPlanLifecycleCoordinator

    @Before
    fun setUp() = runBlocking {
        fx = InstallmentCoordinatorFixture.create(ApplicationProvider.getApplicationContext())
        coordinator = fx.coordinator()
    }

    @After
    fun tearDown() {
        if (::fx.isInitialized) fx.close()
    }

    @Test
    fun AE23_no_sentinel_text_amount_or_date_reaches_any_log_line_across_the_lifecycle() = runTest {
        val name = "SENTINEL-NAME-7731"
        val lender = "SENTINEL-LENDER-4410"
        val merchant = "SENTINEL-MERCHANT-2290"
        val note = "SENTINEL-NOTE-9915"
        val sqlDetail = "SENTINEL-SQL-DETAIL-5521"
        val exceptionDetail = "SENTINEL-EXCEPTION-3308"
        val forbidden = listOf(
            name, lender, merchant, note, sqlDetail, exceptionDetail,
            "6543.21", "654321", "4321.09", "432109", "1234.56", "123456", "777.77", "77777",
            "2027-04-10", "2027-05-10", day(2027, 4, 10).toString(), day(2027, 5, 10).toString()
        )
        val tree = CapturingTree()
        tree.plant()
        try {
            // declare on an existing purchase and with a new purchase (notes carry a sentinel)
            val existing = fx.seedExpense(amount = 6543.21, merchant = merchant)
            val declared = coordinator.declareOnExpense(
                existing,
                fx.terms(displayName = name, lender = lender, total = 6543.21, count = 3, firstDue = day(2027, 4, 10)),
                ATHENS
            )
            assertTrue(declared is InstallmentOpResult.Done)
            val created = coordinator.declareWithNewExpense(
                fx.purchaseRequest(amount = 4321.09, merchant = "$merchant-2").copy(notes = note),
                ATHENS,
                fx.terms(planUuid = "privacy-2", displayName = name, lender = lender, total = 4321.09, count = 3)
            )
            assertTrue(created is InstallmentOpResult.Done)

            // standalone plan: confirm, link, adjust, skip, unlink
            val planId = (coordinator.createStandalone(
                fx.standaloneTerms(planUuid = "privacy-3", fixed = 1234.56).copy(displayName = name, lenderLabel = lender),
                fx.categoryId,
                PaymentMethod.CARD
            ) as InstallmentOpResult.Done).value.planId
            val entries = fx.entries(planId)
            assertTrue(coordinator.confirmEntry(entries[0].id, day(2027, 5, 12), ATHENS) is InstallmentOpResult.Done)
            val captured = fx.seedCharge(athensMillis(2027, 5, 10), amount = 1234.56)
            assertTrue(coordinator.linkExpense(entries[1].id, captured) is InstallmentOpResult.Done)
            assertTrue(coordinator.adjustEntry(entries[2].id, 777.77, null) is InstallmentOpResult.Done)
            assertTrue(
                coordinator.skip(entries[3].id, InstallmentStatusReason.USER_SKIP_OTHER) is InstallmentOpResult.Done
            )
            assertTrue(coordinator.unlinkExpense(entries[1].id) is InstallmentOpResult.Done)

            // lost compare-and-set
            val racing = HookedPlanDao(fx.planDao)
            racing.claimResult = 0
            assertEquals(
                InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT),
                fx.coordinator(dao = racing).linkExpense(entries[1].id, captured)
            )

            // constraint hit with a sentinel SQL message
            fx.exec(
                "CREATE TRIGGER synthetic_privacy BEFORE UPDATE ON installment_entries " +
                    "BEGIN SELECT RAISE(ABORT, '$sqlDetail'); END"
            )
            assertEquals(
                InstallmentOpResult.Failed(InstallmentOpFailure.CONSTRAINT_VIOLATION),
                coordinator.skip(entries[4].id, InstallmentStatusReason.USER_SKIP_WAIVED)
            )
            fx.exec("DROP TRIGGER synthetic_privacy")

            // contained reconcile failure with a sentinel exception message, then a healthy reconcile
            val throwing = HookedPlanDao(fx.planDao)
            throwing.getPlanByIdHook = { throw IllegalStateException(exceptionDetail) }
            val contained = fx.coordinator(dao = throwing).reconcile() as InstallmentOpResult.Done
            assertTrue(contained.value.plansFailed > 0)
            assertTrue(coordinator.reconcile() is InstallmentOpResult.Done)
        } finally {
            tree.uproot()
        }

        assertTrue("the failing paths must have logged something", tree.lines.isNotEmpty())
        assertTrue(tree.lines.any { it.contains("CONSTRAINT_VIOLATION") })
        assertTrue(tree.lines.any { it.contains("InstallmentReconcile") })
        for (line in tree.lines) {
            for (sentinel in forbidden) {
                assertTrue("a log line leaked '$sentinel': $line", !line.contains(sentinel))
            }
        }
    }
}
