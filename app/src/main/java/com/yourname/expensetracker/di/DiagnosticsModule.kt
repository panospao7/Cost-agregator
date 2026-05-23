package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.diagnostics.CompositeDiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.CompositeOperationRunRecorder
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.debug.DiagnosticsRepository
import com.yourname.expensetracker.domain.debug.DiagnosticsRepositoryImpl
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleEventWriter
import com.yourname.expensetracker.domain.receipt.lifecycle.RoomReceiptLifecycleEventWriter
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleEventWriter
import com.yourname.expensetracker.domain.recurring.lifecycle.RoomRecurringLifecycleEventWriter
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunnerImpl
import com.yourname.expensetracker.domain.sideeffect.CompositeSideEffectEventWriter
import com.yourname.expensetracker.domain.sideeffect.SideEffectEventWriter
import com.yourname.expensetracker.domain.sideeffect.DiagnosticSideEffectEventWriter
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
    abstract fun bindDiagnosticEventWriter(impl: CompositeDiagnosticEventWriter): DiagnosticEventWriter

    @Binds @Singleton
    abstract fun bindTransactionLifecycleEventWriter(impl: RoomTransactionLifecycleEventWriter): TransactionLifecycleEventWriter

    @Binds @Singleton
    abstract fun bindReceiptLifecycleEventWriter(impl: RoomReceiptLifecycleEventWriter): ReceiptLifecycleEventWriter

    @Binds @Singleton
    abstract fun bindRecurringLifecycleEventWriter(impl: RoomRecurringLifecycleEventWriter): RecurringLifecycleEventWriter

    @Binds @Singleton
    abstract fun bindOperationRunRecorder(impl: CompositeOperationRunRecorder): OperationRunRecorder

    @Binds @Singleton
    abstract fun bindWorkerRunLogger(impl: WorkerRunLoggerImpl): WorkerRunLogger

    @Binds @Singleton
    abstract fun bindDiagnosticsRepository(impl: DiagnosticsRepositoryImpl): DiagnosticsRepository

    @Binds @Singleton
    abstract fun bindPostCommitActionRunner(impl: PostCommitActionRunnerImpl): PostCommitActionRunner

    @Binds @Singleton
    abstract fun bindSideEffectEventWriter(impl: CompositeSideEffectEventWriter): SideEffectEventWriter
}
