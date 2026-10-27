package com.yourname.expensetracker.ui.screens.installments.declare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanRef
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanTerms
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentResidualPlacement
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.domain.installment.schedule.InstallmentScheduleCalculator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.model.MoneyDisplayUi
import com.yourname.expensetracker.ui.navigation.InstallmentsFeature
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentActionMessages
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentAmountInput
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanMutationState
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanOperation
import com.yourname.expensetracker.ui.screens.installments.actions.executeInstallmentPlanOp
import com.yourname.expensetracker.ui.screens.installments.formatInstallmentDate
import com.yourname.expensetracker.ui.screens.installments.installmentMoney
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the sheet shows of the purchase: display values taken from the row, no raw captured text. */
data class DeclareInstallmentsTarget(
    val expenseId: Long,
    val merchant: String,
    val dateMillis: Long,
    val amount: Double,
    val currency: String
) {
    companion object {
        fun from(expense: Expense) = DeclareInstallmentsTarget(
            expenseId = expense.id,
            merchant = expense.merchant,
            dateMillis = expense.date,
            amount = expense.amount,
            currency = expense.currency
        )
    }
}

/**
 * Declare form (ux-flows 3.2). The plan currency defaults to the purchase currency when it is EUR or
 * USD; a different plan currency is allowed (OD-14): financing cost is then unavailable and only a
 * fixed payment can be entered, because an equal split of a purchase in another currency would need a
 * conversion the sheet never invents. [currency] null (unsupported purchase currency) blocks saving.
 */
data class DeclareForm(
    val name: String,
    val countText: String = "12",
    val split: InstallmentSplitMethod = InstallmentSplitMethod.EQUAL_SPLIT,
    val fixedText: String = "",
    val currency: InstallmentCurrency?,
    val firstDueEpochDay: Long,
    val mode: InstallmentFulfilmentMode = InstallmentFulfilmentMode.AUTO_RECORD
) {
    val count: Int? get() = countText.trim().toIntOrNull()?.takeIf { it in MIN_COUNT..MAX_COUNT }

    companion object {
        const val MIN_COUNT = 2
        const val MAX_COUNT = 480
        const val MAX_NAME_CHARS = 80
        const val MAX_AMOUNT_CHARS = 12
    }
}

sealed interface DeclareNotice {
    data class Declared(val planId: Long, val planName: String) : DeclareNotice
    data class UndoFailed(val planId: Long, val paymentRecorded: Boolean) : DeclareNotice
    data object Undone : DeclareNotice
}

data class DeclareInstallmentsUiState(
    val target: DeclareInstallmentsTarget? = null,
    val form: DeclareForm? = null,
    val initialForm: DeclareForm? = null,
    val mutation: InstallmentPlanMutationState = InstallmentPlanMutationState.Idle,
    val showDiscardDialog: Boolean = false,
    val notice: DeclareNotice? = null
) {
    val isDirty: Boolean get() = form != initialForm

    /** Equal split needs the same currency (OD-14); otherwise a fixed payment is the only way. */
    val effectiveSplit: InstallmentSplitMethod
        get() {
            val f = form ?: return InstallmentSplitMethod.EQUAL_SPLIT
            val t = target ?: return f.split
            return if (f.currency?.code == t.currency) f.split else InstallmentSplitMethod.FIXED_PAYMENT
        }

    val fixedMinor: Long? get() = form?.let { InstallmentAmountInput.parseMinor(it.fixedText) }

    val currencyDiffers: Boolean get() = form?.currency != null && target != null && form.currency.code != target.currency

    fun firstDueOk(zone: ZoneId): Boolean {
        val t = target ?: return false
        val f = form ?: return false
        return f.firstDueEpochDay >= InstallmentScheduleCalculator.civilEpochDay(t.dateMillis, zone)
    }

    fun canSave(zone: ZoneId): Boolean {
        val f = form ?: return false
        val t = target ?: return false
        if (f.name.isBlank() || f.currency == null || f.count == null || !firstDueOk(zone)) return false
        return when (effectiveSplit) {
            InstallmentSplitMethod.EQUAL_SPLIT -> InstallmentMinorUnits.toMinorOrNull(t.amount)?.let { it > 0L } == true
            InstallmentSplitMethod.FIXED_PAYMENT -> fixedMinor != null
        }
    }
}

