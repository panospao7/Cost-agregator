package com.yourname.expensetracker.ui.unsupported

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.DatabaseOpenGate
import com.yourname.expensetracker.domain.privacy.applock.AuthLaunch
import com.yourname.expensetracker.domain.privacy.applock.AuthPromptCopy
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCallback
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthenticator
import com.yourname.expensetracker.ui.applock.BiometricPromptDeviceAuthenticator
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R25 slice B2: the only screen shown while the database open gate blocks the live database file
 * (too old, too new or unreadable).
 *
 * This is deliberately not a Hilt activity: no generated injection base, no Room, nothing that could
 * open or migrate the blocked file. It is a [FragmentActivity] only because the system authentication
 * prompt needs one. The one optional action is a user-started export of the untouched database file
 * (plus its -wal companion, never -shm) through the system document picker. The files are copied
 * verbatim with plain file streams and are never opened with SQLite.
 *
 * App lock (S21-c, #10): when the committed lock is on, or the lock store cannot be read, and the
 * device can authenticate (or the capability check failed), the export first needs device
 * authentication, before the document picker opens. The lock and the capability come from the one
 * narrow read-only entry point in `UnsupportedDatabaseExportAuth.kt`, read at the tap and bounded at 2 s.
 * A cancelled or failed prompt exports nothing and shows one constant message; no diagnostic is kept.
 */
class UnsupportedDatabaseActivity : FragmentActivity() {

    private enum class ExportState { IDLE, CHECKING, AUTHENTICATING, RUNNING, SUCCESS, FAILED, AUTH_DENIED }

    private var exportState by mutableStateOf(ExportState.IDLE)

    /** Per Activity instance, created in [onCreate] (prompt library guidance). Null only when the gate does not block. */
    private var exportAuthenticator: DeviceAuthenticator? = null
    private var exportAuthAttemptId: Long = 0L

    /** The in-flight gate read of one tap (F6-i); cancelled by [onStop]. */
    private var exportGateJob: Job? = null

    /** Generation token of the gate read (like [exportAuthAttemptId]): a stale gate job is a no-op (F6-i). */
    private var exportGateGeneration = 0L

    /** The prompt watchdog of the open attempt (F6-ii); null when none is armed. */
    private var exportAuthWatchdog: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reasonCode = DatabaseOpenGate.blockedReasonOrNull(applicationContext)
        if (reasonCode == null) {
            // Fail closed: this export-only screen must never be reachable (e.g. from recents or a
            // restored task) while the gate does not block the live database.
            finish()
            return
        }
        exportAuthenticator = authenticatorFactoryForTest?.invoke(this) ?: BiometricPromptDeviceAuthenticator(this)
        setContent {
            ExpenseTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UnsupportedDatabaseContent(reasonCode)
                }
            }
        }
    }

    @Composable
    private fun UnsupportedDatabaseContent(reasonCode: String?) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE)
        ) { uri ->
            if (uri != null) startExport(uri)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.unsupported_db_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(reasonTextRes(reasonCode)),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onExportRequested { launcher.launch(EXPORT_SUGGESTED_NAME) } },
                enabled = canStartExport(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.unsupported_db_export_button))
            }
            when (exportState) {
                ExportState.SUCCESS -> StatusText(R.string.unsupported_db_export_success)
                ExportState.FAILED -> StatusText(R.string.unsupported_db_export_failed)
                ExportState.AUTH_DENIED -> StatusText(R.string.unsupported_db_export_auth_denied)
                else -> Unit
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { finishAffinity() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.unsupported_db_close_button))
            }
        }
    }

    @Composable
    private fun StatusText(@StringRes textRes: Int) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium
        )
    }

    /** False while a gate read, a prompt or an export is in flight, so a second tap cannot start another. */
    private fun canStartExport(): Boolean = when (exportState) {
        ExportState.CHECKING,
        ExportState.AUTHENTICATING,
        ExportState.RUNNING -> false
        ExportState.IDLE,
        ExportState.SUCCESS,
        ExportState.FAILED,
        ExportState.AUTH_DENIED -> true
    }

    /**
     * The export button (S21-c). Decides at the tap, from a fresh bounded read, whether device
     * authentication must come first; [launchPicker] (the system document picker) runs only when it is
     * not required or the user authenticated. Nothing is written to any document before that.
     */
    private fun onExportRequested(launchPicker: () -> Unit) {
        if (!canStartExport()) return
        exportState = ExportState.CHECKING
        val gen = ++exportGateGeneration
        exportGateJob = lifecycleScope.launch {
            try {
                val testGate = exportAuthRequiredForTest
                val authRequired = if (testGate != null) {
                    testGate(applicationContext)
                } else {
                    UnsupportedDatabaseExportAuth.isAuthRequired(applicationContext)
                }
                // F6-i: the read is bounded but suspends; the screen may have been stopped or be finishing
                // meanwhile. Then neither a prompt nor the picker may start; the state falls back to IDLE below.
                ensureActive()
                // A newer tap or an onStop superseded this read: it is a no-op and must not touch the state.
                if (gen != exportGateGeneration) return@launch
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || isFinishing) {
                    exportState = ExportState.IDLE
                } else if (authRequired) {
                    beginExportAuthentication(launchPicker)
                } else {
                    exportState = ExportState.IDLE
                    launchPicker()
                }
            } finally {
                // A cancelled (or failed) gate read must not leave the button disabled. This only resets the
                // state; it never opens the picker. A superseded (stale) job leaves the newer tap's state alone.
                if (gen == exportGateGeneration && exportState == ExportState.CHECKING) {
                    exportState = ExportState.IDLE
                    exportGateJob = null
                }
            }
        }
    }

    override fun onStop() {
        // F6-i: a gate read still in flight is abandoned. Its finally block also resets CHECKING; the reset
        // here makes that immediate, and it never opens the picker.
        exportGateGeneration++
        exportGateJob?.cancel()
        exportGateJob = null
        if (exportState == ExportState.CHECKING) exportState = ExportState.IDLE
        // F6-ii: the stop ends the attempt itself, so the watchdog has nothing left to do.
        disarmExportAuthWatchdog()
        // The port contract: the caller closes an attempt it no longer waits for. cancel() delivers the
        // outcome synchronously, which ends the attempt as AUTH_DENIED with no picker.
        if (exportState == ExportState.AUTHENTICATING) {
            exportAuthenticator?.cancel(exportAuthAttemptId)
            // Fail closed if no outcome was delivered (a stale id or an adapter that already closed it).
            if (exportState == ExportState.AUTHENTICATING) exportState = ExportState.AUTH_DENIED
        }
        super.onStop()
    }

    private fun beginExportAuthentication(launchPicker: () -> Unit) {
        val attemptId = ++exportAuthAttemptId
        // Recorded before the launch: the prompt can report back before authenticate returns.
        exportState = ExportState.AUTHENTICATING
        val launch = exportAuthenticator?.authenticate(
            attemptId,
            AuthPromptCopy.EXPORT,
            DeviceAuthCallback { id, outcome -> onExportAuthOutcome(id, outcome, launchPicker) }
        ) ?: AuthLaunch.PROMPT_UNAVAILABLE
        if (launch != AuthLaunch.LAUNCHED && exportState == ExportState.AUTHENTICATING) {
            // Nothing was launched (below API 30, host unavailable, prompt could not be built): fail closed.
            exportState = ExportState.AUTH_DENIED
        } else if (exportState == ExportState.AUTHENTICATING) {
            // The prompt is open and no outcome has come yet: bound how long it may stay open (F6-ii).
            armExportAuthWatchdog(attemptId)
        }
    }

    /**
     * F6-ii: after [EXPORT_AUTH_PROMPT_TIMEOUT_MILLIS] without an outcome the screen closes the prompt itself
     * and ends the attempt as denied. It never opens the picker and keeps no diagnostic.
     */
    private fun armExportAuthWatchdog(attemptId: Long) {
        disarmExportAuthWatchdog()
        val delayMillis = exportAuthPromptTimeoutMillisForTest ?: EXPORT_AUTH_PROMPT_TIMEOUT_MILLIS
        exportAuthWatchdog = lifecycleScope.launch {
            delay(delayMillis)
            onExportAuthWatchdogFired(attemptId)
        }
    }

    private fun disarmExportAuthWatchdog() {
        exportAuthWatchdog?.cancel()
        exportAuthWatchdog = null
    }

    private fun onExportAuthWatchdogFired(attemptId: Long) {
        // The watchdog job is finishing; drop the reference first so the outcome below does not cancel it.
        exportAuthWatchdog = null
        // Only the attempt it was armed for, and only while that attempt is still open.
        if (attemptId != exportAuthAttemptId || exportState != ExportState.AUTHENTICATING) return
        // cancel() delivers SYSTEM_CANCELED synchronously, which ends the attempt as AUTH_DENIED with no picker.
        exportAuthenticator?.cancel(attemptId)
        // Fail closed if no outcome was delivered (a stale id or an adapter that already closed it).
        if (exportState == ExportState.AUTHENTICATING) exportState = ExportState.AUTH_DENIED
    }

    private fun onExportAuthOutcome(attemptId: Long, outcome: DeviceAuthOutcome, launchPicker: () -> Unit) {
        // A late or repeated callback of an earlier attempt can never start an export.
        if (attemptId != exportAuthAttemptId || exportState != ExportState.AUTHENTICATING) return
        // Any outcome of the open attempt ends it, so the watchdog is disarmed.
        disarmExportAuthWatchdog()
        if (outcome == DeviceAuthOutcome.Succeeded && !isFinishing && !isDestroyed) {
            exportState = ExportState.IDLE
            launchPicker()
        } else {
            // Cancelled, locked out, no credential, failed or unknown: no export, one constant message.
            exportState = ExportState.AUTH_DENIED
        }
    }

    private fun startExport(uri: Uri) {
        exportState = ExportState.RUNNING
        lifecycleScope.launch {
            Log.i(LOG_TAG, CODE_EXPORT_STARTED)
            val succeeded = try {
                withContext(Dispatchers.IO) { writeBackupTo(uri) }
                Log.i(LOG_TAG, CODE_EXPORT_COMPLETED)
                true
            } catch (e: CancellationException) {
                deletePartialOutput(uri)
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, CODE_EXPORT_FAILED + " " + e.javaClass.simpleName)
                deletePartialOutput(uri)
                false
            }
            exportState = if (succeeded) ExportState.SUCCESS else ExportState.FAILED
        }
    }

    /**
     * Best-effort removal of the partial/empty document left by a failed or cancelled export. Runs
     * non-cancellable so a cancelled scope still cleans up; a delete failure is only logged.
     */
    internal suspend fun deletePartialOutput(
        uri: Uri,
        delete: (Uri) -> Boolean = { DocumentsContract.deleteDocument(contentResolver, it) }
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                if (!delete(uri)) Log.w(LOG_TAG, CODE_CLEANUP_FAILED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, CODE_CLEANUP_FAILED + " " + e.javaClass.simpleName)
            }
        }
    }

    /** Blocking; call from an IO dispatcher. Throws on any failure so the caller reports it. */
    internal fun writeBackupTo(uri: Uri) {
        // Defence in depth: never export unless the gate currently blocks the live database.
        check(DatabaseOpenGate.blockedReasonOrNull(applicationContext) != null) {
            "export not permitted"
        }
        val sources = DatabaseOpenGate.liveDatabaseFiles(applicationContext)
        val output = contentResolver.openOutputStream(uri)
            ?: throw IOException("output stream unavailable")
        output.use { writeBackupZip(it, sources) }
    }

    companion object {
        internal const val LOG_TAG = "UnsupportedDatabase"
        internal const val CODE_EXPORT_STARTED = "UNSUPPORTED_DB_EXPORT_STARTED"
        internal const val CODE_EXPORT_COMPLETED = "UNSUPPORTED_DB_EXPORT_COMPLETED"
        internal const val CODE_EXPORT_FAILED = "UNSUPPORTED_DB_EXPORT_FAILED"
        internal const val CODE_CLEANUP_FAILED = "UNSUPPORTED_DB_EXPORT_CLEANUP_FAILED"

        /**
         * Test seam: replaces the export auth gate read (`UnsupportedDatabaseExportAuth.isAuthRequired`) when
         * non-null. Always null in production; a test sets it and resets it to null afterwards.
         */
        @VisibleForTesting
        internal var exportAuthRequiredForTest: (suspend (Context) -> Boolean)? = null

        /**
         * Test seam: replaces the system-prompt authenticator built in `onCreate` when non-null. Always null
         * in production; a test sets it and resets it to null afterwards.
         */
        @VisibleForTesting
        internal var authenticatorFactoryForTest: ((FragmentActivity) -> DeviceAuthenticator)? = null

        /**
         * Test seam: replaces [EXPORT_AUTH_PROMPT_TIMEOUT_MILLIS] as the prompt watchdog delay when non-null.
         * Always null in production; a test sets it and resets it to null afterwards.
         */
        @VisibleForTesting
        internal var exportAuthPromptTimeoutMillisForTest: Long? = null

        private const val EXPORT_MIME_TYPE = "application/zip"
        private const val EXPORT_SUGGESTED_NAME = "cost-aggregator-database-backup.zip"
        private const val SHM_SUFFIX = "-shm"

        @StringRes
        internal fun reasonTextRes(reasonCode: String?): Int = when (reasonCode) {
            DatabaseOpenGate.REASON_SCHEMA_TOO_OLD -> R.string.unsupported_db_reason_too_old
            DatabaseOpenGate.REASON_SCHEMA_TOO_NEW -> R.string.unsupported_db_reason_too_new
            else -> R.string.unsupported_db_reason_unreadable
        }

        /**
         * Writes each existing source file verbatim as a zip entry named after the file. The -shm
         * companion is never included. Sources are read with plain [FileInputStream]s only.
         */
        internal fun writeBackupZip(output: OutputStream, sources: List<File>) {
            // The first source is the main database file; a zip without it is not a backup.
            if (sources.firstOrNull()?.isFile != true) throw IOException("main database file missing")
            val zip = ZipOutputStream(output)
            for (file in sources) {
                if (file.name.endsWith(SHM_SUFFIX) || !file.isFile) continue
                zip.putNextEntry(ZipEntry(file.name))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
        }
    }
}
