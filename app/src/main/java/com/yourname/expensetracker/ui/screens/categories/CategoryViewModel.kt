package com.yourname.expensetracker.ui.screens.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DeleteCategoryResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repository: CategoryRepository
) : ViewModel() {

    val categories: StateFlow<List<Category>> = repository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Seed default categories on first run
        viewModelScope.launch {
            repository.ensureDefaultCategories()
        }
    }

    fun addCategory(name: String, icon: String, color: String) {
        viewModelScope.launch {
            repository.addCategory(name, icon, color)
        }
    }

    fun deleteCategory(categoryId: Long) {
        viewModelScope.launch {
            when (val result = repository.deleteCategory(categoryId)) {
                is DeleteCategoryResult.NotFound -> { /* update state with error */ }
                is DeleteCategoryResult.CannotDeleteDefault -> { /* show cannot delete default */ }
                is DeleteCategoryResult.HasBudgets -> { /* store in state for UI dialog */ }
                is DeleteCategoryResult.Deleted -> { /* refresh categories */ }
            }
        }
    }

    // Future: edit
}
