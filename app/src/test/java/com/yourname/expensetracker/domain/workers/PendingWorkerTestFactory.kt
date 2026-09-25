package com.yourname.expensetracker.domain.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

/** Keeps test-scheduled work active without constructing injected production workers. */
class PendingWorkerTestFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker = PendingWorker(appContext, workerParameters)

    private class PendingWorker(
        appContext: Context,
        workerParameters: WorkerParameters
    ) : ListenableWorker(appContext, workerParameters) {
        private val result = SettableFuture.create<Result>()

        override fun startWork(): ListenableFuture<Result> = result

        override fun onStopped() {
            result.cancel(false)
            super.onStopped()
        }
    }
}
