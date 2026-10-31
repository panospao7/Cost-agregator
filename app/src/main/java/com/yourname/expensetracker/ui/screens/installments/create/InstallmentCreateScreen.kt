package com.yourname.expensetracker.ui.screens.installments.create

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanTerms
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentResidualPlacement
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentAmountInput
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanMutationState
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanOperation
import com.yourname.expensetracker.ui.screens.installments.actions.executeInstallmentPlanOp
import com.yourname.expensetracker.ui.screens.installments.actions.installmentPlanMutationMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** A category the user can pick. Display values only. */
data class InstallmentCategoryOption(val id: Long, val name: String)

/**
 * Standalone plan form (ux-flows 3.3). A standalone plan is always FIXED_PAYMENT (spec 2.1).
 * D-7 is moot (AMD-3): the category is required, user-picked, and nothing is preselected.
 * OQ-18: [paidBeforeText] is the history-only count of payments made before using the app.
 */
data class InstallmentCreateForm(
    val name: String = "",
    val lender: String = "",
    val currency: InstallmentCurrency = InstallmentCurrency.EUR,
    val amountText: String = "",
    val countText: String = "",
    val firstDueEpochDay: Long = 0L,
    val mode: InstallmentFulfilmentMode = InstallmentFulfilmentMode.AUTO_RECORD,
    val categoryId: Long? = null,
    val paidBeforeText: String = ""
) {
    val amountMinor: Long? get() = InstallmentAmountInput.parseMinor(amountText)

    /** Plan payments 2..480 (a one-payment plan is INVALID_COUNT). */
    val count: Int? get() = countText.trim().toIntOrNull()?.takeIf { it in MIN_COUNT..MAX_COUNT }

    /** Blank means none; a value must be 0..count-1 (the coordinator checks it against the dates). */
    val paidBefore: Int?
        get() {
            val text = paidBeforeText.trim()
            if (text.isEmpty()) return 0
            val value = text.toIntOrNull() ?: return null
            val total = count ?: return null
            return value.takeIf { it in 0 until total }
        }

    val nameOk: Boolean get() = name.isNotBlank()

    val canSave: Boolean
        get() = nameOk && amountMinor != null && count != null && paidBefore != null && categoryId != null

    companion object {
        const val MIN_COUNT = 2
        const val MAX_COUNT = 480
        const val MAX_NAME_CHARS = 80
        const val MAX_LENDER_CHARS = 80
        const val MAX_AMOUNT_CHARS = 12

        /**
         * OQ-18 paid-before field visibility. Hidden until the first-due picker exists: while the first due
         * day is today (= the declared-on day) every non-zero count is refused (deferral 49(b)).
         */
        val SHOW_PAID_BEFORE_FIELD: Boolean = false
    }
}

data class InstallmentCreateUiState(
    val form: InstallmentCreateForm = InstallmentCreateForm(),
    val initialForm: InstallmentCreateForm = InstallmentCreateForm(),
    val categories: List<InstallmentCategoryOption> = emptyList(),
    val categoriesFailed: Boolean = false,
    val mutation: InstallmentPlanMutationState = InstallmentPlanMutationState.Idle,
    val showDiscardDialog: Boolean = false
) {
    val isDirty: Boolean get() = form != initialForm
}

/**
 * Create a standalone plan (loan-installments S10b-2, ux-flows 3.3). One coordinator operation:
 * [InstallmentPlanLifecycleCoordinator.createStandalone]. The plan uuid is fixed per form so a retry
 * after a lost response is an idempotent AlreadyInState (silent success). The name is required
 * (INV-34): a blank name never reaches the coordinator. Categories are read through
 * [CategoryRepository] (read only). `CancellationException` is rethrown.
 */
