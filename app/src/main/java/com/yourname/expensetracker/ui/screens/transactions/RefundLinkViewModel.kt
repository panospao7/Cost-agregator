package com.yourname.expensetracker.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.CurrencyFractionDigits
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.transaction.RefundCandidate
import com.yourname.expensetracker.domain.transaction.RefundCandidates
import com.yourname.expensetracker.domain.transaction.RefundLikelihoodDetector
import com.yourname.expensetracker.domain.transaction.RefundLinkRefusal
import com.yourname.expensetracker.domain.transaction.RefundLinkResult
import com.yourname.expensetracker.domain.transaction.RefundLinkUseCase
import com.yourname.expensetracker.domain.transaction.RefundLinks
import com.yourname.expensetracker.domain.transaction.RefundUnlinkResult
import com.yourname.expensetracker.domain.transaction.RefundWriteFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

// ---------------------------------------------------------------------------------------------
// Refund netting (DR-05) S4: Transactions UI state (ux-flows 3). Held in a small view model of its
// own so TransactionsViewModel stays untouched. Every link, unlink and candidate or link read goes
// through RefundLinkUseCase (INV-16); the repositories are used only to read display text
// (merchant, category) of rows the use case named by id. Nothing here logs an amount, merchant or id.
// ---------------------------------------------------------------------------------------------

/** The deposit a picker is open for. Amount and currency travel together as a [MoneyAmount]. */
data class RefundDepositRef(val id: Long, val amount: MoneyAmount, val dateMs: Long)

/** One purchase row of the picker, enriched with live display text. */
data class RefundCandidateRowUi(
    val purchaseId: Long,
    val merchant: String,
    val dateMs: Long,
    val categoryName: String?,
    val amount: MoneyAmount,
    val remaining: MoneyAmount
) {
    /** True when an earlier refund already took part of this purchase ("x not yet refunded" line). */
    val partlyRefunded: Boolean get() = remaining.amount < amount.amount
}

/** Picker sheet states (ux-flows 3.1). The confirm dialog is [RefundConfirmUiState], a separate flow. */
sealed interface RefundPickerUiState {
    data object Idle : RefundPickerUiState
    data class Loading(val deposit: RefundDepositRef) : RefundPickerUiState
    data class Content(val deposit: RefundDepositRef, val candidates: List<RefundCandidateRowUi>) : RefundPickerUiState
    data class None(val deposit: RefundDepositRef, val currencyCode: String, val windowDays: Int) : RefundPickerUiState
    data class Unavailable(val deposit: RefundDepositRef) : RefundPickerUiState
}

/** Confirm dialog of a link (ux-flows 2.4). [applied] is a preview; the write recomputes it. */
data class RefundConfirmUiState(
    val deposit: RefundDepositRef,
    val candidate: RefundCandidateRowUi,
    val applied: MoneyAmount,
    val netAfter: MoneyAmount,
    val depositPartlyUsed: Boolean,
    val submitting: Boolean
)

/** The row a details sheet was opened for. Header numbers come from the row, so they show while loading. */
data class RefundSubject(
    val expenseId: Long,
    val isDeposit: Boolean,
    val merchant: String,
    val dateMs: Long,
    val amount: MoneyAmount,
    val refunded: MoneyAmount
)

/** One link of the details sheet, seen from the subject. [counterpartAmount] and [counterpartRefunded] are the other row's. */
data class RefundLinkRowUi(
    val linkId: Long,
    val counterpartId: Long,
    val merchant: String,
    val dateMs: Long,
    val categoryName: String?,
    val applied: MoneyAmount,
    val counterpartAmount: MoneyAmount,
    val counterpartRefunded: MoneyAmount
)

sealed interface RefundDetailsUiState {
    data object Idle : RefundDetailsUiState
    data class Loading(val subject: RefundSubject) : RefundDetailsUiState
    data class Content(val subject: RefundSubject, val links: List<RefundLinkRowUi>) : RefundDetailsUiState
    data class Unavailable(val subject: RefundSubject) : RefundDetailsUiState
}

/**
 * Unlink confirm (ux-flows 2.6, Q7). [shownAmount] is the purchase amount after the removal; [lastLink]
 * is true when that amount is the gross ("go back to"), false when other links remain ("show" the net).
 */
data class RefundUnlinkConfirmUiState(
    val link: RefundLinkRowUi,
    val refundDepositId: Long,
    val purchaseMerchant: String,
    val shownAmount: MoneyAmount,
    val lastLink: Boolean,
    val submitting: Boolean
)

