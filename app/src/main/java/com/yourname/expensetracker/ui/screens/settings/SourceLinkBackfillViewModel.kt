package com.yourname.expensetracker.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.provenance.SourceLinkBackfillWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PR8: ViewModel that exposes source-link backfill progress to a settings UI.
 *
 * Usage:
 *   viewModel.startBackfill()
 *   viewModel.progress.collect { progress -> /* update UI */ }
 *   viewModel.isRunning.collect { running -> /* show/hide spinner */ }
 */
@HiltViewModel
class SourceLinkBackfillViewModel @Inject constructor(
    private val backfillWorker: SourceLinkBackfillWorker
) : ViewModel() {

    private val _progress = MutableStateFlow<SourceLinkBackfillWorker.BackfillProgress?>(null)
    val progress: StateFlow<SourceLinkBackfillWorker.BackfillProgress?> = _progress.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * Start (or restart) the backfill. No-op if already running.
     */
    fun startBackfill() {
        if (_isRunning.value) return
        viewModelScope.launch {
            _isRunning.value = true
            _progress.value = null
            try {
                val result = backfillWorker.runBackfill { progress ->
                    _progress.value = progress
                }
                _progress.value = result
            } finally {
                _isRunning.value = false
            }
        }
    }
}
