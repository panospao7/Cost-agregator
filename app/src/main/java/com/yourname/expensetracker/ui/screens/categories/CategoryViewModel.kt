package com.yourname.expensetracker.ui.screens.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DeleteCategoryResult
import dagger.hilt.android.lifecycle.HiltViewModel
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.util.guardedWrite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repository: CategoryRepository
) : ViewModel() {

    val categories: StateFlow<List<Category>> = repository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // R18b: one-shot bounded message when a write hits the backup/restore maintenance write barrier.
    private val _writeBlockedMessages = MutableSharedFlow<UiText>(extraBufferCapacity = 1)
    val writeBlockedMessages: SharedFlow<UiText> = _writeBlockedMessages.asSharedFlow()

    private fun emitWriteBlocked(message: UiText) {
        _writeBlockedMessages.tryEmit(message)
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

    fun deleteCategory(categoryId: Long) {
        viewModelScope.launch {
            val result = guardedWrite(::emitWriteBlocked) {
                repository.deleteCategory(categoryId)
            } ?: return@launch
            when (result) {
                is DeleteCategoryResult.NotFound -> { /* update state with error */ }
                is DeleteCategoryResult.CannotDeleteDefault -> { /* show cannot delete default */ }
                is DeleteCategoryResult.HasBudgets -> { /* store in state for UI dialog */ }
                is DeleteCategoryResult.Deleted -> { /* refresh categories */ }
            }
        }
    }

    // Future: edit
}