/** What the picker does after a refused link (ux-flows 3.3). */
enum class RefundRefusalFollowUp { RELOAD_CANDIDATES, CLOSE_AND_REFRESH, RELOAD_AND_REFRESH }

/** Pure result to string mapping. The view model never builds user text from a code name. */
object RefundLinkUiMapper {
    fun refusalMessageRes(code: RefundLinkRefusal): Int = when (code) {
        RefundLinkRefusal.NOTHING_TO_APPLY -> R.string.refund_refused_nothing_to_apply
        RefundLinkRefusal.REFUND_LINKED_ELSEWHERE -> R.string.refund_refused_linked_elsewhere
        RefundLinkRefusal.REFUND_BEFORE_PURCHASE -> R.string.refund_refused_before_purchase
        RefundLinkRefusal.CURRENCY_MISMATCH -> R.string.refund_refused_currency
        RefundLinkRefusal.INSTALLMENT_MANAGED -> R.string.refund_refused_installment
        RefundLinkRefusal.GROUP_LINKED -> R.string.refund_refused_group
        RefundLinkRefusal.REFUND_NOT_FOUND,
        RefundLinkRefusal.PURCHASE_NOT_FOUND,
        RefundLinkRefusal.SAME_ROW,
        RefundLinkRefusal.NOT_A_DEPOSIT,
        RefundLinkRefusal.NOT_A_PURCHASE,
        RefundLinkRefusal.INVALID_AMOUNT,
        RefundLinkRefusal.CURRENCY_DIGITS_UNAVAILABLE -> R.string.refund_refused_stale
    }

    fun refusalFollowUp(code: RefundLinkRefusal): RefundRefusalFollowUp = when (code) {
        RefundLinkRefusal.REFUND_LINKED_ELSEWHERE -> RefundRefusalFollowUp.CLOSE_AND_REFRESH
        RefundLinkRefusal.REFUND_NOT_FOUND,
        RefundLinkRefusal.PURCHASE_NOT_FOUND,
        RefundLinkRefusal.SAME_ROW,
        RefundLinkRefusal.NOT_A_DEPOSIT,
        RefundLinkRefusal.NOT_A_PURCHASE,
        RefundLinkRefusal.INVALID_AMOUNT,
        RefundLinkRefusal.CURRENCY_DIGITS_UNAVAILABLE -> RefundRefusalFollowUp.RELOAD_AND_REFRESH
        else -> RefundRefusalFollowUp.RELOAD_CANDIDATES
    }

    fun failureMessageRes(code: RefundWriteFailure): Int = when (code) {
        RefundWriteFailure.WRITE_BLOCKED -> R.string.ui_write_blocked_maintenance
        RefundWriteFailure.INTERNAL -> R.string.recurring_error_generic
    }
}

/**
 * Money arithmetic for display (ux-flows 4): BigDecimal at the currency's fraction digits, HALF_EVEN,
 * never negative. A currency without known digits gives null; the caller then shows no figure
 * (never a guessed scale and never a zero).
 */
object RefundUiMoney {
    fun scaleOf(currency: String): Int? =
        CurrencyCode.parse(currency)?.let { CurrencyFractionDigits.of(it) }

    /** `max(0, a - b)` at the scale of [currency]. */
    fun subtract(a: Double, b: Double, currency: String): Double? {
        val scale = scaleOf(currency) ?: return null
        val diff = BigDecimal.valueOf(a).setScale(scale, RoundingMode.HALF_EVEN).subtract(BigDecimal.valueOf(b))
        return diff.max(BigDecimal.ZERO).setScale(scale, RoundingMode.HALF_EVEN).toDouble()
    }

    /** `min(a, b)` at the scale of [currency]. */
    fun minOf(a: Double, b: Double, currency: String): Double? {
        val scale = scaleOf(currency) ?: return null
        val x = BigDecimal.valueOf(a).setScale(scale, RoundingMode.HALF_EVEN)
        val y = BigDecimal.valueOf(b).setScale(scale, RoundingMode.HALF_EVEN)
        return x.min(y).toDouble()
    }

    /** True when [a] is smaller than [b] at the scale of [currency]. */
    fun isLess(a: Double, b: Double, currency: String): Boolean {
        val scale = scaleOf(currency) ?: return a < b
        return BigDecimal.valueOf(a).setScale(scale, RoundingMode.HALF_EVEN) <
            BigDecimal.valueOf(b).setScale(scale, RoundingMode.HALF_EVEN)
    }

