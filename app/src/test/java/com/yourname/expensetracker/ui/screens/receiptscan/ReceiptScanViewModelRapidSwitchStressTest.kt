package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.RateRefusalReason
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockOrigin
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockVariant
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/**
 * MEC C1 stress: rapid currency / amount / date / scan switching on the receipt review. Latest wins; a
 * slow older evaluation never overwrites a newer one; a refusal is never overwritten by a late preview.
 * Synthetic data only; the device zone and locale are restored after every test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptScanViewModelRapidSwitchStressTest : ViewModelTestUtils() {
    private lateinit var deps: ReceiptScanMecDeps
    private lateinit var model: ReceiptScanViewModel
    private val originalZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()
    private val st get() = model.state.value
    private val region get() = st.currencyRegion

    @Before
    override fun setup() {
        super.setup()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        deps = ReceiptScanMecDeps(testDispatcher, MEC_DATE)
        coEvery { deps.links.checkCanLinkReceipt(any()) } returns true
        deps.store.putRate("USD", 0.9, mecDay(14))
        deps.store.putRate("GBP", 1.1, mecDay(13))
        deps.store.putRate("JPY", 0.006, mecDay(12))
        model = deps.createViewModel()
        settle()
        setState(ReceiptScanState(
            step = ScanStep.REVIEW, receiptId = 17L, editMerchant = "Merchant", editAmount = "10",
            editCurrency = "EUR", editDate = MEC_DATE
        ))
        model.onReviewOpened()
        settle()
    }

    @After
    override fun tearDown() {
        try {
            TimeZone.setDefault(originalZone)
            Locale.setDefault(originalLocale)
        } finally {
            super.tearDown()
        }
    }

    private fun settle() = testDispatcher.scheduler.advanceUntilIdle()
    private fun runNow() = testDispatcher.scheduler.runCurrent()

    private fun setState(state: ReceiptScanState) {
        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(model) as MutableStateFlow<ReceiptScanState>).value = state
    }

    private fun rateDate(): Any? = (region as? CurrencyRegionUi.Eligible)?.rateDate

    @Test
    fun `stress - a slow older currency evaluation never overwrites a newer one`() {
        deps.store.latencyMs = { from, _, _ -> if (from == "USD") 1_000L else 10L }
        model.updateCurrency("USD")
        model.updateCurrency("GBP")
        settle()
        assertEquals(mecDay(13), rateDate())

        deps.store.latencyMs = { from, _, _ -> if (from == "GBP") 1_000L else 10L }
        model.updateCurrency("GBP")
        model.updateCurrency("USD")
        settle()
        assertEquals(mecDay(14), rateDate())
    }

    @Test
    fun `stress - sixty rapid currency switches settle on the last pick only`() {
        val picks = listOf("USD", "GBP", "JPY", "EUR")
        deps.store.latencyMs = { from, _, at -> ((from.hashCode() + at) % 7).let { if (it < 0) -it else it } * 300L }
        for (i in 0 until 60) {
            model.updateCurrency(picks[i % picks.size])
        }
        settle()
        // i = 59 -> index 3 -> EUR (home)
        assertEquals("EUR", st.editCurrency)
        assertEquals(CurrencyRegionUi.Home, region)

        for (i in 0 until 59) {
            model.updateCurrency(picks[i % picks.size])
        }
        settle()
        // i = 58 -> index 2 -> JPY
        assertEquals("JPY", st.editCurrency)
        assertEquals(mecDay(12), rateDate())
    }

    @Test
    fun `stress - fifty rapid amount edits show the converted amount of the last edit only`() {
        model.updateCurrency("USD")
        settle()
        deps.store.latencyMs = { _, _, _ -> 400L }
        for (amount in 1..50) {
            model.updateAmount(amount.toString())
        }
        settle()
        val eligible = region as CurrencyRegionUi.Eligible
        assertEquals("50", st.editAmount)
        assertTrue(eligible.formattedAmountInHome!!.contains("45.00"))
    }

    @Test
    fun `stress - rapid date edits end with the evaluation of the last date`() {
        model.updateCurrency("USD")
        settle()
        deps.store.latencyMs = { _, _, at -> if (at < mecUtcMidnight(mecDay(20))) 900L else 5L }
        for (day in 14..28) {
            model.updateDate(MEC_DATE + (day - 14) * MEC_DAY_MS)
        }
        settle()
        // Last date 28 Nov: 14 days after the 14 Nov rate.
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, mecDay(14), CurrencyBlockOrigin.PREVIEW), region)
    }

    @Test
    fun `stress - a refusal survives a slow evaluation and a later currency switch replaces it`() {
        model.updateCurrency("USD")
        settle()
        val gate = CompletableDeferred<Unit>()
        deps.store.gate = gate
        model.updateAmount("11")
        runNow()
        deps.saveOutcome = {
            kotlin.Result.failure(ReceiptLifecycleCoordinator.RateNotEligibleException(RateRefusalReason.NO_RATE, null))
        }
        model.saveExpense()
        runNow()
        val blocked = CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.SAVE)
        assertEquals(blocked, region)
        gate.complete(Unit)
        settle()
        assertEquals(blocked, region)

        deps.store.gate = null
        model.updateCurrency("GBP")
        settle()
        assertEquals(mecDay(13), rateDate())
        assertNull(st.saveResult)
    }

    @Test
    fun `stress - rapid double tap on Save reaches the coordinator once`() {
        model.updateCurrency("USD")
        settle()
        deps.saveOutcome = {
            kotlin.Result.failure(ReceiptLifecycleCoordinator.RateNotEligibleException(RateRefusalReason.NO_RATE, null))
        }
        repeat(5) { model.saveExpense() }
        settle()
        assertEquals(1, deps.requests.size)
        assertFalse(st.isSaving)
        assertEquals(SaveReceiptResult.RateNotEligible, st.saveResult)
    }

    @Test
    fun `stress - rapid scan switching keeps only the last receipt and returns to home`() {
        val first = Uri.parse("content://synthetic/stress-a.jpg")
        val second = Uri.parse("content://synthetic/stress-b.jpg")
        val slow = CompletableDeferred<ReceiptLifecycleCoordinator.ReceiptProcessOutcome>()
        coEvery { deps.coordinator.processReceiptInput(first, any()) } coAnswers { kotlin.Result.success(slow.await()) }
        coEvery { deps.coordinator.processReceiptInput(second, any()) } returns kotlin.Result.success(
            ReceiptLifecycleCoordinator.ReceiptProcessOutcome(mecReceipt(id = 18L, currency = "EUR", total = 5.0), true, null)
        )

        model.processGalleryImage(first)
        runNow()
        model.processGalleryImage(second)
        settle()
        slow.complete(ReceiptLifecycleCoordinator.ReceiptProcessOutcome(mecReceipt(id = 17L, currency = "USD"), true, null))
        settle()

        assertEquals(18L, st.receiptId)
        assertEquals("EUR", st.editCurrency)
        assertEquals("5.00", st.editAmount)
        assertEquals(CurrencyRegionUi.Home, region)
    }
}
