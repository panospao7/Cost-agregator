package com.yourname.expensetracker.ui.screens.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DeleteCategoryResult
import com.yourname.expensetracker.data.repository.RenameCategoryResult
import dagger.hilt.android.lifecycle.HiltViewModel
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.util.guardedWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** State of the rename dialog. [error] is an inline, bounded message; [inFlight] disables confirm. */
data class RenameDialogState(
    val categoryId: Long,
    val currentName: String,
    val error: UiText? = null,
    val inFlight: Boolean = false
)

/** Expense count shown in the delete dialog. Unavailable is never rendered as zero. */
sealed class ExpenseCountState {
    data object Loading : ExpenseCountState()
    data class Loaded(val count: Int) : ExpenseCountState()
    data object Unavailable : ExpenseCountState()
}

/**
 * State of the delete dialog. [candidates] never contains the category being deleted;
 * [replacementId] defaults to Uncategorized.
 */
data class DeleteDialogState(
    val categoryId: Long,
    val categoryName: String,
    val candidates: List<Category>,
    val replacementId: Long?,
    val expenseCount: ExpenseCountState = ExpenseCountState.Loading,
    val error: UiText? = null,
    val inFlight: Boolean = false
) {
    val replacement: Category? get() = candidates.firstOrNull { it.id == replacementId }

    /** Confirm needs a loaded-or-unavailable count (not loading), a valid replacement, and no write in flight. */
    val canConfirm: Boolean
        get() = !inFlight && replacement != null && expenseCount !is ExpenseCountState.Loading
}

