package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.diagnostics.RoomDiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.RoomOperationRunRecorder
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleEventWriter
import com.yourname.expensetracker.domain.receipt.lifecycle.RoomReceiptLifecycleEventWriter
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleEventWriter
import com.yourname.expensetracker.domain.recurring.lifecycle.RoomRecurringLifecycleEventWriter
import com.yourname.expensetracker.domain.transaction.lifecycle.RoomTransactionLifecycleEventWriter
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEventWriter
import com.yourname.expensetracker.domain.workers.WorkerRunLogger
import com.yourname.expensetracker.domain.workers.WorkerRunLoggerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {

    @Binds @Singleton
    abstract fun bindDiagnosticEventWriter(impl: RoomDiagnosticEventWriter): DiagnosticEventWriter

    @Binds @Singleton
    abstract fun bindTransactionLifecycleEventWriter(impl: RoomTransactionLifecycleEventWriter): TransactionLifecycleEventWriter

    @Binds @Singleton
    abstract fun bindReceiptLifecycleEventWriter(impl: RoomReceiptLifecycleEventWriter): ReceiptLifecycleEventWriter

    @Binds @Singleton
    abstract fun bindRecurringLifecycleEventWriter(impl: RoomRecurringLifecycleEventWriter): RecurringLifecycleEventWriter

    @Binds @Singleton
    abstract fun bindOperationRunRecorder(impl: RoomOperationRunRecorder): OperationRunRecorder

    @Binds @Singleton
    abstract fun bindWorkerRunLogger(impl: WorkerRunLoggerImpl): WorkerRunLogger
}
