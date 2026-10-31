package com.yourname.expensetracker.ui.screens.installments.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanRef
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanMutationState
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanOperation
import com.yourname.expensetracker.ui.screens.installments.actions.executeInstallmentPlanOp
import com.yourname.expensetracker.ui.screens.installments.actions.installmentPlanMutationMessage
import com.yourname.expensetracker.ui.screens.installments.create.InstallmentCategoryOption
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** The editable details of E3 (spec 7.4): name, lender, category and recording mode. */
data class InstallmentEditForm(
    val name: String = "",
    val lender: String = "",
    val categoryId: Long? = null,
    val mode: InstallmentFulfilmentMode = InstallmentFulfilmentMode.AUTO_RECORD
) {
    val nameOk: Boolean get() = name.isNotBlank()
}

data class InstallmentEditUiState(
    val loaded: Boolean = false,
    /** Bounded: the plan could not be read (missing or unreadable). */
    val loadFailed: Boolean = false,
    val kind: InstallmentPlanKind = InstallmentPlanKind.STANDALONE,
    val status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,
    val currencyCode: String = "",
    val form: InstallmentEditForm = InstallmentEditForm(),
    val initialForm: InstallmentEditForm = InstallmentEditForm(),
    val categories: List<InstallmentCategoryOption> = emptyList(),
    val mutation: InstallmentPlanMutationState = InstallmentPlanMutationState.Idle,
    val showDiscardDialog: Boolean = false
) {
    val isDirty: Boolean get() = form != initialForm

    /** F4: a COMPLETED plan keeps its recording mode (the schedule is over), so the mode chips are disabled. */
    val modeEditable: Boolean get() = status != InstallmentPlanStatus.COMPLETED

    /** A standalone plan needs a category (INV-33); a purchase plan may have none. */
    val canSave: Boolean
        get() = loaded && isDirty && form.nameOk && (kind != InstallmentPlanKind.STANDALONE || form.categoryId != null)
}

/**
 * Edit plan details (loan-installments S10b-2, spec 7.4 E3). One coordinator operation:
 * [InstallmentPlanLifecycleCoordinator.editDetails]. Every argument is the full new value, so the
 * current category, currency and the untouched fields are loaded from the read model and sent back
 * unchanged. The currency is shown, not editable here. A blank name never reaches the coordinator
 * (INV-34). `CancellationException` is rethrown.
 */
