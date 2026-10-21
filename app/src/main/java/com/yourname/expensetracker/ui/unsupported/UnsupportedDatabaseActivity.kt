package com.yourname.expensetracker.ui.unsupported

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.lifecycle.lifecycleScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.DatabaseOpenGate
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R25 slice B2: the only screen shown while the database open gate blocks the live database file
 * (too old, too new or unreadable).
 *
 * This is deliberately a plain [ComponentActivity]: no dependency injection, no Room, nothing that
 * could open or migrate the blocked file. The one optional action is a user-started export of the
 * untouched database file (plus its -wal companion, never -shm) through the system document picker.
 * The files are copied verbatim with plain file streams and are never opened with SQLite.
 */
class UnsupportedDatabaseActivity : ComponentActivity() {

    private enum class ExportState { IDLE, RUNNING, SUCCESS, FAILED }

    private var exportState by mutableStateOf(ExportState.IDLE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reasonCode = DatabaseOpenGate.blockedReasonOrNull(applicationContext)
        if (reasonCode == null) {
            // Fail closed: this export-only screen must never be reachable (e.g. from recents or a
            // restored task) while the gate does not block the live database.
            finish()
            return
        }
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
                onClick = { launcher.launch(EXPORT_SUGGESTED_NAME) },
                enabled = exportState != ExportState.RUNNING,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.unsupported_db_export_button))
            }
            when (exportState) {
                ExportState.SUCCESS -> StatusText(R.string.unsupported_db_export_success)
                ExportState.FAILED -> StatusText(R.string.unsupported_db_export_failed)
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
