package com.yourname.expensetracker.ui.screens.captureskip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.util.guardedWrite
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row of the "Apps to skip" list. [label] is the app label or, when unavailable, the package name. */
data class SkipAppItem(val packageName: String, val label: String)

/**
 * UI state for the "Apps to skip" screen.
 *
 * [blocked] are apps whose notifications are skipped; [candidates] are packages already seen in
 * captured data that are not blocked yet. [inFlight] holds packages with a block/unblock running.
 */
data class AppsToSkipUiState(
    val isLoading: Boolean = true,
    val blocked: List<SkipAppItem> = emptyList(),
    val candidates: List<SkipAppItem> = emptyList(),
    val inFlight: Set<String> = emptySet(),
    val loadFailed: Boolean = false
)

/**
 * P-01-3: manages the notification capture skip list. Writes go only through
 * [NotificationRepository.blockPackage] / [NotificationRepository.unblockPackage] (barrier-guarded);
 * the capture gate, the service deferred path and the intake worker read the same table.
 *
 * Package names are never logged. While a backup/restore/reset holds the write barrier the user
 * sees the existing `ui_write_blocked_maintenance` message; any other failure is the generic one.
 */
@HiltViewModel
class AppsToSkipViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val labelResolver: AppLabelResolver,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val inFlight = MutableStateFlow<Set<String>>(emptySet())

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 1)
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    val uiState: StateFlow<AppsToSkipUiState> = combine(
        repository.getBlockedPackages(),
        repository.getAllPackages(),
        inFlight
    ) { blockedRows, seenPackages, busy ->
        val blockedNames = blockedRows.map { it.packageName }.distinct()
        val blockedSet = blockedNames.toSet()
        AppsToSkipUiState(
            isLoading = false,
            blocked = blockedNames.toItems(),
            candidates = seenPackages.distinct().filterNot { it in blockedSet }.toItems(),
            inFlight = busy
        )
    }
        .flowOn(ioDispatcher)
        .catch { e ->
            if (e is CancellationException) throw e
            emit(AppsToSkipUiState(isLoading = false, loadFailed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsToSkipUiState())

    /** Skip notifications from [packageName] from now on. Already-queued rows are dropped by the worker. */
    fun block(packageName: String) = runWrite(packageName) { repository.blockPackage(packageName) }

    /** Capture notifications from [packageName] again. */
    fun unblock(packageName: String) = runWrite(packageName) { repository.unblockPackage(packageName) }

    private fun runWrite(packageName: String, write: suspend () -> Unit) {
        // Claim the slot synchronously so a double tap on the same row is ignored.
        if (packageName in inFlight.value) return
        inFlight.value = inFlight.value + packageName
        viewModelScope.launch {
            try {
                guardedWrite(onBlocked = { _messages.tryEmit(it) }) { write() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(UiText.from(R.string.capture_skip_error_generic))
            } finally {
                inFlight.value = inFlight.value - packageName
            }
        }
    }

    private fun List<String>.toItems(): List<SkipAppItem> =
        map { SkipAppItem(packageName = it, label = labelResolver.label(it)) }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
}