/** Fixed texts of the declare outcomes (AE-22). `null` while nothing is wrong. */
fun declareMutationMessage(state: InstallmentPlanMutationState): Int? = when (state) {
    is InstallmentPlanMutationState.Refused -> when (state.code) {
        InstallmentRefusalCode.NOT_MINE -> R.string.declare_installments_refused_not_mine
        InstallmentRefusalCode.SHARED_NOT_SUPPORTED,
        InstallmentRefusalCode.SPLIT_NOT_SUPPORTED -> R.string.declare_installments_refused_shared
        InstallmentRefusalCode.ALREADY_DECLARED -> R.string.declare_installments_refused_already_declared
        InstallmentRefusalCode.FIRST_DUE_BEFORE_PURCHASE -> R.string.declare_installments_refused_first_date
        InstallmentRefusalCode.INVALID_COUNT -> R.string.declare_installments_refused_count
        InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT -> R.string.declare_installments_refused_too_small
        else -> InstallmentActionMessages.refusalText(state.code)
    }
    is InstallmentPlanMutationState.Failed -> InstallmentActionMessages.failureText(state.code)
    else -> null
}

/**
 * Declare installments on an EXISTING expense (AE-1 existing-expense path, OQ-16, OD-14). One write
 * path: [InstallmentPlanLifecycleCoordinator.declareOnExpense]. Undo of the declaration is
 * [InstallmentPlanLifecycleCoordinator.delete] (AE-14); it fails with PLAN_HAS_PAYMENTS once a payment
 * exists. This class never touches a DAO, the transaction lifecycle coordinator or an expense
 * repository, and never deletes an expense (INV-23). Post-commit batches go through the shared
 * [InstallmentPostCommitDispatcher]. Dormant while [InstallmentsFeature.ENABLED] is false: [open] is a
 * no-op. Operation labels reuse the shared enum: CREATE = declare, DELETE = undo.
 */