@HiltViewModel
class InstallmentCreateViewModel @Inject constructor(
    private val coordinator: InstallmentPlanLifecycleCoordinator,
    private val postCommitDispatcher: InstallmentPostCommitDispatcher,
    private val categoryRepository: CategoryRepository,
    private val timeProvider: TimeProvider
) : ViewModel() {

    // Regenerated by resetSession(): the overlay ViewModel is activity-scoped and outlives one form.
    private var planUuid: String = UUID.randomUUID().toString()
    private val initial = InstallmentCreateForm(firstDueEpochDay = todayEpochDay())

    private val _state = MutableStateFlow(InstallmentCreateUiState(form = initial, initialForm = initial))
    val state: StateFlow<InstallmentCreateUiState> = _state.asStateFlow()

    /**
     * Starts a clean form session (S10b-2 F1). Called on every close path of the overlay (close, discard,
     * created, dispose) because the ViewModel is activity-scoped and never recreated. A no-op while a save
     * is in progress. Clears the form, the mutation and the discard dialog, regenerates the plan uuid
     * (a new form is a new plan) and recomputes the first due day from today. Categories are kept.
     */
    fun resetSession() {
        if (_state.value.mutation is InstallmentPlanMutationState.InProgress) return
        planUuid = UUID.randomUUID().toString()
        val fresh = InstallmentCreateForm(firstDueEpochDay = todayEpochDay())
        _state.update {
            it.copy(
                form = fresh,
                initialForm = fresh,
                mutation = InstallmentPlanMutationState.Idle,
                showDiscardDialog = false
            )
        }
    }

    init {
        loadCategories()
    }

    fun loadCategories() {
        viewModelScope.launch {
            try {
                val options = categoryRepository.getAll().map { InstallmentCategoryOption(it.id, it.name) }
                _state.update { it.copy(categories = options, categoriesFailed = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Installment create categories failed: class=%s", e::class.java.simpleName)
                _state.update { it.copy(categoriesFailed = true) }
            }
        }
    }

    private fun edit(change: (InstallmentCreateForm) -> InstallmentCreateForm) {
        _state.update { current ->
            if (current.mutation is InstallmentPlanMutationState.InProgress) return@update current
            // Editing after a refusal clears it so the user sees the form is live again.
            current.copy(form = change(current.form), mutation = InstallmentPlanMutationState.Idle)
        }
    }

    fun setName(value: String) = edit { it.copy(name = value.take(InstallmentCreateForm.MAX_NAME_CHARS)) }
    fun setLender(value: String) = edit { it.copy(lender = value.take(InstallmentCreateForm.MAX_LENDER_CHARS)) }
    fun setCurrency(value: InstallmentCurrency) = edit { it.copy(currency = value) }
    fun setAmountText(value: String) = edit { it.copy(amountText = value.take(InstallmentCreateForm.MAX_AMOUNT_CHARS)) }
    fun setCountText(value: String) = edit { it.copy(countText = value.filter(Char::isDigit).take(3)) }
    fun setFirstDueEpochDay(value: Long) = edit { it.copy(firstDueEpochDay = value) }
    fun setMode(value: InstallmentFulfilmentMode) = edit { it.copy(mode = value) }
    fun setCategory(id: Long) = edit { it.copy(categoryId = id) }
    fun setPaidBeforeText(value: String) = edit { it.copy(paidBeforeText = value.filter(Char::isDigit).take(3)) }

    /** Back or the top-bar arrow: a dirty form asks first (discard dialog). Nothing closes while saving. */
    fun requestClose(close: () -> Unit) {
        val current = _state.value
        if (current.mutation is InstallmentPlanMutationState.InProgress) return
        if (current.isDirty) _state.update { it.copy(showDiscardDialog = true) } else close()
    }

    fun cancelDiscard() = _state.update { it.copy(showDiscardDialog = false) }

    fun save() {
        val current = _state.value
        if (current.mutation is InstallmentPlanMutationState.InProgress) return
        val form = current.form
        if (!form.nameOk) {
            // INV-34: the plan name is required; the coordinator is never called without one.
            _state.update {
                it.copy(
                    mutation = InstallmentPlanMutationState.Refused(
                        InstallmentPlanOperation.CREATE,
                        InstallmentRefusalCode.PLAN_NAME_REQUIRED
                    )
                )
            }
            return
        }
        if (!form.canSave) return
        val amountMinor = form.amountMinor ?: return
        val count = form.count ?: return
        val terms = InstallmentPlanTerms(
            planUuid = planUuid,
            displayName = form.name.trim(),
            lenderLabel = form.lender.trim().takeIf { it.isNotEmpty() },
            currency = form.currency.code,
            splitMethod = InstallmentSplitMethod.FIXED_PAYMENT,
            declaredTotal = null,
            fixedPaymentAmount = InstallmentAmountInput.toAmount(amountMinor),
            installmentCount = count,
            firstDueEpochDay = form.firstDueEpochDay,
            residualPlacement = InstallmentResidualPlacement.FIRST,
            fulfilmentMode = form.mode,
            // OQ-18 / deferral 49(b): the paid-before field is hidden until the first-due picker exists
            // (with firstDue = today = declaredOn every non-zero count is refused), so 0 is always sent.
            paidBeforeTrackingCount = 0,
            declaredOnEpochDay = todayEpochDay()
        )
        val categoryId = form.categoryId
        _state.update { it.copy(mutation = InstallmentPlanMutationState.InProgress(InstallmentPlanOperation.CREATE)) }
        viewModelScope.launch {
            try {
                val outcome = executeInstallmentPlanOp(
                    InstallmentPlanOperation.CREATE,
                    postCommitDispatcher,
                    planIdOf = { ref: com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanRef -> ref.planId }
                ) {
                    coordinator.createStandalone(terms, categoryId, PaymentMethod.UNKNOWN)
                }
                _state.update { it.copy(mutation = outcome) }
            } catch (e: CancellationException) {
                // A cancelled save must not leave the form stuck InProgress (S10b-2 F3); then rethrow.
                _state.update {
                    if (it.mutation is InstallmentPlanMutationState.InProgress) {
                        it.copy(mutation = InstallmentPlanMutationState.Idle)
                    } else {
                        it
                    }
                }
                throw e
            }
        }
    }

    private fun todayEpochDay(): Long =
        Instant.ofEpochMilli(timeProvider.now()).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
}

/** Full-screen create overlay hosted by the overview (no navigation destination). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallmentCreateScreen(
    onClose: () -> Unit,
    onCreated: (Long) -> Unit,
    viewModel: InstallmentCreateViewModel = hiltViewModel(key = "installment_create")
) {
    val state by viewModel.state.collectAsState()
    val mutation = state.mutation
    // The ViewModel is activity-scoped and never recreated: every close path starts a clean session (F1).
    val closeAndReset = {
        onClose()
        viewModel.resetSession()
    }
    DisposableEffect(viewModel) { onDispose { viewModel.resetSession() } }
    LaunchedEffect(mutation) {
        if (mutation is InstallmentPlanMutationState.Success && mutation.planId != null) {
            onCreated(mutation.planId)
            viewModel.resetSession()
        }
    }
    val running = mutation is InstallmentPlanMutationState.InProgress
    val form = state.form
    Dialog(
        onDismissRequest = { viewModel.requestClose(closeAndReset) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        BackHandler { viewModel.requestClose(closeAndReset) }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.installments_create_title)) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.requestClose(closeAndReset) }, enabled = !running) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.installments_back))
                        }
                    },
                    actions = {
                        TextButton(onClick = viewModel::save, enabled = form.canSave && !running) {
                            Text(stringResource(if (running) R.string.installments_action_working else R.string.installments_create_save))
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.installments_create_name)) },
                    supportingText = { Text(stringResource(R.string.installments_create_name_helper)) },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.lender,
                    onValueChange = viewModel::setLender,
                    label = { Text(stringResource(R.string.installments_create_lender)) },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.installments_create_currency), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InstallmentCurrency.entries.forEach { currency ->
                        FilterChip(
                            selected = form.currency == currency,
                            onClick = { viewModel.setCurrency(currency) },
                            enabled = !running,
                            label = { Text(currency.code) }
                        )
                    }
                }
                OutlinedTextField(
                    value = form.amountText,
                    onValueChange = viewModel::setAmountText,
                    label = { Text(stringResource(R.string.installments_create_amount)) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.countText,
                    onValueChange = viewModel::setCountText,
                    label = { Text(stringResource(R.string.installments_create_count)) },
                    supportingText = { Text(stringResource(R.string.installments_create_count_helper)) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                // OQ-18 paid-before is hidden until the first-due picker exists (deferral 49(b), F2).
                if (InstallmentCreateForm.SHOW_PAID_BEFORE_FIELD) {
                    OutlinedTextField(
                        value = form.paidBeforeText,
                        onValueChange = viewModel::setPaidBeforeText,
                        label = { Text(stringResource(R.string.installments_create_paid_before)) },
                        supportingText = { Text(stringResource(R.string.installments_create_paid_before_helper)) },
                        singleLine = true,
                        enabled = !running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(stringResource(R.string.installments_create_mode), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = form.mode == InstallmentFulfilmentMode.AUTO_RECORD,
                        onClick = { viewModel.setMode(InstallmentFulfilmentMode.AUTO_RECORD) },
                        enabled = !running,
                        label = { Text(stringResource(R.string.installments_create_mode_auto)) }
                    )
                    FilterChip(
                        selected = form.mode == InstallmentFulfilmentMode.CONFIRM,
                        onClick = { viewModel.setMode(InstallmentFulfilmentMode.CONFIRM) },
                        enabled = !running,
                        label = { Text(stringResource(R.string.installments_create_mode_confirm)) }
                    )
                }
                Text(stringResource(R.string.installments_create_category), style = MaterialTheme.typography.labelLarge)
                if (state.categoriesFailed) {
                    TextButton(onClick = viewModel::loadCategories) {
                        Text(stringResource(R.string.installments_create_categories_failed))
                    }
                }
                state.categories.forEach { option ->
                    FilterChip(
                        selected = form.categoryId == option.id,
                        onClick = { viewModel.setCategory(option.id) },
                        enabled = !running,
                        label = { Text(option.name) }
                    )
                }
                installmentPlanMutationMessage(mutation)?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.installments_create_estimate), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.installments_create_privacy), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (state.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDiscard,
            title = { Text(stringResource(R.string.installments_action_discard_title)) },
            text = { Text(stringResource(R.string.installments_action_discard_body)) },
            confirmButton = { TextButton(onClick = closeAndReset) { Text(stringResource(R.string.installments_action_discard)) } },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDiscard) { Text(stringResource(R.string.installments_action_cancel)) }
            }
        )
    }
}