@HiltViewModel
class InstallmentEditViewModel @Inject constructor(
    private val coordinator: InstallmentPlanLifecycleCoordinator,
    private val postCommitDispatcher: InstallmentPostCommitDispatcher,
    private val readRepository: InstallmentPlanReadRepository,
    private val categoryRepository: CategoryRepository,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _state = MutableStateFlow(InstallmentEditUiState())
    val state: StateFlow<InstallmentEditUiState> = _state.asStateFlow()

    private var planId: Long? = null
    private var loadJob: Job? = null

    /**
     * Starts a clean edit session (S10b-2 F1). Called on every close path of the overlay (close, discard,
     * saved, dispose) because the ViewModel is activity-scoped and never recreated. A no-op while a save
     * is in progress. Clears the form, the mutation and the discard dialog and forgets the plan id so the
     * next [start] reloads from the read model.
     */
    fun resetSession() {
        if (_state.value.mutation is InstallmentPlanMutationState.InProgress) return
        loadJob?.cancel()
        loadJob = null
        planId = null
        _state.value = InstallmentEditUiState()
    }

    /** Loads the plan once per id; calling it again with the same id is a no-op. */
    fun start(planId: Long) {
        if (this.planId == planId) return
        this.planId = planId
        _state.value = InstallmentEditUiState()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val detail = readRepository.loadDetail(planId)
                if (detail == null) {
                    _state.update { it.copy(loadFailed = true) }
                    return@launch
                }
                val categories = try {
                    categoryRepository.getAll().map { InstallmentCategoryOption(it.id, it.name) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w("Installment edit categories failed: class=%s", e::class.java.simpleName)
                    emptyList()
                }
                val form = InstallmentEditForm(
                    name = detail.summary.displayName,
                    lender = detail.lenderLabel.orEmpty(),
                    categoryId = detail.categoryId,
                    mode = detail.summary.fulfilmentMode
                )
                _state.value = InstallmentEditUiState(
                    loaded = true,
                    kind = detail.kind,
                    status = detail.summary.status,
                    currencyCode = detail.summary.currency.code,
                    form = form,
                    initialForm = form,
                    categories = categories
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Bounded class name only (INV-31).
                Timber.w("Installment edit load failed: class=%s", e::class.java.simpleName)
                _state.update { it.copy(loadFailed = true) }
            }
        }
    }

    private fun edit(change: (InstallmentEditForm) -> InstallmentEditForm) {
        _state.update { current ->
            if (!current.loaded || current.mutation is InstallmentPlanMutationState.InProgress) return@update current
            current.copy(form = change(current.form), mutation = InstallmentPlanMutationState.Idle)
        }
    }

    fun setName(value: String) = edit { it.copy(name = value.take(MAX_NAME_CHARS)) }
    fun setLender(value: String) = edit { it.copy(lender = value.take(MAX_NAME_CHARS)) }
    fun setCategory(id: Long?) = edit { it.copy(categoryId = id) }
    fun setMode(value: InstallmentFulfilmentMode) {
        if (!_state.value.modeEditable) return
        edit { it.copy(mode = value) }
    }

    /** Back: a dirty form asks first (discard dialog). Nothing closes while saving. */
    fun requestClose(close: () -> Unit) {
        val current = _state.value
        if (current.mutation is InstallmentPlanMutationState.InProgress) return
        if (current.isDirty) _state.update { it.copy(showDiscardDialog = true) } else close()
    }

    fun cancelDiscard() = _state.update { it.copy(showDiscardDialog = false) }

    fun save() {
        val current = _state.value
        val id = planId ?: return
        if (!current.loaded || current.mutation is InstallmentPlanMutationState.InProgress) return
        val form = current.form
        if (!form.nameOk) {
            // INV-34: the plan name is required.
            _state.update {
                it.copy(
                    mutation = InstallmentPlanMutationState.Refused(
                        InstallmentPlanOperation.EDIT,
                        InstallmentRefusalCode.PLAN_NAME_REQUIRED
                    )
                )
            }
            return
        }
        if (!current.canSave) return
        _state.update { it.copy(mutation = InstallmentPlanMutationState.InProgress(InstallmentPlanOperation.EDIT)) }
        viewModelScope.launch {
            try {
                val outcome = executeInstallmentPlanOp(
                    InstallmentPlanOperation.EDIT,
                    postCommitDispatcher,
                    planIdOf = { ref: InstallmentPlanRef -> ref.planId }
                ) {
                    coordinator.editDetails(
                        planId = id,
                        displayName = form.name.trim(),
                        lenderLabel = form.lender.trim().takeIf { it.isNotEmpty() },
                        categoryId = form.categoryId,
                        fulfilmentMode = form.mode,
                        currency = current.currencyCode,
                        todayEpochDay = Instant.ofEpochMilli(timeProvider.now())
                            .atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
                    )
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

    private companion object {
        const val MAX_NAME_CHARS = 80
    }
}

/** Full-screen edit overlay hosted by the plan detail (no navigation destination). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallmentEditScreen(
    planId: Long,
    onClose: () -> Unit,
    viewModel: InstallmentEditViewModel = hiltViewModel(key = "installment_edit_$planId")
) {
    LaunchedEffect(planId) { viewModel.start(planId) }
    val state by viewModel.state.collectAsState()
    val mutation = state.mutation
    // The ViewModel is activity-scoped and never recreated: every close path starts a clean session (F1).
    val closeAndReset = {
        onClose()
        viewModel.resetSession()
    }
    DisposableEffect(viewModel) { onDispose { viewModel.resetSession() } }
    // A saved edit (also AlreadyInState) closes the overlay; the detail refreshes from the read model.
    LaunchedEffect(mutation) { if (mutation is InstallmentPlanMutationState.Success) closeAndReset() }
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
                    title = { Text(stringResource(R.string.installments_edit_title)) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.requestClose(closeAndReset) }, enabled = !running) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.installments_back))
                        }
                    },
                    actions = {
                        TextButton(onClick = viewModel::save, enabled = state.canSave && !running) {
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
                if (state.loadFailed) {
                    Text(stringResource(R.string.installments_error_load_detail), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(stringResource(R.string.installments_edit_helper), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = form.name,
                        onValueChange = viewModel::setName,
                        label = { Text(stringResource(R.string.installments_create_name)) },
                        singleLine = true,
                        enabled = state.loaded && !running,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = form.lender,
                        onValueChange = viewModel::setLender,
                        label = { Text(stringResource(R.string.installments_create_lender)) },
                        singleLine = true,
                        enabled = state.loaded && !running,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.installments_create_mode), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = form.mode == InstallmentFulfilmentMode.AUTO_RECORD,
                            onClick = { viewModel.setMode(InstallmentFulfilmentMode.AUTO_RECORD) },
                            enabled = state.loaded && !running && state.modeEditable,
                            label = { Text(stringResource(R.string.installments_create_mode_auto)) }
                        )
                        FilterChip(
                            selected = form.mode == InstallmentFulfilmentMode.CONFIRM,
                            onClick = { viewModel.setMode(InstallmentFulfilmentMode.CONFIRM) },
                            enabled = state.loaded && !running && state.modeEditable,
                            label = { Text(stringResource(R.string.installments_create_mode_confirm)) }
                        )
                    }
                    Text(stringResource(R.string.installments_create_category), style = MaterialTheme.typography.labelLarge)
                    if (state.kind == InstallmentPlanKind.PURCHASE_ATTACHED) {
                        FilterChip(
                            selected = form.categoryId == null,
                            onClick = { viewModel.setCategory(null) },
                            enabled = state.loaded && !running,
                            label = { Text(stringResource(R.string.installments_edit_no_category)) }
                        )
                    }
                    state.categories.forEach { option ->
                        FilterChip(
                            selected = form.categoryId == option.id,
                            onClick = { viewModel.setCategory(option.id) },
                            enabled = state.loaded && !running,
                            label = { Text(option.name) }
                        )
                    }
                    installmentPlanMutationMessage(mutation)?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                    }
                }
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
