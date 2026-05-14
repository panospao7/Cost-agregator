package com.yourname.expensetracker.ui.model

import com.yourname.expensetracker.domain.model.UiText

/**
 * Universal frontend contract for loadable data states.
 *
 * Rule: An empty list is NOT success. Use [Empty] with a reason.
 *
 * Usage in ViewModel:
 * ```kotlin
 * private val _state = MutableStateFlow<LoadableUiState<List<Transaction>>>(LoadableUiState.Loading)
 *
 * init {
 *     viewModelScope.launch {
 *         try {
 *             val data = repository.getAll()
 *             _state.value = if (data.isEmpty()) {
 *                 LoadableUiState.Empty(UiText.from(R.string.no_transactions))
 *             } else {
 *                 LoadableUiState.Data(data)
 *             }
 *         } catch (e: Exception) {
 *             _state.value = LoadableUiState.Error(UiText.from(e.message ?: "Failed to load"))
 *         }
 *     }
 * }
 * ```
 *
 * Usage in UI:
 * ```kotlin
 * when (val state = uiState) {
 *     is LoadableUiState.Loading -> LoadingSkeleton()
 *     is LoadableUiState.Data -> ContentList(state.value)
 *     is LoadableUiState.Empty -> EmptyState(message = state.reason)
 *     is LoadableUiState.Error -> ErrorState(message = state.message, onRetry = { viewModel.reload() })
 * }
 * ```
 */
sealed interface LoadableUiState<out T> {
    data object Loading : LoadableUiState<Nothing>

    data class Data<T>(val value: T) : LoadableUiState<T>

    data class Empty(val reason: UiText) : LoadableUiState<Nothing>

    data class Error(
        val message: UiText,
        val isRetryable: Boolean = true
    ) : LoadableUiState<Nothing>
}

/**
 * Extension to safely get data or null.
 */
fun <T> LoadableUiState<T>.dataOrNull(): T? = when (this) {
    is LoadableUiState.Data -> value
    else -> null
}

/**
 * Extension to check if currently loading.
 */
fun <T> LoadableUiState<T>.isLoading(): Boolean = this is LoadableUiState.Loading