@HiltViewModel
class DeclareInstallmentsViewModel @Inject constructor(
    private val coordinator: InstallmentPlanLifecycleCoordinator,
    private val postCommitDispatcher: InstallmentPostCommitDispatcher,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private var planUuid: String = UUID.randomUUID().toString()

    private val _state = MutableStateFlow(DeclareInstallmentsUiState())
    val state: StateFlow<DeclareInstallmentsUiState> = _state.asStateFlow()

    // One-shot "the plan data changed" signal, sent from the success path (not from the snackbar), so
    // the Transactions marks refresh exactly once per committed declare or undo. Conflated: one read
    // covers a burst; kept until a collector exists.
    private val _changed = Channel<Unit>(Channel.CONFLATED)
    val changed: Flow<Unit> = _changed.receiveAsFlow()

    fun open(target: DeclareInstallmentsTarget, enabled: Boolean = InstallmentsFeature.ENABLED) {
        if (!enabled) return
        if (_state.value.mutation is InstallmentPlanMutationState.InProgress) return
        planUuid = UUID.randomUUID().toString()
        val zone = ZoneId.systemDefault()
        val purchaseDay = InstallmentScheduleCalculator.civilEpochDay(target.dateMillis, zone)
        val form = DeclareForm(
            name = target.merchant.trim().take(DeclareForm.MAX_NAME_CHARS),
            currency = InstallmentCurrency.fromCodeOrNull(target.currency),
            firstDueEpochDay = LocalDate.ofEpochDay(purchaseDay).plusMonths(1).toEpochDay()
        )
        _state.update {
            it.copy(
                target = target,
                form = form,
                initialForm = form,
                mutation = InstallmentPlanMutationState.Idle,
                showDiscardDialog = false
                // notice is kept on purpose: a pending "Undo" of an earlier declaration must not be
                // dropped silently just because another declare sheet opens. It is replaced only by a
                // newer notice or consumed by its own snackbar (action, dismissal, timeout, cancel).
            )
        }
    }

    private fun edit(change: (DeclareForm) -> DeclareForm) {
        _state.update { current ->
            val form = current.form ?: return@update current
            if (current.mutation is InstallmentPlanMutationState.InProgress) return@update current
            current.copy(form = change(form), mutation = InstallmentPlanMutationState.Idle)
        }
    }

    fun setName(v: String) = edit { it.copy(name = v.take(DeclareForm.MAX_NAME_CHARS)) }
    fun setCountText(v: String) = edit { it.copy(countText = v.filter(Char::isDigit).take(3)) }
    fun setSplit(v: InstallmentSplitMethod) = edit { it.copy(split = v) }
    fun setFixedText(v: String) = edit { it.copy(fixedText = v.take(DeclareForm.MAX_AMOUNT_CHARS)) }
    fun setCurrency(v: InstallmentCurrency) = edit { it.copy(currency = v) }
    fun setFirstDueEpochDay(v: Long) = edit { it.copy(firstDueEpochDay = v) }
    fun setMode(v: InstallmentFulfilmentMode) = edit { it.copy(mode = v) }

    /** Back, Cancel or the close icon: a changed form asks first; nothing closes while saving. */
    fun requestClose() {
        val current = _state.value
        if (current.target == null || current.mutation is InstallmentPlanMutationState.InProgress) return
        if (current.isDirty) _state.update { it.copy(showDiscardDialog = true) } else closeSheet()
    }

    fun confirmDiscard() = closeSheet()

    fun cancelDiscard() = _state.update { it.copy(showDiscardDialog = false) }

    private fun closeSheet() {
        _state.update {
            it.copy(
                target = null,
                form = null,
                initialForm = null,
                showDiscardDialog = false,
                mutation = InstallmentPlanMutationState.Idle
            )
        }
    }

    /**
     * Clears the notice. With [expected] it clears only that notice, so a snackbar that is cancelled
     * (leaving composition) consumes its own notice without wiping a newer one.
     */
    fun consumeNotice(expected: DeclareNotice? = null) = _state.update {
        if (expected == null || it.notice == expected) it.copy(notice = null) else it
    }

    fun save() {
        val zone = ZoneId.systemDefault()
        val current = _state.value
        if (current.mutation is InstallmentPlanMutationState.InProgress || !current.canSave(zone)) return
        val target = current.target ?: return
        val form = current.form ?: return
        val currency = form.currency ?: return
        val count = form.count ?: return
        val equal = current.effectiveSplit == InstallmentSplitMethod.EQUAL_SPLIT
        val terms = InstallmentPlanTerms(
            planUuid = planUuid,
            displayName = form.name.trim(),
            lenderLabel = null,
            currency = currency.code,
            splitMethod = current.effectiveSplit,
            declaredTotal = if (equal) target.amount else null,
            fixedPaymentAmount = if (equal) null else current.fixedMinor?.let(InstallmentAmountInput::toAmount),
            installmentCount = count,
            firstDueEpochDay = form.firstDueEpochDay,
            residualPlacement = InstallmentResidualPlacement.FIRST,
            fulfilmentMode = form.mode,
            // The paid-before field is hidden (see the create form, deferral 49(b)): 0 is always sent.
            paidBeforeTrackingCount = 0,
            declaredOnEpochDay = todayEpochDay(zone)
        )
        _state.update { it.copy(mutation = InstallmentPlanMutationState.InProgress(InstallmentPlanOperation.CREATE)) }
        viewModelScope.launch {
            try {
                val outcome = executeInstallmentPlanOp(
                    InstallmentPlanOperation.CREATE,
                    postCommitDispatcher,
                    planIdOf = { ref: InstallmentPlanRef -> ref.planId }
                ) {
                    coordinator.declareOnExpense(target.expenseId, terms, zone)
                }
                _state.update {
                    if (outcome is InstallmentPlanMutationState.Success) {
                        // The sheet closes only on success. AlreadyInState is a silent success (no notice).
                        val planId = outcome.planId
                        it.copy(
                            target = null,
                            form = null,
                            initialForm = null,
                            showDiscardDialog = false,
                            mutation = InstallmentPlanMutationState.Idle,
                            notice = if (!outcome.alreadyInState && planId != null) {
                                DeclareNotice.Declared(planId, form.name.trim())
                            } else {
                                null
                            }
                        )
                    } else {
                        it.copy(mutation = outcome)
                    }
                }
                // One refresh signal per committed declaration, including the silent AlreadyInState close.
                if (outcome is InstallmentPlanMutationState.Success) _changed.trySend(Unit)
            } catch (e: CancellationException) {
                resetInProgress()
                throw e
            }
        }
    }

    /** Undo of the declaration (INV-27, AE-14): the plan is deleted; valid while no payment is recorded. */
    fun undo() {
        val declared = _state.value.notice as? DeclareNotice.Declared ?: return
        if (_state.value.mutation is InstallmentPlanMutationState.InProgress) return
        _state.update {
            it.copy(notice = null, mutation = InstallmentPlanMutationState.InProgress(InstallmentPlanOperation.DELETE))
        }
        viewModelScope.launch {
            try {
                val outcome = executeInstallmentPlanOp(
                    InstallmentPlanOperation.DELETE,
                    postCommitDispatcher,
                    planIdOf = { ref: InstallmentPlanRef -> ref.planId }
                ) {
                    coordinator.delete(declared.planId)
                }
                _state.update {
                    it.copy(
                        mutation = InstallmentPlanMutationState.Idle,
                        notice = when {
                            outcome is InstallmentPlanMutationState.Success -> DeclareNotice.Undone
                            outcome is InstallmentPlanMutationState.Refused &&
                                outcome.code == InstallmentRefusalCode.PLAN_HAS_PAYMENTS ->
                                DeclareNotice.UndoFailed(declared.planId, paymentRecorded = true)
                            else -> DeclareNotice.UndoFailed(declared.planId, paymentRecorded = false)
                        }
                    )
                }
                if (outcome is InstallmentPlanMutationState.Success) _changed.trySend(Unit)
            } catch (e: CancellationException) {
                resetInProgress()
                throw e
            }
        }
    }

    private fun resetInProgress() {
        _state.update {
            if (it.mutation is InstallmentPlanMutationState.InProgress) {
                it.copy(mutation = InstallmentPlanMutationState.Idle)
            } else {
                it
            }
        }
    }

    private fun todayEpochDay(zone: ZoneId): Long =
        Instant.ofEpochMilli(timeProvider.now()).atZone(zone).toLocalDate().toEpochDay()
}

