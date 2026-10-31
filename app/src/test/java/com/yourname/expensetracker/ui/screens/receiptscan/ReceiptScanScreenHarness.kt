package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import io.mockk.coEvery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows.shadowOf

/**
 * manual-entry-currency C2: builds REAL [ReceiptScanViewModel]s (over [ReceiptScanMecDeps]) for the Robolectric
 * Compose screen tests. Only peripheral collaborators are doubles (repositories, clock, the receipt
 * coordinator, the rate store fake, the rates repository fake). The preview use case, eligibility evaluator,
 * converter and view model are the real classes.
 *
 * No test dispatcher is installed: `viewModelScope` runs on the Robolectric main looper and the converter uses
 * [Dispatchers.Unconfined], so evaluations complete inline. Synthetic data only.
 */
internal class ReceiptScanScreenHarness(home: String = "EUR") {

    val deps = ReceiptScanMecDeps(Dispatchers.Unconfined, MEC_DATE).also { it.homeFlow.value = home }
    private val created = mutableListOf<ReceiptScanViewModel>()

    init {
        coEvery { deps.links.checkCanLinkReceipt(any()) } returns true
    }

    /** Cancels the `viewModelScope` of every created view model (idempotent); call from @After. */
    fun close() {
        created.forEach { it.viewModelScope.cancel() }
        created.clear()
    }

    fun createViewModel(): ReceiptScanViewModel = deps.createViewModel().also { created += it }

    /** Runs a gallery scan of [receipt] to the REVIEW step (the image preview is dropped: no image loading). */
    fun scan(model: ReceiptScanViewModel, receipt: ScannedReceipt) {
        val uri = Uri.parse("content://synthetic/receipt-${receipt.id}.jpg")
        coEvery { deps.coordinator.processReceiptInput(uri, any()) } returns kotlin.Result.success(
            ReceiptLifecycleCoordinator.ReceiptProcessOutcome(receipt, true, null)
        )
        coEvery { deps.links.checkCanLinkReceipt(receipt.id) } returns true
        model.processGalleryImage(uri)
        shadowOf(Looper.getMainLooper()).idle()
        check(model.state.value.step == ScanStep.REVIEW) { "scan did not reach REVIEW" }
        setState(model, model.state.value.copy(imageUri = null))
    }

    /** Replaces the view model state (test seam, as the C1 view model test does). */
    fun setState(model: ReceiptScanViewModel, state: ReceiptScanState) {
        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(model) as MutableStateFlow<ReceiptScanState>).value = state
    }
}
