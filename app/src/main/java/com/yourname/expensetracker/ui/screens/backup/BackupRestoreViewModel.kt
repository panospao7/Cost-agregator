package com.yourname.expensetracker.ui.screens.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.backup.CostbackupBundle
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.RestoreRestartSignal
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseImportResult
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.yourname.expensetracker.domain.util.CancellationSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * UI state for the Backup & Restore screen.
 *
 * RP-15 (15-D): privacy denial / fail-closed / SecurityException outcomes
 * converge on [privacyBlocked] (typed, resource-backed) instead of message
 * matching on repository failure strings.
 */
data class BackupRestoreUiState(
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false,
    val lastBackupDate: String? = null,
    val backupInfoUnavailable: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val restartRequired: Boolean = false,
    /** RG01-N1: number of receipt images skipped during restore (count only, never paths/names). */
    val receiptAssetWarningCount: Int = 0,
    /** RP-15 (15-D): typed privacy-denied state — rendered via PrivacyBlockedCard. */
    val privacyBlocked: com.yourname.expensetracker.ui.components.PrivacyBlockedUiState? = null
)

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseBackupRepository: DatabaseBackupRepository,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val timeProvider: TimeProvider,
    private val restoreRestartSignal: RestoreRestartSignal
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

    init {
        loadLastBackupInfo()
    }

    /**
     * G-TIME-01: the screen's single TimeProvider-backed "now" source
     * (same exposure pattern as ReviewViewModel.referenceNowMillis).
     */
    fun referenceNowMillis(): Long = timeProvider.now()

    /**
     * Loads last backup timestamp from the maintenance mode prefs or database stats.
     */
    private fun loadLastBackupInfo() {
        viewModelScope.launch {
            try {
                val stats = databaseBackupRepository.getDatabaseStats()
                _uiState.value = _uiState.value.copy(backupInfoUnavailable = false)
                val lastBackup = stats.lastBackupDate
                if (lastBackup != null && lastBackup > 0L) {
                    val formatted = java.time.format.DateTimeFormatter.ofPattern(
                        "yyyy-MM-dd HH:mm",
                        java.util.Locale.getDefault()
                    ).format(java.time.Instant.ofEpochMilli(lastBackup).atZone(java.time.ZoneId.systemDefault()))
                    _uiState.value = _uiState.value.copy(lastBackupDate = formatted)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w("BACKUP_INFO_UNAVAILABLE class=%s", e.javaClass.simpleName)
                _uiState.value = _uiState.value.copy(lastBackupDate = null, backupInfoUnavailable = true)
            }
        }
    }

    /**
     * RP-03A (P7-003): Creates a .costbackup bundle streamed to the SAF document
     * at [destination] (picked by the user via CreateDocument). The repository
     * owns opening/writing/closing the destination stream; this method only
     * maps typed failures to bounded messages — raw exception text is never
     * surfaced.
     */
    fun createBackup(destination: Uri, password: String) {
        if (password.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Password cannot be empty")
            return
        }
        // S3-016: Prevent duplicate operations
        if (_uiState.value.isBackingUp || _uiState.value.isRestoring) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isBackingUp = true,
                errorMessage = null,
                successMessage = null,
                privacyBlocked = null
            )

            // R26: an unexpected repository throw must never leave isBackingUp stuck.
            // Cancellation resets the flag and is rethrown; any other exception maps
            // to the bounded generic failure message below (no raw exception text).
            val result = try {
                databaseBackupRepository.createCostBackup(destination, password)
            } catch (e: CancellationException) {
                _uiState.value = _uiState.value.copy(isBackingUp = false)
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            result.fold(
                onSuccess = {
                    Timber.d("Backup created successfully")
                    _uiState.value = _uiState.value.copy(
                        isBackingUp = false,
                        successMessage = "Backup created successfully",
                        lastBackupDate = java.time.format.DateTimeFormatter.ofPattern(
                            "yyyy-MM-dd HH:mm",
                            java.util.Locale.getDefault()
                        ).format(
                            // G-TIME-01: derive the display timestamp from the injected TimeProvider.
                            java.time.Instant.ofEpochMilli(timeProvider.now()).atZone(java.time.ZoneId.systemDefault())
                        )
                    )
                },
                onFailure = { error ->
                    Timber.e("BACKUP_CREATE_FAILED class=%s", error.javaClass.simpleName)
                    // RP-15 (15-D): typed privacy denial — no message matching.
                    val denial = denialStateOrNull(error)
                    val message = when {
                        error is com.yourname.expensetracker.data.backup.CostbackupBundle.WrongBackupPasswordException ->
                            "Encryption error: Incorrect password or corrupt backup file"
                        // RP-03A: typed SAF destination failure (open/write/close) — bounded text only
                        error is com.yourname.expensetracker.domain.backup.BackupDestinationException ->
                            "Could not save the backup to the selected location. Please try again."
                        denial != null -> null
                        else -> "Backup failed. Please try again."
                    }
                    _uiState.value = _uiState.value.copy(
                        isBackingUp = false,
                        errorMessage = message,
                        privacyBlocked = denial
                    )
                }
            )
        }
    }

    /**
     * Restores a .costbackup bundle from the given URI.
     *
     * P7-CURRENT-017: Before copying the (potentially huge) URI to a temp file we
     * preflight it: reject files larger than [MAX_BACKUP_BUNDLE_BYTES] using the
     * ContentResolver size metadata when available, and validate the COSTBACKUP
     * header magic from the first [CostbackupBundle.HEADER_SIZE] bytes. The copy
     * itself is then size-bounded so a provider that lies about (or omits) its
     * size cannot fill the cache. Only after a clean copy do we call
     * [DatabaseBackupRepository.restoreCostBackup].
     */
    fun restoreBackup(uri: Uri, password: String) {
        if (password.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Password cannot be empty")
            return
        }
        // S3-016: Prevent duplicate operations
        if (_uiState.value.isRestoring || _uiState.value.isBackingUp) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isRestoring = true,
                errorMessage = null,
                successMessage = null,
                restartRequired = false,
                receiptAssetWarningCount = 0,
                privacyBlocked = null
            )

            // R26: queryUriSize/createTempFile are inside the guarded region so an
            // unexpected throw can never leave isRestoring stuck. Cancellation resets
            // the flag and is rethrown; other failures use bounded messages only.
            var tempFile: File? = null
            // S3-006: try/finally ensures isRestoring resets and temp file is deleted even on throw
            val result = try {
                // P7-CURRENT-017: cheap size precheck from provider metadata before opening a stream.
                val declaredSize = queryUriSize(uri)
                if (declaredSize != null && declaredSize > MAX_BACKUP_BUNDLE_BYTES) {
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        errorMessage = "Backup file is too large (max ${MAX_BACKUP_BUNDLE_BYTES / (1024 * 1024)} MB)"
                    )
                    return@launch
                }

                val stagedFile = File.createTempFile("restore_", ".costbackup", context.cacheDir)
                tempFile = stagedFile
                val copyResult = CancellationSafe.runCatchingCancellable {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open selected backup file")
                    input.use { src -> copyBackupWithPreflight(src, stagedFile) }
                }
                copyResult.getOrElse { error ->
                    // RP-15 (15-D): a SecurityException on the SAF stream is a security
                    // denial — converge on the typed state instead of a raw message.
                    if (error is SecurityException) {
                        _uiState.value = _uiState.value.copy(
                            isRestoring = false,
                            errorMessage = null,
                            privacyBlocked = com.yourname.expensetracker.ui.components.PrivacyBlockedUiState(
                                capability = com.yourname.expensetracker.domain.privacy.PrivacyCapability.ENCRYPTED_BACKUP,
                                messageResId = com.yourname.expensetracker.R.string.privacy_blocked_generic,
                                reasonCode = com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE
                            )
                        )
                        return@launch
                    }
                    val message = when (error) {
                        is CostbackupBundle.InvalidBackupFormatException ->
                            "Not a valid .costbackup file"
                        is CostbackupBundle.UnsupportedBackupVersionException ->
                            "Unsupported backup version"
                        is CostbackupBundle.BackupTooLargeException ->
                            "Backup file is too large (max ${MAX_BACKUP_BUNDLE_BYTES / (1024 * 1024)} MB)"
                        else -> "Failed to read backup file"
                    }
                    _uiState.value = _uiState.value.copy(isRestoring = false, errorMessage = message)
                    return@launch
                }

                databaseBackupRepository.restoreCostBackup(stagedFile, password)
            } catch (e: CancellationException) {
                _uiState.value = _uiState.value.copy(isRestoring = false)
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                tempFile?.delete()
            }

            result.fold(
                onSuccess = { importResult ->
                    Timber.d("Restore completed: %s", importResult)
                    when (importResult) {
                        is DatabaseImportResult.SuccessNeedsRestart -> {
                            _uiState.value = _uiState.value.copy(
                                isRestoring = false,
                                successMessage = "Restore completed successfully!",
                                restartRequired = true,
                                // RG01-N1: count only; warning strings are never surfaced.
                                receiptAssetWarningCount = importResult.summary.receiptAssetWarnings.size
                            )
                            // R27-S3: in-memory restart request, set ONLY from this in-process
                            // result (never from the persisted mode -> no restart loop). The
                            // restart-required lock screen in MainActivity consumes it, so the
                            // relaunch does not depend on this screen still being composed.
                            // RestoreMaintenanceMode persists the restart-required mode with
                            // SharedPreferences.commit() (synchronous) before the repository
                            // returns, so the relaunched process reads it back.
                            restoreRestartSignal.markRequested()
                        }
                        is DatabaseImportResult.Success ->
                            _uiState.value = _uiState.value.copy(
                                isRestoring = false,
                                successMessage = "Restore completed successfully!",
                                receiptAssetWarningCount = importResult.summary.receiptAssetWarnings.size
                            )
                        is DatabaseImportResult.Loading ->
                            _uiState.value = _uiState.value.copy(isRestoring = false)
                        is DatabaseImportResult.Error ->
                            _uiState.value = _uiState.value.copy(isRestoring = false, errorMessage = importResult.message)
                    }
                },
                onFailure = { error ->
                    Timber.e("RESTORE_FAILED class=%s", error.javaClass.simpleName)
                    // RP-15 (15-D): typed privacy denial — no message matching.
                    val denial = denialStateOrNull(error)
                    val message = when {
                        error is com.yourname.expensetracker.data.backup.CostbackupBundle.WrongBackupPasswordException ->
                            "Incorrect password or corrupt backup file"
                        // P7-CURRENT-023: zip-bomb / oversized-bundle rejection from the
                        // extract phase (the preflight copy maps this separately above).
                        error is com.yourname.expensetracker.data.backup.CostbackupBundle.BackupTooLargeException ->
                            "Backup file is too large or contains too many entries"
                        error.message?.contains("password", ignoreCase = true) == true ->
                            "Incorrect password or corrupt backup file"
                        denial != null -> null
                        else -> "Restore failed. Please try again."
                    }
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        errorMessage = message,
                        privacyBlocked = denial
                    )
                }
            )
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, privacyBlocked = null)
    }

    /**
     * RP-15 (15-D): maps a typed [com.yourname.expensetracker.domain.privacy.PrivacyDeniedException]
     * to the resource-backed blocked state. Returns null for any other failure —
     * those keep their bounded messages. The denial reason code travels in its
     * explicit bounded property, not Throwable.message.
     */
    @VisibleForTesting
    internal fun denialStateOrNull(error: Throwable): com.yourname.expensetracker.ui.components.PrivacyBlockedUiState? {
        if (error !is com.yourname.expensetracker.domain.privacy.PrivacyDeniedException) return null
        return com.yourname.expensetracker.ui.components.PrivacyBlockedUiState(
            capability = error.capability,
            messageResId = when (error.capability) {
                com.yourname.expensetracker.domain.privacy.PrivacyCapability.ENCRYPTED_BACKUP ->
                    com.yourname.expensetracker.R.string.privacy_blocked_encrypted_backup
                com.yourname.expensetracker.domain.privacy.PrivacyCapability.RAWBACKUP_EXPORT,
                com.yourname.expensetracker.domain.privacy.PrivacyCapability.RAW_DATABASE_EXPORT ->
                    com.yourname.expensetracker.R.string.privacy_blocked_raw_export
                else -> com.yourname.expensetracker.R.string.privacy_blocked_generic
            },
            reasonCode = error.reasonCode
        )
    }

    /**
     * Clears the current success message.
     */
    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    /**
     * P7-CURRENT-017: Returns the provider-declared size of [uri] in bytes via
     * [OpenableColumns.SIZE], or null when the provider does not report a size.
     * Used as a cheap pre-copy reject; the bounded copy is the authoritative guard.
     */
    private fun queryUriSize(uri: Uri): Long? {
        return try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return null
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query backup URI size")
            null
        }
    }

    /**
     * P7-CURRENT-017: Validates the COSTBACKUP header from the first
     * [CostbackupBundle.HEADER_SIZE] bytes BEFORE copying the body, then streams
     * the rest into [dest] with a hard [MAX_BACKUP_BUNDLE_BYTES] cap so a provider
     * that under-reports (or omits) its size cannot fill the cache.
     *
     * @throws CostbackupBundle.InvalidBackupFormatException bad/short magic
     * @throws CostbackupBundle.UnsupportedBackupVersionException unknown format version
     * @throws CostbackupBundle.BackupTooLargeException copy exceeds the size cap
     */
    @VisibleForTesting
    internal fun copyBackupWithPreflight(source: InputStream, dest: File) {
        copyBackupWithPreflight(source, dest, MAX_BACKUP_BUNDLE_BYTES)
    }

    /** Size-cap-injectable variant for tests (see [copyBackupWithPreflight]). */
    @VisibleForTesting
    internal fun copyBackupWithPreflight(source: InputStream, dest: File, maxBytes: Long) {
        // 1. Read + validate the fixed-size header (magic + format version).
        val header = ByteArray(CostbackupBundle.HEADER_SIZE)
        var off = 0
        while (off < header.size) {
            val n = source.read(header, off, header.size - off)
            if (n == -1) {
                throw CostbackupBundle.InvalidBackupFormatException("File too short: missing header")
            }
            off += n
        }
        CostbackupBundle.readHeader(header) // throws on bad magic / unsupported version

        // 2. Write header + bounded body to the temp file.
        dest.outputStream().use { out ->
            out.write(header)
            var total = header.size.toLong()
            val buf = ByteArray(8192)
            while (true) {
                val n = source.read(buf)
                if (n == -1) break
                total += n
                if (total > maxBytes) {
                    throw CostbackupBundle.BackupTooLargeException(
                        "Backup exceeds max size ($maxBytes bytes)"
                    )
                }
                out.write(buf, 0, n)
            }
        }
    }

    companion object {
        /**
         * P7-CURRENT-017: hard cap on a restorable .costbackup bundle (500 MB).
         * Larger files are rejected before/while copying to avoid filling the cache.
         */
        const val MAX_BACKUP_BUNDLE_BYTES: Long = 500L * 1024 * 1024
    }
}