    /** Net of a purchase row: `amount - refunded`, never negative. Null when the scale is unavailable. */
    fun net(amount: Double, refunded: Double, currency: String): Double? = subtract(amount, refunded, currency)
}

@HiltViewModel
class RefundLinkViewModel @Inject constructor(
    private val useCase: RefundLinkUseCase,
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _picker = MutableStateFlow<RefundPickerUiState>(RefundPickerUiState.Idle)
    val picker: StateFlow<RefundPickerUiState> = _picker.asStateFlow()

    private val _confirm = MutableStateFlow<RefundConfirmUiState?>(null)
    val confirm: StateFlow<RefundConfirmUiState?> = _confirm.asStateFlow()

    private val _details = MutableStateFlow<RefundDetailsUiState>(RefundDetailsUiState.Idle)
    val details: StateFlow<RefundDetailsUiState> = _details.asStateFlow()

    private val _unlinkConfirm = MutableStateFlow<RefundUnlinkConfirmUiState?>(null)
    val unlinkConfirm: StateFlow<RefundUnlinkConfirmUiState?> = _unlinkConfirm.asStateFlow()

    /** Row ids with a write in flight; the screen disables both rows' actions meanwhile. */
    private val _mutatingExpenseIds = MutableStateFlow<Set<Long>>(emptySet())
    val mutatingExpenseIds: StateFlow<Set<Long>> = _mutatingExpenseIds.asStateFlow()

    private val _error = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
    val error: SharedFlow<UiText> = _error.asSharedFlow()

    private val _successMessage = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
    val successMessage: SharedFlow<UiText> = _successMessage.asSharedFlow()

    /**
     * The Transactions list must reload (chips changed). The screen forwards it to TransactionsViewModel.
     * Conflated channel: a request raised while nobody collects is kept and delivered once, never dropped.
     */
    private val _refreshRequests = Channel<Unit>(Channel.CONFLATED)
    val refreshRequests: Flow<Unit> = _refreshRequests.receiveAsFlow()

    private var pickerJob: Job? = null
    private var detailsJob: Job? = null

    /** In-flight guard: one write at a time (link or unlink). Only touched on the main dispatcher. */
    private var writeInFlight = false

    // ------------------------------------------------------------------ picker

    /** Opens the picker for [deposit] (entry A or B). Nothing is written. */
    fun openPicker(deposit: Expense) {
        val money = moneyOf(deposit.amount, deposit.currency)
        if (money == null) {
            _error.tryEmit(UiText.from(R.string.refund_picker_unavailable))
            return
        }
        val ref = RefundDepositRef(deposit.id, money, deposit.date)
        _confirm.value = null
        loadCandidates(ref)
    }

    fun closePicker() {
        pickerJob?.cancel()
        pickerJob = null
        _confirm.value = null
        _picker.value = RefundPickerUiState.Idle
    }

    /** Retry of the picker read. No automatic retry or polling. */
    fun retryPicker() {
        val deposit = when (val state = _picker.value) {
            is RefundPickerUiState.Unavailable -> state.deposit
            is RefundPickerUiState.None -> state.deposit
            else -> return
        }
        loadCandidates(deposit)
    }

    private fun loadCandidates(deposit: RefundDepositRef) {
        pickerJob?.cancel()
        _picker.value = RefundPickerUiState.Loading(deposit)
        pickerJob = viewModelScope.launch {
            try {
                val result = useCase.findCandidates(deposit.id)
                val next = when (result) {
                    is RefundCandidates.Unavailable -> RefundPickerUiState.Unavailable(deposit)
                    is RefundCandidates.Available -> buildPickerState(deposit, result.items)
                }
                _picker.value = next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Refund netting UI: code=REFUND_PICKER_FAILED class=%s", e::class.java.simpleName)
                _picker.value = RefundPickerUiState.Unavailable(deposit)
            }
        }
    }

    private suspend fun buildPickerState(deposit: RefundDepositRef, items: List<RefundCandidate>): RefundPickerUiState {
        if (items.isEmpty()) {
            return RefundPickerUiState.None(deposit, deposit.amount.currency.code, RefundLikelihoodDetector.WINDOW_DAYS)
        }
        val categories = categoryNames() ?: return RefundPickerUiState.Unavailable(deposit)
        val rows = ArrayList<RefundCandidateRowUi>(items.size)
        for (candidate in items) {
            // A candidate whose live display read fails is dropped (render truthfulness, ux-flows 3.1).
            val purchase = readExpenseOrNull(candidate.purchaseId) ?: continue
            rows.add(
                RefundCandidateRowUi(
                    purchaseId = candidate.purchaseId,
                    merchant = purchase.merchant,
                    dateMs = candidate.purchaseDate,
                    categoryName = purchase.categoryId?.let { categories[it] },
                    amount = candidate.amount,
                    remaining = candidate.remaining
                )
            )
        }
        // All dropped: unavailable, never "none" and never an empty list.
        return if (rows.isEmpty()) RefundPickerUiState.Unavailable(deposit) else RefundPickerUiState.Content(deposit, rows)
    }

    // ------------------------------------------------------------------ confirm and link

    fun selectCandidate(candidate: RefundCandidateRowUi) {
        val deposit = when (val state = _picker.value) {
            is RefundPickerUiState.Content -> state.deposit
            else -> return
        }
        val currency = deposit.amount.currency.code
        // Scale unavailable: say so (stale wording) and reload the picker, never a silent no-op or a zero preview.
        val applied = RefundUiMoney.minOf(candidate.remaining.amount, deposit.amount.amount, currency)
        val netAfter = applied?.let { RefundUiMoney.subtract(candidate.remaining.amount, it, currency) }
        if (applied == null || netAfter == null) {
            _error.tryEmit(UiText.from(R.string.refund_refused_stale))
            loadCandidates(deposit)
            return
        }
        _confirm.value = RefundConfirmUiState(
            deposit = deposit,
            candidate = candidate,
            applied = MoneyAmount(applied, deposit.amount.currency),
            netAfter = MoneyAmount(netAfter, deposit.amount.currency),
            depositPartlyUsed = RefundUiMoney.isLess(applied, deposit.amount.amount, currency),
            submitting = false
        )
    }

    fun dismissConfirm() {
        val current = _confirm.value ?: return
        if (current.submitting) return
        _confirm.value = null
    }

    fun confirmLink() {
        val current = _confirm.value ?: return
        if (current.submitting || writeInFlight) return
        val ids = setOf(current.deposit.id, current.candidate.purchaseId)
        beginWrite(ids)
        _confirm.value = current.copy(submitting = true)
        viewModelScope.launch {
            try {
                when (val result = useCase.link(current.deposit.id, current.candidate.purchaseId)) {
                    is RefundLinkResult.Linked -> {
                        val net = RefundUiMoney.subtract(
                            current.candidate.remaining.amount, result.applied.amount, result.applied.currency.code
                        )
                        // A net that cannot be computed is omitted from the sentence, never shown as zero.
                        if (net != null) {
                            _successMessage.tryEmit(
                                UiText.from(
                                    R.string.refund_done_format,
                                    current.candidate.merchant,
                                    com.yourname.expensetracker.domain.util.CurrencyFormatter.formatMoney(net, result.applied.currency.code)
                                )
                            )
                        } else {
                            _successMessage.tryEmit(UiText.from(R.string.refund_done_plain_format, current.candidate.merchant))
                        }
                        finishLinkAndClose()
                    }
                    is RefundLinkResult.AlreadyLinked -> {
                        _successMessage.tryEmit(UiText.from(R.string.refund_already_applied))
                        finishLinkAndClose()
                    }
                    is RefundLinkResult.Refused -> {
                        _error.tryEmit(UiText.from(RefundLinkUiMapper.refusalMessageRes(result.code)))
                        _confirm.value = null
                        when (RefundLinkUiMapper.refusalFollowUp(result.code)) {
                            RefundRefusalFollowUp.RELOAD_CANDIDATES -> loadCandidates(current.deposit)
                            RefundRefusalFollowUp.RELOAD_AND_REFRESH -> {
                                _refreshRequests.trySend(Unit)
                                loadCandidates(current.deposit)
                            }
                            RefundRefusalFollowUp.CLOSE_AND_REFRESH -> {
                                closePicker()
                                _refreshRequests.trySend(Unit)
                            }
                        }
                    }
                    is RefundLinkResult.Failed -> {
                        // Dialog stays so the user can retry; buttons re-enable.
                        _error.tryEmit(UiText.from(RefundLinkUiMapper.failureMessageRes(result.code)))
                        reenableConfirm()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Refund netting UI: code=REFUND_LINK_UI_FAILED class=%s", e::class.java.simpleName)
                _error.tryEmit(UiText.from(R.string.recurring_error_generic))
                reenableConfirm()
            } finally {
                endWrite(ids)
            }
        }
    }

    private fun finishLinkAndClose() {
        closePicker()
        _refreshRequests.trySend(Unit)
    }

    private fun reenableConfirm() {
        _confirm.update { it?.copy(submitting = false) }
    }

    // ------------------------------------------------------------------ details and unlink

    /** Opens the details sheet of a linked row (entry C). Header numbers come from [expense] immediately. */
    fun openDetails(expense: Expense) {
        val subject = subjectOf(expense)
        if (subject == null) {
            _error.tryEmit(UiText.from(R.string.refund_details_unavailable))
            return
        }
        _unlinkConfirm.value = null
        loadDetails(subject)
    }

    fun closeDetails() {
        detailsJob?.cancel()
        detailsJob = null
        _unlinkConfirm.value = null
        _details.value = RefundDetailsUiState.Idle
    }

    fun retryDetails() {
        val subject = when (val state = _details.value) {
            is RefundDetailsUiState.Unavailable -> state.subject
            else -> return
        }
        loadDetails(subject)
    }

    private fun loadDetails(subject: RefundSubject) {
        detailsJob?.cancel()
        _details.value = RefundDetailsUiState.Loading(subject)
        detailsJob = viewModelScope.launch {
            try {
                when (val result = useCase.linksOf(subject.expenseId)) {
                    is RefundLinks.Unavailable -> _details.value = RefundDetailsUiState.Unavailable(subject)
                    is RefundLinks.Available -> {
                        if (result.links.isEmpty()) {
                            // Stale row (race with an unlink or delete): close and refresh, no message.
                            _details.value = RefundDetailsUiState.Idle
                            _refreshRequests.trySend(Unit)
                        } else {
                            _details.value = buildDetailsState(subject, result)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Refund netting UI: code=REFUND_DETAILS_FAILED class=%s", e::class.java.simpleName)
                _details.value = RefundDetailsUiState.Unavailable(subject)
            }
        }
    }

    private suspend fun buildDetailsState(subject: RefundSubject, links: RefundLinks.Available): RefundDetailsUiState {
        val categories = categoryNames() ?: return RefundDetailsUiState.Unavailable(subject)
        val rows = ArrayList<RefundLinkRowUi>(links.links.size)
        for (link in links.links) {
            // One unreadable counterpart makes the whole list unavailable: a link must not be removed blind.
            val other = readExpenseOrNull(link.counterpartId) ?: return RefundDetailsUiState.Unavailable(subject)
            val amount = moneyOf(other.amount, other.currency) ?: return RefundDetailsUiState.Unavailable(subject)
            val refunded = moneyOf(other.refundNettedAmount, other.currency)
                ?: return RefundDetailsUiState.Unavailable(subject)
            rows.add(
                RefundLinkRowUi(
                    linkId = link.linkId,
                    counterpartId = link.counterpartId,
                    merchant = other.merchant,
                    dateMs = other.date,
                    categoryName = other.categoryId?.let { categories[it] },
                    applied = link.appliedAmount,
                    counterpartAmount = amount,
                    counterpartRefunded = refunded
                )
            )
        }
        return RefundDetailsUiState.Content(subject, rows)
    }

    fun requestUnlink(link: RefundLinkRowUi) {
        val content = _details.value as? RefundDetailsUiState.Content ?: return
        val subject = content.subject
        // The purchase is the subject itself on a purchase sheet, the counterpart on a deposit sheet.
        val purchaseMerchant: String
        val purchaseAmount: MoneyAmount
        if (subject.isDeposit) {
            purchaseMerchant = link.merchant
            purchaseAmount = link.counterpartAmount
        } else {
            purchaseMerchant = subject.merchant
            purchaseAmount = subject.amount
        }
        val currency = purchaseAmount.currency.code
        val stillRefunded: Double
        if (subject.isDeposit) {
            // The counterpart purchase row was read live with the details; remove this link's share.
            stillRefunded = RefundUiMoney.subtract(link.counterpartRefunded.amount, link.applied.amount, currency)
                ?: run { emitDetailsUnavailable(); return }
        } else {
            // Purchase sheet: the list row's refunded figure can be stale, so sum the other live links instead.
            val scale = RefundUiMoney.scaleOf(currency) ?: run { emitDetailsUnavailable(); return }
            var others = BigDecimal.ZERO
            for (other in content.links) {
                if (other.linkId == link.linkId) continue
                others = others.add(BigDecimal.valueOf(other.applied.amount).setScale(scale, RoundingMode.HALF_EVEN))
            }
            stillRefunded = others.toDouble()
        }
        val shown = RefundUiMoney.subtract(purchaseAmount.amount, stillRefunded, currency)
            ?: run { emitDetailsUnavailable(); return }
        _unlinkConfirm.value = RefundUnlinkConfirmUiState(
            link = link,
            refundDepositId = if (subject.isDeposit) subject.expenseId else link.counterpartId,
            purchaseMerchant = purchaseMerchant,
            shownAmount = MoneyAmount(shown, purchaseAmount.currency),
            lastLink = stillRefunded == 0.0,
            submitting = false
        )
    }

    private fun emitDetailsUnavailable() {
        _error.tryEmit(UiText.from(R.string.refund_details_unavailable))
    }

    fun dismissUnlink() {
        val current = _unlinkConfirm.value ?: return
        if (current.submitting) return
        _unlinkConfirm.value = null
    }

    fun confirmUnlink() {
        val current = _unlinkConfirm.value ?: return
        if (current.submitting || writeInFlight) return
        val subjectId = (_details.value as? RefundDetailsUiState.Content)?.subject?.expenseId
        val ids = setOfNotNull(current.refundDepositId, current.link.counterpartId, subjectId)
        beginWrite(ids)
        _unlinkConfirm.value = current.copy(submitting = true)
        viewModelScope.launch {
            try {
                when (val result = useCase.unlink(current.refundDepositId)) {
                    is RefundUnlinkResult.Unlinked, is RefundUnlinkResult.NotLinked -> {
                        _successMessage.tryEmit(UiText.from(R.string.refund_unlinked))
                        _unlinkConfirm.value = null
                        _refreshRequests.trySend(Unit)
                        reloadDetailsAfterUnlink(subjectId)
                    }
                    is RefundUnlinkResult.Failed -> {
                        _error.tryEmit(UiText.from(RefundLinkUiMapper.failureMessageRes(result.code)))
                        _unlinkConfirm.update { it?.copy(submitting = false) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Refund netting UI: code=REFUND_UNLINK_UI_FAILED class=%s", e::class.java.simpleName)
                _error.tryEmit(UiText.from(R.string.recurring_error_generic))
                _unlinkConfirm.update { it?.copy(submitting = false) }
            } finally {
                endWrite(ids)
            }
        }
    }

    /** After a removal the sheet reloads from the row's fresh values, or closes when nothing is left. */
    private suspend fun reloadDetailsAfterUnlink(subjectId: Long?) {
        val fresh = subjectId?.let { readExpenseOrNull(it) }
        val subject = fresh?.let { subjectOf(it) }
        if (subject == null || subject.refunded.amount <= 0.0) {
            closeDetails()
        } else {
            loadDetails(subject)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun beginWrite(ids: Set<Long>) {
        writeInFlight = true
        _mutatingExpenseIds.update { it + ids }
    }

    private fun endWrite(ids: Set<Long>) {
        writeInFlight = false
        _mutatingExpenseIds.update { it - ids }
    }

    private fun subjectOf(expense: Expense): RefundSubject? {
        val amount = moneyOf(expense.amount, expense.currency) ?: return null
        val refunded = moneyOf(expense.refundNettedAmount, expense.currency) ?: return null
        return RefundSubject(
            expenseId = expense.id,
            isDeposit = expense.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT,
            merchant = expense.merchant,
            dateMs = expense.date,
            amount = amount,
            refunded = refunded
        )
    }

    private fun moneyOf(amount: Double, currency: String): MoneyAmount? {
        val code = CurrencyCode.parse(currency) ?: return null
        if (amount.isNaN() || amount.isInfinite()) return null
        return MoneyAmount(amount, code)
    }

    /** Live display read of one row; null on any failure (the caller drops or fails closed). */
    private suspend fun readExpenseOrNull(id: Long): Expense? {
        return try {
            expenseRepository.getExpenseById(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Refund netting UI: code=REFUND_DISPLAY_READ_FAILED class=%s", e::class.java.simpleName)
            null
        }
    }

    /** Category id to name map, or null when the read fails (then the sheet is unavailable). */
    private suspend fun categoryNames(): Map<Long, String>? {
        return try {
            categoryRepository.getAll().associate { it.id to it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Refund netting UI: code=REFUND_DISPLAY_READ_FAILED class=%s", e::class.java.simpleName)
            null
        }
    }
}
