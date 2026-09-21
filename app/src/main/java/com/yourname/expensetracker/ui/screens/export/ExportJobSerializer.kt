package com.yourname.expensetracker.ui.screens.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * RP-19 (19-C): serializes export runs so at most one export streams at a time.
 *
 * Starting a new export CANCELS the previous job and JOINS it (cancel-and-join)
 * before any new work begins. Previously the ViewModel only called
 * `exportJob?.cancel()` without joining, so two exports could stream temp files
 * (same non-unique `.tmp_<name>` path at the time) and final outputs
 * concurrently — a cancelled run and a fresh run could interleave file writes.
 *
 * The mechanics live here, outside the ViewModels, so the ViewModel diff stays a
 * minimal call-site delegation and the denied-UI state owned by RP-15 is not touched.
 */
@Singleton
class ExportJobSerializer @Inject constructor() {

    private var activeJob: Job? = null

    /**
     * Launches [block] into [scope] as the sole export job. If a previous job
     * is still active it is cancelled first, and this job suspends until the
     * predecessor has fully terminated (its `finally` cleanup included) before
     * running [block].
     *
     * @return the [Job] backing this export run.
     */
    fun launch(scope: CoroutineScope, block: suspend () -> Unit): Job {
        val prior = activeJob
        val job = scope.launch {
            prior?.let { previous ->
                previous.cancel()
                try {
                    previous.join()
                } catch (e: CancellationException) {
                    // THIS job was cancelled while waiting for the predecessor;
                    // propagate instead of silently continuing to run.
                    throw e
                }
            }
            block()
        }
        activeJob = job
        return job
    }

    /**
     * Cancels the currently active export job, if any.
     * Safe to call when no export is in progress.
     */
    fun cancelActive() {
        activeJob?.cancel()
        activeJob = null
    }
}
