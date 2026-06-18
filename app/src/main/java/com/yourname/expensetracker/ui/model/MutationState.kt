package com.yourname.expensetracker.ui.model

import com.yourname.expensetracker.domain.model.UiText

/**
 * Universal frontend contract for mutation operations (save/delete/update).
 *
 * Rules:
 * 1. Dialogs/sheets close only after success event
 * 2. Double-taps cannot create duplicate writes (isRunning guard)
 * 3. Failures are shown in-context (not just snackbar)
 *
 * Usage in ViewModel:
 * ```kotlin
 * private val _mutation = MutableStateFlow(MutationState.idle())
 * val mutation: StateFlow<MutationState> = _mutation.asStateFlow()
 *
 * fun save() {
 *     if (_mutation.value.isRunning) return
 *     _mutation.value = MutationState.running("save")
 *     viewModelScope.launch {
 *         try {
 *             repository.save(...)
 *             _mutation.value = MutationState.success("save")
 *         } catch (e: Exception) {
 *             _mutation.value = MutationState.error("save", UiText.from(e.message ?: "Failed"))
 *         }
 *     }
 * }
 * ```
 *
 * Usage in UI:
 * ```kotlin
 * val mutation by viewModel.mutation.collectAsState()
 * if (mutation.isSuccess) { dismiss() }
 * if (mutation.error != null) { ShowError(mutation.error) }
 * Button(enabled = !mutation.isRunning) { viewModel.save() }
 * ```
 */
data class MutationState(
    val operation: String? = null,
    val targetId: Long? = null,
    val isRunning: Boolean = false,
    val isSuccess: Boolean = false,
    val error: UiText? = null
) {
    companion object {
        fun idle() = MutationState()

        fun running(operation: String, targetId: Long? = null) = MutationState(
            operation = operation,
            targetId = targetId,
            isRunning = true
        )

        fun success(operation: String, targetId: Long? = null) = MutationState(
            operation = operation,
            targetId = targetId,
            isSuccess = true
        )

        fun error(operation: String, message: UiText, targetId: Long? = null) = MutationState(
            operation = operation,
            targetId = targetId,
            error = message
        )
    }
}