// ---------------------------------------------------------------------- Compose

/** Hosts the sheet and the Declared / Undo snackbars on the Transactions screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeclareInstallmentsHost(
    viewModel: DeclareInstallmentsViewModel,
    snackbarHostState: SnackbarHostState,
    onChanged: () -> Unit,
    onOpenPlan: (Long) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val notice = state.notice
    val undoLabel = stringResource(R.string.declare_installments_snackbar_undo)
    val viewPlanLabel = stringResource(R.string.declare_installments_snackbar_view_plan)
    val declaredText = (notice as? DeclareNotice.Declared)
        ?.let { stringResource(R.string.declare_installments_snackbar_declared, it.planName) }
    val undoneText = stringResource(R.string.declare_installments_snackbar_undone)
    val undoFailedText = stringResource(R.string.declare_installments_snackbar_undo_failed)
    val genericText = stringResource(R.string.installments_action_refused_generic)
    // The refresh signal comes from the ViewModel success path, once per committed declare or undo;
    // it does not depend on the snackbar coroutine, so a replayed or cancelled snackbar cannot repeat it.
    val currentOnChanged by rememberUpdatedState(onChanged)
    LaunchedEffect(viewModel) { viewModel.changed.collect { currentOnChanged() } }
    LaunchedEffect(notice) {
        // The notice is consumed on every exit, a cancellation (leaving composition) included, so it
        // never replays on return. Only this notice is cleared, never a newer one. The finally block
        // lets the CancellationException continue (CANCEL-01).
        try {
            when (notice) {
                is DeclareNotice.Declared -> {
                    // Bounded duration (the default is Indefinite).
                    val result = snackbarHostState.showSnackbar(
                        declaredText.orEmpty(),
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undo()
                }
                is DeclareNotice.UndoFailed -> {
                    val text = if (notice.paymentRecorded) undoFailedText else genericText
                    val result = snackbarHostState.showSnackbar(
                        text,
                        actionLabel = viewPlanLabel,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) onOpenPlan(notice.planId)
                }
                DeclareNotice.Undone -> {
                    snackbarHostState.showSnackbar(undoneText, duration = SnackbarDuration.Short)
                }
                null -> Unit
            }
        } finally {
            if (notice != null) viewModel.consumeNotice(notice)
        }
    }
    if (state.target != null && state.form != null) DeclareSheet(state, viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeclareSheet(state: DeclareInstallmentsUiState, vm: DeclareInstallmentsViewModel) {
    val target = state.target ?: return
    val form = state.form ?: return
    val zone = remember { ZoneId.systemDefault() }
    val running = state.mutation is InstallmentPlanMutationState.InProgress
    val message = declareMutationMessage(state.mutation)?.let { stringResource(it) }
    var pickingDate by remember { mutableStateOf(false) }
    val purchaseDay = InstallmentScheduleCalculator.civilEpochDay(target.dateMillis, zone)
    val purchaseMoney = MoneyDisplayUi.from(target.amount, target.currency).formatted
    ModalBottomSheet(
        onDismissRequest = vm::requestClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.declare_installments_title), style = MaterialTheme.typography.titleLarge)
            Text("${target.merchant} · ${formatInstallmentDate(purchaseDay)} · $purchaseMoney", style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.declare_installments_receipt_note), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = form.name,
                onValueChange = vm::setName,
                label = { Text(stringResource(R.string.declare_installments_name)) },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.countText,
                onValueChange = vm::setCountText,
                label = { Text(stringResource(R.string.declare_installments_count)) },
                singleLine = true,
                enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            // OD-14: the plan currency may differ from the purchase currency (EUR or USD only).
            Text(stringResource(R.string.declare_installments_currency), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InstallmentCurrency.entries.forEach { c ->
                    FilterChip(
                        selected = form.currency == c,
                        onClick = { vm.setCurrency(c) },
                        enabled = !running,
                        label = { Text(c.code) }
                    )
                }
            }
            if (state.currencyDiffers) {
                Text(stringResource(R.string.declare_installments_currency_differs), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.effectiveSplit == InstallmentSplitMethod.EQUAL_SPLIT,
                    onClick = { vm.setSplit(InstallmentSplitMethod.EQUAL_SPLIT) },
                    enabled = !running && !state.currencyDiffers,
                    label = { Text(stringResource(R.string.declare_installments_split_equal)) }
                )
                FilterChip(
                    selected = state.effectiveSplit == InstallmentSplitMethod.FIXED_PAYMENT,
                    onClick = { vm.setSplit(InstallmentSplitMethod.FIXED_PAYMENT) },
                    enabled = !running,
                    label = { Text(stringResource(R.string.declare_installments_split_fixed)) }
                )
            }
            if (state.effectiveSplit == InstallmentSplitMethod.FIXED_PAYMENT) {
                OutlinedTextField(
                    value = form.fixedText,
                    onValueChange = vm::setFixedText,
                    label = { Text(stringResource(R.string.declare_installments_fixed_amount)) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                val count = form.count
                val currency = form.currency
                val total = InstallmentMinorUnits.toMinorOrNull(target.amount)
                if (count != null && currency != null && total != null) {
                    // Rounded down per payment; the remainder is placed on the first payment by the coordinator.
                    Text(
                        stringResource(
                            R.string.declare_installments_each_payment,
                            installmentMoney(total / count, currency).formatted
                        )
                    )
                }
            }
            TextButton(onClick = { pickingDate = true }, enabled = !running) {
                Text(stringResource(R.string.declare_installments_first_due, formatInstallmentDate(form.firstDueEpochDay)))
            }
            if (!state.firstDueOk(zone)) {
                Text(stringResource(R.string.declare_installments_refused_first_date), color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.declare_installments_mode_title), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = form.mode == InstallmentFulfilmentMode.AUTO_RECORD,
                    onClick = { vm.setMode(InstallmentFulfilmentMode.AUTO_RECORD) },
                    enabled = !running,
                    label = { Text(stringResource(R.string.declare_installments_mode_auto)) }
                )
                FilterChip(
                    selected = form.mode == InstallmentFulfilmentMode.CONFIRM,
                    onClick = { vm.setMode(InstallmentFulfilmentMode.CONFIRM) },
                    enabled = !running,
                    label = { Text(stringResource(R.string.declare_installments_mode_confirm)) }
                )
            }
            // AE-20 / ux-flows 3.2: the mandatory "What changes" block.
            Text(
                stringResource(R.string.declare_installments_impact, purchaseMoney, formatInstallmentDate(purchaseDay)),
                style = MaterialTheme.typography.bodySmall
            )
            if (message != null) Text(message, color = MaterialTheme.colorScheme.error)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = vm::requestClose, enabled = !running) {
                    Text(stringResource(R.string.installments_action_cancel))
                }
                Button(onClick = vm::save, enabled = state.canSave(zone) && !running) {
                    Text(stringResource(if (running) R.string.installments_action_working else R.string.declare_installments_confirm))
                }
            }
        }
    }
    if (state.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = vm::cancelDiscard,
            title = { Text(stringResource(R.string.declare_installments_discard_title)) },
            text = { Text(stringResource(R.string.installments_action_discard_body)) },
            confirmButton = { TextButton(onClick = vm::confirmDiscard) { Text(stringResource(R.string.installments_action_discard)) } },
            dismissButton = { TextButton(onClick = vm::cancelDiscard) { Text(stringResource(R.string.declare_installments_keep)) } }
        )
    }
    if (pickingDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = form.firstDueEpochDay * MILLIS_PER_DAY)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { vm.setFirstDueEpochDay(it / MILLIS_PER_DAY) }
                    pickingDate = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.installments_action_cancel)) }
            }
        ) { DatePicker(state = pickerState) }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