@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repository: CategoryRepository
) : ViewModel() {

    val categories: StateFlow<List<Category>> = repository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // R18b: one-shot bounded message when a write hits the backup/restore maintenance write barrier.
    private val _writeBlockedMessages = MutableSharedFlow<UiText>(extraBufferCapacity = 1)
    val writeBlockedMessages: SharedFlow<UiText> = _writeBlockedMessages.asSharedFlow()

    // One-shot snackbar messages for rename/delete outcomes (always string resources).
    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    private val _renameDialog = MutableStateFlow<RenameDialogState?>(null)
    val renameDialog: StateFlow<RenameDialogState?> = _renameDialog.asStateFlow()

    private val _deleteDialog = MutableStateFlow<DeleteDialogState?>(null)
    val deleteDialog: StateFlow<DeleteDialogState?> = _deleteDialog.asStateFlow()

    private fun emitWriteBlocked(message: UiText) {
        _writeBlockedMessages.tryEmit(message)
    }

    private fun emitMessage(resId: Int, vararg args: Any) {
        _messages.tryEmit(UiText.StringResource(resId, args.toList()))
    }

    init {
        // Seed default categories on first run
        viewModelScope.launch {
            guardedWrite(::emitWriteBlocked) {
                repository.ensureDefaultCategories()
            }
        }
    }

    fun addCategory(name: String, icon: String, color: String) {
        viewModelScope.launch {
            guardedWrite(::emitWriteBlocked) {
                repository.addCategory(name, icon, color)
            }
        }
    }

    // ---- rename ---------------------------------------------------------------------------------

    /** Opens the rename dialog for a non-default category; default or missing categories show a message instead. */
    fun requestRename(categoryId: Long) {
        if (_renameDialog.value != null) return
        viewModelScope.launch {
            val category = repository.allCategories.first().firstOrNull { it.id == categoryId }
            when {
                category == null -> emitMessage(R.string.category_manage_not_found)
                category.isDefault -> emitMessage(R.string.category_manage_default_protected)
                else -> _renameDialog.compareAndSet(null, RenameDialogState(category.id, category.name))
            }
        }
    }

    fun dismissRename() {
        // A write already in flight cannot be cancelled by dismissing; keep the dialog until it settles.
        _renameDialog.update { if (it?.inFlight == true) it else null }
    }

    fun renameCategory(categoryId: Long, name: String) {
        val current = _renameDialog.value
        // In-flight guard: a second tap while the first write runs is ignored.
        if (current == null || current.categoryId != categoryId || current.inFlight) return
        if (!_renameDialog.compareAndSet(current, current.copy(inFlight = true, error = null))) return
        viewModelScope.launch {
            try {
                val result = guardedWrite(::emitWriteBlocked) {
                    repository.renameCategory(categoryId, name)
                }
                if (result == null) {
                    // Blocked by the maintenance barrier: keep the dialog open so the user can retry.
                    settleRename()
                    return@launch
                }
                when (result) {
                    is RenameCategoryResult.Renamed -> {
                        _renameDialog.value = null
                        emitMessage(R.string.category_rename_success, name.trim())
                    }
                    is RenameCategoryResult.EmptyName ->
                        failRename(UiText.StringResource(R.string.category_error_name_empty))
                    is RenameCategoryResult.InvalidCharacters ->
                        failRename(UiText.StringResource(R.string.category_error_invalid_characters))
                    is RenameCategoryResult.Duplicate ->
                        failRename(UiText.StringResource(R.string.category_rename_error_duplicate))
                    is RenameCategoryResult.CannotRenameDefault -> {
                        _renameDialog.value = null
                        emitMessage(R.string.category_manage_default_protected)
                    }
                    is RenameCategoryResult.NotFound -> {
                        _renameDialog.value = null
                        emitMessage(R.string.category_manage_not_found)
                    }
                }
            } catch (e: CancellationException) {
                settleRename()
                throw e
            } catch (e: Exception) {
                Timber.w("CATEGORY_RENAME_FAILED class=%s", e::class.java.simpleName)
                settleRename()
                emitMessage(R.string.category_manage_failed)
            }
        }
    }

    private fun settleRename() {
        _renameDialog.update { it?.copy(inFlight = false) }
    }

    private fun failRename(error: UiText) {
        _renameDialog.update { it?.copy(inFlight = false, error = error) }
    }

    // ---- delete ---------------------------------------------------------------------------------

    /**
     * Opens the delete dialog for a non-default category and loads the expense count.
     * The replacement list excludes the source and defaults to Uncategorized.
     */
    fun requestDelete(categoryId: Long) {
        if (_deleteDialog.value != null) return
        viewModelScope.launch {
            val all = repository.allCategories.first()
            val category = all.firstOrNull { it.id == categoryId }
            if (category == null) {
                emitMessage(R.string.category_manage_not_found)
                return@launch
            }
            if (category.isDefault) {
                emitMessage(R.string.category_manage_default_protected)
                return@launch
            }
            val candidates = all.filter { it.id != categoryId }
            val defaultReplacement = candidates.firstOrNull { it.name.equals(UNCATEGORIZED_NAME, ignoreCase = true) }
                ?: candidates.firstOrNull()
            val opened = DeleteDialogState(
                categoryId = category.id,
                categoryName = category.name,
                candidates = candidates,
                replacementId = defaultReplacement?.id
            )
            if (!_deleteDialog.compareAndSet(null, opened)) return@launch
            loadExpenseCount(categoryId)
        }
    }

    private suspend fun loadExpenseCount(categoryId: Long) {
        val state = try {
            ExpenseCountState.Loaded(repository.countExpensesForCategory(categoryId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("CATEGORY_COUNT_FAILED class=%s", e::class.java.simpleName)
            ExpenseCountState.Unavailable
        }
        _deleteDialog.update {
            if (it != null && it.categoryId == categoryId) it.copy(expenseCount = state) else it
        }
    }

    fun selectReplacement(replacementId: Long) {
        _deleteDialog.update { dialog ->
            if (dialog == null || dialog.inFlight || dialog.candidates.none { it.id == replacementId }) {
                dialog
            } else {
                dialog.copy(replacementId = replacementId, error = null)
            }
        }
    }

    fun dismissDelete() {
        _deleteDialog.update { if (it?.inFlight == true) it else null }
    }

    fun confirmDelete(categoryId: Long, replacementId: Long) {
        val current = _deleteDialog.value
        // In-flight guard: a second tap while the first write runs is ignored.
        if (current == null || current.categoryId != categoryId || current.inFlight) return
        if (current.candidates.none { it.id == replacementId }) return
        if (!_deleteDialog.compareAndSet(current, current.copy(inFlight = true, error = null))) return
        val replacementName = current.candidates.first { it.id == replacementId }.name
        viewModelScope.launch {
            try {
                val result = guardedWrite(::emitWriteBlocked) {
                    repository.deleteCategory(categoryId, replacementId)
                }
                if (result == null) {
                    // Blocked by the maintenance barrier: keep the dialog open so the user can retry.
                    settleDelete()
                    return@launch
                }
                when (result) {
                    is DeleteCategoryResult.Deleted -> {
                        _deleteDialog.value = null
                        emitMessage(R.string.category_delete_success, current.categoryName, replacementName)
                    }
                    is DeleteCategoryResult.CannotDeleteDefault -> {
                        _deleteDialog.value = null
                        emitMessage(R.string.category_manage_default_protected)
                    }
                    is DeleteCategoryResult.NotFound -> {
                        _deleteDialog.value = null
                        emitMessage(R.string.category_manage_not_found)
                    }
                    is DeleteCategoryResult.ReplacementInvalid,
                    is DeleteCategoryResult.SameAsSource ->
                        _deleteDialog.update {
                            it?.copy(
                                inFlight = false,
                                error = UiText.StringResource(R.string.category_delete_error_replacement)
                            )
                        }
                }
            } catch (e: CancellationException) {
                settleDelete()
                throw e
            } catch (e: Exception) {
                Timber.w("CATEGORY_DELETE_FAILED class=%s", e::class.java.simpleName)
                settleDelete()
                emitMessage(R.string.category_manage_failed)
            }
        }
    }

    private fun settleDelete() {
        _deleteDialog.update { it?.copy(inFlight = false) }
    }

    private companion object {
        const val UNCATEGORIZED_NAME = "Uncategorized"
    }
}
