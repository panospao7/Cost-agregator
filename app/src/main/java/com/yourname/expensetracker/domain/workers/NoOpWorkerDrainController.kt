package com.yourname.expensetracker.domain.workers

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op stub for [WorkerDrainController].
 * PR 4 replaces this with a real implementation backed by [WorkerLeaseRegistry].
 */
@Singleton
class NoOpWorkerDrainController @Inject constructor() : WorkerDrainController {
    override suspend fun requestStopAndAwaitDrain(operationName: String, timeoutMs: Long): Boolean {
        Timber.d("WorkerDrainController (no-op): drain requested for $operationName")
        return true
    }
}
