package com.yourname.expensetracker.data.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.CancellationSafe
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistry
import com.yourname.expensetracker.domain.workers.WorkerRegistry
import com.yourname.expensetracker.domain.workers.WorkerSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages maintenance mode for database restore operations.
 *
 * When activated, this pauses all 7 background workers and blocks notification
 * ingestion to ensure no writes occur during the restore process.
 *
 * State is persisted in [SharedPreferences] so it survives process death.
 */
@Singleton
class RestoreMaintenanceMode @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workerLeaseRegistry: dagger.Lazy<WorkerLeaseRegistry>,
    private val timeProvider: TimeProvider
) {
    class PersistenceException : IllegalStateException(MODE_PERSISTENCE_FAILURE)
    class WorkerRescheduleException : IllegalStateException(WORKER_RESCHEDULE_FAILURE)

    /** Test-only constructor — uses a no-op WorkerLeaseRegistry. */
    constructor(context: Context, timeProvider: TimeProvider) : this(
        context,
        dagger.Lazy {
            com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController().let {
                object : WorkerLeaseRegistry {
                    override suspend fun acquire(workerName: String) =
                        object : com.yourname.expensetracker.domain.workers.WorkerLease {
                            override val leaseId: String = "restore-mode-noop"
                            override suspend fun checkpoint(operation: String) {}
                            override fun close() {}
                        }

                    override suspend fun requestStopAll(reason: String) {}
                    override suspend fun awaitNoActiveWorkers(timeoutMs: Long) = true
                    override fun isStopRequested() = false
                    override fun resetStopFlag() {}
                }
            }
        },
        timeProvider
    )

    private val stateLock = PERSISTED_STATE_LOCK
    internal enum class CriticalSentinelIoStage { OPEN, WRITE, SYNC }
    internal var beforeCriticalSentinelIo: ((CriticalSentinelIoStage) -> Unit)? = null

    private val prefs: SharedPreferences? = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    enum class Mode(val label: String) {
        NORMAL("normal"),
        BACKUP_EXPORTING("backup_exporting"),
        RESTORE_PREPARING("restore_preparing"),
        RESTORE_STAGING("restore_staging"),
        RESTORE_SWAPPING("restore_swapping"),
        RESTORE_VERIFYING("restore_verifying"),
        RESTORE_ROLLING_BACK("restore_rolling_back"),
        ASSETS_RESTORING("assets_restoring"),
        RESETTING_DATABASE("resetting_database"),
        RESTORE_COMPLETE_RESTART_REQUIRED("restore_complete_restart_required"),
        CRITICAL_RECOVERY_REQUIRED("critical_recovery_required")
    }

    private data class PersistedState(
        val rawMode: Mode,
        val resumePending: Boolean,
        val effectiveMode: Mode
    )

    private data class ReadResult(
        val state: PersistedState? = null,
        val failureReason: String? = null
    )

    @Volatile
    private var persistenceHealthy = prefs != null
    private val initialRead = readPersistedState()
    private val initialMode = initialRead.state?.effectiveMode ?: Mode.CRITICAL_RECOVERY_REQUIRED
    private val _modeFlow = MutableStateFlow(initialMode)
    val modeFlow: StateFlow<Mode> = _modeFlow.asStateFlow()

    private val _operationalStateFlow = MutableStateFlow(toOperationalState(initialMode))
    val operationalStateFlow: StateFlow<AppOperationalState> get() = _operationalStateFlow

    init {
        val failureReason = initialRead.failureReason
        if (failureReason != null) {
            latchCriticalFromReadFailure(failureReason)
        } else if (
            initialMode == Mode.CRITICAL_RECOVERY_REQUIRED ||
            initialRead.state?.resumePending == true
        ) {
            synchronized(stateLock) {
                // Construction may have observed pending before another owner finished.
                val latest = readPersistedState()
                if (latest.failureReason != null) {
                    latchCriticalFromReadFailure(latest.failureReason)
                } else if (latest.state?.resumePending == true ||
                    latest.state?.effectiveMode == Mode.CRITICAL_RECOVERY_REQUIRED
                ) {
                    pauseAllWorkers()
                }
            }
        }
    }

    /** Writes are admitted only after persisted state and published state both prove NORMAL. */
    fun isWritesAllowed(): Boolean {
        if (!persistenceHealthy || _modeFlow.value != Mode.NORMAL) return false
        return currentMode() == Mode.NORMAL
    }

    /** Returns the effective persisted maintenance mode, failing closed on read errors. */
    fun currentMode(): Mode {
        if (!persistenceHealthy || _modeFlow.value == Mode.CRITICAL_RECOVERY_REQUIRED) {
            return Mode.CRITICAL_RECOVERY_REQUIRED
        }

        val read = readPersistedState()
        val failureReason = read.failureReason
        if (failureReason != null) {
            latchCriticalFromReadFailure(failureReason)
            return Mode.CRITICAL_RECOVERY_REQUIRED
        }

        val effectiveMode = read.state!!.effectiveMode
        if (effectiveMode == Mode.CRITICAL_RECOVERY_REQUIRED) {
            latchPersistedCritical()
            return effectiveMode
        }

        // Pending removal is committed before NORMAL is published. Preserve the
        // in-memory barrier during that final, bounded transition window.
        if (effectiveMode == Mode.NORMAL && _modeFlow.value != Mode.NORMAL) {
            return _modeFlow.value
        }
        return effectiveMode
    }

    /** Enters a maintenance mode, pausing workers and blocking writes. */
    fun enter(mode: Mode) {
        if (mode == Mode.NORMAL) {
            resumeWorkersToNormal()
            return
        }

        Timber.w("Maintenance mode: entering %s", mode.label)
        try {
            synchronized(stateLock) {
                writeMode(mode)
            }
            pauseAllWorkers()
        } catch (e: PersistenceException) {
            enterCriticalRecoveryRequired(MODE_PERSISTENCE_FAILURE)
            throw e
        }
        Timber.d("Maintenance mode: entered %s", mode.label)
    }

    /** Enters the absorbing fail-closed recovery state. */
    fun enterCriticalRecoveryRequired(reason: String) {
        Timber.e("Maintenance mode: entering CRITICAL_RECOVERY_REQUIRED — %s", reason)
        val committed = latchCritical(reason, persist = true)
        if (!committed) throw PersistenceException()
    }

    /**
     * Exits maintenance mode. A normal exit verifies all worker schedules before
     * publishing a writable state; a restart-required exit remains blocked.
     */
    fun exit(forceRestartRequired: Boolean = false) {
        if (forceRestartRequired) {
            Timber.w("Maintenance mode: exiting to %s", Mode.RESTORE_COMPLETE_RESTART_REQUIRED.label)
            try {
                synchronized(stateLock) {
                    writeMode(Mode.RESTORE_COMPLETE_RESTART_REQUIRED)
                }
            } catch (e: PersistenceException) {
                enterCriticalRecoveryRequired(MODE_PERSISTENCE_FAILURE)
                throw e
            }
            Timber.d("Maintenance mode: writes remain blocked until app restart")
            return
        }

        Timber.w("Maintenance mode: exiting to %s", Mode.NORMAL.label)
        resumeWorkersToNormal()
    }

    /** Coroutine-aware normal exit used by startup recovery. */
    suspend fun exitCancellable(forceRestartRequired: Boolean = false) {
        if (forceRestartRequired) {
            exit(forceRestartRequired = true)
            return
        }

        Timber.w("Maintenance mode: exiting to %s", Mode.NORMAL.label)
        resumeWorkersToNormalCancellable()
    }

    /** Resets a restart/pending state through the same checked worker-resume gate. */
    fun reset() {
        Timber.w("Maintenance mode: resetting to NORMAL")
        if (resetRequiresWorkerResume()) {
            resumeWorkersToNormal()
        }
    }

    /** Coroutine-aware reset used by startup recovery. */
    suspend fun resetCancellable() {
        Timber.w("Maintenance mode: resetting to NORMAL")
        if (resetRequiresWorkerResume()) {
            resumeWorkersToNormalCancellable()
        }
    }

    private fun resetRequiresWorkerResume(): Boolean {
        val read = readPersistedState()
        val failureReason = read.failureReason
        if (failureReason != null) {
            latchCriticalFromReadFailure(failureReason)
            throw PersistenceException()
        }

        val state = read.state!!
        if (state.effectiveMode == Mode.CRITICAL_RECOVERY_REQUIRED ||
            _modeFlow.value == Mode.CRITICAL_RECOVERY_REQUIRED
        ) {
            throw PersistenceException()
        }

        if (state.rawMode == Mode.NORMAL && !state.resumePending && _modeFlow.value == Mode.NORMAL) {
            return false
        }
        return true
    }

    private fun resumeWorkersToNormal() {
        val resumeGeneration = beginWorkerResume()

        try {
            val scheduleResult = scheduleAllWorkers()
            if (!scheduleResult.confirms(WorkerSpec.DEFAULTS.keys) ||
                !confirmDefaultWorkerSchedules()
            ) {
                throw WorkerRescheduleException()
            }

            finalizeWorkerResume(resumeGeneration)
            Timber.d("Maintenance mode: all workers rescheduled and NORMAL confirmed")
        } catch (e: CancellationException) {
            // NORMAL + pending remains durable, the stop flag remains set, and a
            // fresh process will retry this gate through reset().
            throw e
        } catch (e: Exception) {
            enterCriticalRecoveryRequired(
                if (e is PersistenceException) MODE_PERSISTENCE_FAILURE else WORKER_RESCHEDULE_FAILURE
            )
            if (e is PersistenceException) throw e
            throw WorkerRescheduleException()
        }
    }

    private suspend fun resumeWorkersToNormalCancellable() {
        val callerContext = currentCoroutineContext()
        val resumeGeneration = beginWorkerResume()

        try {
            callerContext.ensureActive()
            val scheduleResult = scheduleAllWorkers()
            callerContext.ensureActive()
            if (!scheduleResult.confirms(WorkerSpec.DEFAULTS.keys) ||
                !confirmDefaultWorkerSchedulesCancellable()
            ) {
                throw WorkerRescheduleException()
            }

            callerContext.ensureActive()
            finalizeWorkerResume(resumeGeneration) { callerContext.ensureActive() }
            Timber.d("Maintenance mode: all workers rescheduled and NORMAL confirmed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            enterCriticalRecoveryRequired(
                if (e is PersistenceException) MODE_PERSISTENCE_FAILURE else WORKER_RESCHEDULE_FAILURE
            )
            if (e is PersistenceException) throw e
            throw WorkerRescheduleException()
        }
    }

    private fun beginWorkerResume(): Long = try {
        synchronized(stateLock) {
            ensureCriticalIsNotCleared()
            commitNormalWithResumePending()
            workerPauseGeneration
        }
    } catch (e: PersistenceException) {
        enterCriticalRecoveryRequired(MODE_PERSISTENCE_FAILURE)
        throw e
    }

    private fun finalizeWorkerResume(
        resumeGeneration: Long,
        ensureCallerActive: () -> Unit = {}
    ) {
        synchronized(stateLock) {
            ensureCallerActive()
            if (workerPauseGeneration != resumeGeneration ||
                _modeFlow.value == Mode.CRITICAL_RECOVERY_REQUIRED ||
                !persistenceHealthy
            ) {
                throw PersistenceException()
            }

            val persisted = readPersistedState()
            val failureReason = persisted.failureReason
            if (failureReason != null) {
                latchCriticalFromReadFailure(failureReason)
                throw PersistenceException()
            }
            val state = persisted.state!!
            if (state.rawMode != Mode.NORMAL || !state.resumePending) {
                if (state.effectiveMode == Mode.CRITICAL_RECOVERY_REQUIRED) {
                    latchPersistedCritical()
                }
                throw PersistenceException()
            }

            clearResumePending()
            if (workerPauseGeneration != resumeGeneration ||
                _modeFlow.value == Mode.CRITICAL_RECOVERY_REQUIRED
            ) {
                throw PersistenceException()
            }
            workerLeaseRegistry.get().resetStopFlag()
            persistenceHealthy = true
            publishMode(Mode.NORMAL)
        }
    }

    /** Cancels every default worker by its unique work name. */
    private fun pauseAllWorkers(): Unit = synchronized(stateLock) {
        // Invalidates confirmations made by every mode owner, not only this instance.
        workerPauseGeneration++
        val workManager = try {
            WorkManager.getInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@synchronized
        }
        for (name in WorkerSpec.DEFAULTS.keys) {
            try {
                workManager.cancelUniqueWork(name)
                Timber.d("Cancelled unique worker: %s", name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Failed to cancel unique worker %s (%s)", name, e::class.java.simpleName)
            }
        }
    }

    private fun scheduleAllWorkers(): WorkerRegistry.ScheduleAllResult {
        return WorkerRegistry.scheduleAll(context.applicationContext, timeProvider)
    }

    private fun confirmDefaultWorkerSchedules(): Boolean {
        val workManager = try {
            WorkManager.getInstance(context.applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SCHEDULE_CONFIRM_TIMEOUT_MS)

        for (workerName in WorkerSpec.DEFAULTS.keys) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return false

            val workInfos = try {
                workManager.getWorkInfosForUniqueWork(workerName)
                    .get(remainingNanos, TimeUnit.NANOSECONDS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cancellation = e.cause as? CancellationException
                if (cancellation != null) throw cancellation
                if (e is InterruptedException) Thread.currentThread().interrupt()
                return false
            }

            if (workInfos.count { !it.state.isFinished } != 1) return false
        }
        return true
    }

    private suspend fun confirmDefaultWorkerSchedulesCancellable(): Boolean {
        val workManager = try {
            WorkManager.getInstance(context.applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }

        return withTimeoutOrNull(SCHEDULE_CONFIRM_TIMEOUT_MS) {
            for (workerName in WorkerSpec.DEFAULTS.keys) {
                val workInfos = try {
                    workManager.getWorkInfosForUniqueWork(workerName).awaitCancellable()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@withTimeoutOrNull false
                }

                if (workInfos.count { !it.state.isFinished } != 1) {
                    return@withTimeoutOrNull false
                }
            }
            true
        } ?: false
    }

    private suspend fun <T> ListenableFuture<T>.awaitCancellable(): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel(true) }
            addListener(
                {
                    try {
                        val result = get()
                        if (continuation.isActive) {
                            try {
                                continuation.resume(result)
                            } catch (_: IllegalStateException) {
                                // Cancellation won the completion race.
                            }
                        }
                    } catch (e: ExecutionException) {
                        if (continuation.isActive) {
                            try {
                                continuation.resumeWithException(e.cause ?: e)
                            } catch (_: IllegalStateException) {
                                // Cancellation won the completion race.
                            }
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) {
                            try {
                                continuation.resumeWithException(e)
                            } catch (_: IllegalStateException) {
                                // Cancellation won the completion race.
                            }
                        }
                    }
                },
                DIRECT_EXECUTOR
            )
        }

    private fun readPersistedState(): ReadResult = synchronized(stateLock) {
        readPersistedStateLocked()
    }

    private fun readPersistedStateLocked(): ReadResult {
        when (criticalSentinelExists()) {
            true -> return ReadResult(
                PersistedState(
                    rawMode = Mode.CRITICAL_RECOVERY_REQUIRED,
                    resumePending = false,
                    effectiveMode = Mode.CRITICAL_RECOVERY_REQUIRED
                )
            )
            null -> return ReadResult(failureReason = MODE_READ_FAILURE)
            false -> Unit
        }

        val preferences = prefs ?: return ReadResult(failureReason = MODE_PREFERENCES_UNAVAILABLE)
        return try {
            // SharedPreferences getters are individually thread-safe, but a sequence of
            // contains/get calls is not a coherent snapshot. Decode one copied map so a
            // concurrent pending-marker removal cannot be mistaken for corrupt state.
            val snapshot = preferences.all.toMap()
            val hasMode = snapshot.containsKey(KEY_MAINTENANCE_MODE)
            val hasPending = snapshot.containsKey(KEY_WORKER_RESUME_PENDING)
            val hasCriticalReason = snapshot.containsKey(KEY_CRITICAL_REASON)
            val hasCriticalTimestamp = snapshot.containsKey(KEY_CRITICAL_TIMESTAMP)
            val hasCriticalMetadata = hasCriticalReason || hasCriticalTimestamp

            if (!hasMode) {
                if (hasPending || hasCriticalMetadata) {
                    ReadResult(failureReason = MODE_STATE_INVALID)
                } else {
                    ReadResult(PersistedState(Mode.NORMAL, resumePending = false, effectiveMode = Mode.NORMAL))
                }
            } else {
                val storedName = snapshot[KEY_MAINTENANCE_MODE] as? String
                    ?: return ReadResult(failureReason = MODE_STATE_INVALID)
                if (storedName.isBlank()) return ReadResult(failureReason = MODE_STATE_INVALID)

                val rawMode = try {
                    Mode.valueOf(storedName)
                } catch (_: IllegalArgumentException) {
                    return ReadResult(failureReason = MODE_STATE_INVALID)
                }

                val resumePending = if (hasPending) {
                    if (snapshot[KEY_WORKER_RESUME_PENDING] != true) {
                        return ReadResult(failureReason = MODE_STATE_INVALID)
                    }
                    true
                } else {
                    false
                }

                when {
                    rawMode == Mode.CRITICAL_RECOVERY_REQUIRED -> ReadResult(
                        PersistedState(rawMode, resumePending, Mode.CRITICAL_RECOVERY_REQUIRED)
                    )

                    hasCriticalMetadata -> ReadResult(failureReason = MODE_STATE_INVALID)

                    resumePending && rawMode == Mode.NORMAL -> ReadResult(
                        PersistedState(rawMode, resumePending = true, Mode.RESTORE_COMPLETE_RESTART_REQUIRED)
                    )

                    resumePending -> ReadResult(failureReason = MODE_STATE_INVALID)
                    else -> ReadResult(PersistedState(rawMode, resumePending = false, rawMode))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ReadResult(failureReason = MODE_READ_FAILURE)
        }
    }

    private fun writeMode(mode: Mode, publish: Boolean = true) {
        ensureCriticalIsNotCleared(mode)
        val preferences = prefs ?: run {
            persistenceHealthy = false
            throw PersistenceException()
        }

        val committed = try {
            preferences.edit()
                .putString(KEY_MAINTENANCE_MODE, mode.name)
                .remove(KEY_WORKER_RESUME_PENDING)
                .commit()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!committed) {
            persistenceHealthy = false
            throw PersistenceException()
        }
        persistenceHealthy = true
        if (publish) publishMode(mode)
    }

    private fun commitNormalWithResumePending() {
        ensureCriticalIsNotCleared()
        val preferences = prefs ?: run {
            persistenceHealthy = false
            throw PersistenceException()
        }
        val committed = try {
            preferences.edit()
                .putString(KEY_MAINTENANCE_MODE, Mode.NORMAL.name)
                .putBoolean(KEY_WORKER_RESUME_PENDING, true)
                .commit()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!committed) {
            persistenceHealthy = false
            throw PersistenceException()
        }
    }

    private fun clearResumePending() {
        val preferences = prefs ?: throw PersistenceException()
        val committed = try {
            preferences.edit().remove(KEY_WORKER_RESUME_PENDING).commit()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!committed) {
            persistenceHealthy = false
            throw PersistenceException()
        }
    }

    private fun ensureCriticalIsNotCleared(targetMode: Mode = Mode.NORMAL) {
        if (targetMode == Mode.CRITICAL_RECOVERY_REQUIRED) return
        if (_modeFlow.value == Mode.CRITICAL_RECOVERY_REQUIRED) throw PersistenceException()

        val read = readPersistedState()
        val failureReason = read.failureReason
        if (failureReason != null) {
            latchCriticalFromReadFailure(failureReason)
            throw PersistenceException()
        }
        if (read.state!!.effectiveMode == Mode.CRITICAL_RECOVERY_REQUIRED) {
            latchPersistedCritical()
            throw PersistenceException()
        }
    }

    private fun persistCriticalState(reason: String): Boolean {
        // The alternate marker keeps reconstruction blocked but must not hide a
        // failed preference commit from the caller's typed persistence contract.
        persistCriticalSentinel()
        val preferencesPersisted = prefs?.let { preferences ->
            try {
                preferences.edit()
                    .putString(KEY_CRITICAL_REASON, reason)
                    .putLong(KEY_CRITICAL_TIMESTAMP, timeProvider.now())
                    .putString(KEY_MAINTENANCE_MODE, Mode.CRITICAL_RECOVERY_REQUIRED.name)
                    .remove(KEY_WORKER_RESUME_PENDING)
                    .commit()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        } ?: false
        return preferencesPersisted
    }

    private fun latchCriticalFromReadFailure(reason: String) {
        latchCritical(reason, persist = true)
    }

    private fun latchPersistedCritical() {
        latchCritical(MODE_STATE_INVALID, persist = false)
    }

    private fun latchCritical(reason: String, persist: Boolean): Boolean {
        val committed = synchronized(stateLock) {
            persistenceHealthy = false
            publishCriticalInMemory(reason)
            val durable = !persist || persistCriticalState(reason)
            persistenceHealthy = durable
            durable
        }
        pauseAllWorkers()
        return committed
    }

    private fun criticalSentinelExists(): Boolean? = try {
        File(context.noBackupFilesDir, CRITICAL_SENTINEL_FILE).exists()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun persistCriticalSentinel(): Boolean = try {
        val sentinel = File(context.noBackupFilesDir, CRITICAL_SENTINEL_FILE)
        sentinel.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) throw PersistenceException()
        }
        // A previous open/write/sync failure can leave a visible, non-durable file.
        // Every attempt must write and sync, including attempts on an existing file.
        beforeCriticalSentinelIo?.invoke(CriticalSentinelIoStage.OPEN)
        FileOutputStream(sentinel, false).use { output ->
            beforeCriticalSentinelIo?.invoke(CriticalSentinelIoStage.WRITE)
            output.write(CRITICAL_SENTINEL_CONTENT)
            beforeCriticalSentinelIo?.invoke(CriticalSentinelIoStage.SYNC)
            output.fd.sync()
        }
        sentinel.exists()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun publishMode(mode: Mode) {
        _modeFlow.value = mode
        _operationalStateFlow.value = toOperationalState(mode)
    }

    private fun publishCriticalInMemory(reason: String) {
        _modeFlow.value = Mode.CRITICAL_RECOVERY_REQUIRED
        _operationalStateFlow.value = AppOperationalState.CriticalRecoveryRequired(reason, null)
    }

    private fun toOperationalState(mode: Mode): AppOperationalState = when (mode) {
        Mode.NORMAL -> AppOperationalState.Normal
        Mode.BACKUP_EXPORTING -> AppOperationalState.BackupExporting
        Mode.RESTORE_COMPLETE_RESTART_REQUIRED -> AppOperationalState.RestartRequiredAfterRestore
        Mode.CRITICAL_RECOVERY_REQUIRED -> AppOperationalState.CriticalRecoveryRequired(
            reason = CancellationSafe.runCatchingCancellable { prefs?.getString(KEY_CRITICAL_REASON, null) }.getOrNull(),
            timestamp = CancellationSafe.runCatchingCancellable { prefs?.getLong(KEY_CRITICAL_TIMESTAMP, 0L) }
                .getOrNull()
                ?.takeIf { it > 0L }
        )

        else -> AppOperationalState.RestoreInProgress(mode)
    }

    companion object {
        private val PERSISTED_STATE_LOCK = Any()
        private var workerPauseGeneration = 0L
        private const val PREFS_NAME = "restore_maintenance_mode"
        private const val KEY_MAINTENANCE_MODE = "current_mode"
        private const val KEY_WORKER_RESUME_PENDING = "worker_resume_pending"
        private const val KEY_CRITICAL_REASON = "critical_recovery_reason"
        private const val KEY_CRITICAL_TIMESTAMP = "critical_recovery_timestamp"
        private const val CRITICAL_SENTINEL_FILE = "restore_maintenance_critical"
        private val CRITICAL_SENTINEL_CONTENT = byteArrayOf(1)
        private const val MODE_PERSISTENCE_FAILURE = "RESTORE_MAINTENANCE_MODE_PERSISTENCE_FAILED"
        private const val MODE_PREFERENCES_UNAVAILABLE = "RESTORE_MAINTENANCE_MODE_PREFERENCES_UNAVAILABLE"
        private const val MODE_READ_FAILURE = "RESTORE_MAINTENANCE_MODE_READ_FAILED"
        private const val MODE_STATE_INVALID = "RESTORE_MAINTENANCE_MODE_STATE_INVALID"
        private const val WORKER_RESCHEDULE_FAILURE = "RESTORE_WORKER_RESCHEDULE_FAILED"
        private const val SCHEDULE_CONFIRM_TIMEOUT_MS = 10_000L
        private val DIRECT_EXECUTOR = java.util.concurrent.Executor { command -> command.run() }
    }
}
