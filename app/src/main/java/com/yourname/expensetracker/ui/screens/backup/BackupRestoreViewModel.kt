package com.yourname.expensetracker.ui.screens.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * UI state for the Backup & Restore screen.
 */
data class BackupRestoreUiState(
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false,
    val lastBackupDate: String? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val restartRequired: Boolean = false,
    val lastBackupFile: String? = null
)

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseBackupRepository: DatabaseBackupRepository,
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

    init {
        loadLastBackupInfo()
    }

    /**
     * Loads last backup timestamp from the maintenance mode prefs or database stats.
     */
    private fun loadLastBackupInfo() {
        viewModelScope.launch {
            try {
                val stats = databaseBackupRepository.getDatabaseStats()
                val lastBackup = stats.lastBackupDate
                if (lastBackup != null && lastBackup > 0L) {
                    val formatted = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm",
                        java.util.Locale.getDefault()
                    ).format(java.util.Date(lastBackup))
                    _uiState.value = _uiState.value.copy(lastBackupDate = formatted)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to load backup info")
            }
        }
    }

    /**
     * Creates a .costbackup bundle with the given password.
     */
    fun createBackup(password: String) {
        if (password.isBlank()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Password cannot be empty"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isBackingUp = true,
                errorMessage = null,
                successMessage = null
            )

            val result = databaseBackupRepository.createCostBackup(password)

            result.fold(
                onSuccess = { file ->
                    Timber.d("Backup created: %s", file.absolutePath)
                    _uiState.value = _uiState.value.copy(
                        isBackingUp = false,
                        successMessage = "Backup created successfully: ${file.name}",
                        lastBackupFile = file.absolutePath,
                        lastBackupDate = java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date())
                    )
                },
                onFailure = { error ->
                    Timber.e(error, "Backup creation failed")
                    val message = when {
                        error.message?.contains("password", ignoreCase = true) == true ->
                            "Encryption error: ${error.message}"
                        error.message?.contains("denied", ignoreCase = true) == true ->
                            "Privacy gate denied: ${error.message}"
                        else -> "Backup failed: ${error.message ?: "Unknown error"}"
                    }
                    _uiState.value = _uiState.value.copy(
                        isBackingUp = false,
                        errorMessage = message
                    )
                }
            )
        }
    }

    /**
     * Restores a .costbackup bundle from the given URI.
     *
     * Copies the content from the URI to a temp file, then calls
     * [DatabaseBackupRepository.restoreCostBackup].
     */
    fun restoreBackup(uri: Uri, password: String) {
        if (password.isBlank()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Password cannot be empty"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isRestoring = true,
                errorMessage = null,
                successMessage = null,
                restartRequired = false
            )

            // Copy URI content to a temp file for processing
            val tempFile = runCatching {
                val temp = File.createTempFile("restore_", ".costbackup", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                temp
            }.getOrElse { error ->
                _uiState.value = _uiState.value.copy(
                    isRestoring = false,
                    errorMessage = "Failed to read backup file: ${error.message}"
                )
                return@launch
            }

            val result = databaseBackupRepository.restoreCostBackup(tempFile, password)

            // Clean up temp file
            tempFile.delete()

            result.fold(
                onSuccess = { importResult ->
                    Timber.d("Restore completed: %s", importResult)
                    when (importResult) {
                        is DatabaseImportResult.SuccessNeedsRestart -> {
                            _uiState.value = _uiState.value.copy(
                                isRestoring = false,
                                successMessage = "Restore completed successfully!",
                                restartRequired = true
                            )
                        }
                        is DatabaseImportResult.Success -> {
                            _uiState.value = _uiState.value.copy(
                                isRestoring = false,
                                successMessage = "Restore completed successfully!"
                            )
                        }
                        is DatabaseImportResult.Loading -> {
                            // Should not happen in practice
                            _uiState.value = _uiState.value.copy(
                                isRestoring = false
                            )
                        }
                        is DatabaseImportResult.Error -> {
                            _uiState.value = _uiState.value.copy(
                                isRestoring = false,
                                errorMessage = importResult.message
                            )
                        }
                    }
                },
                onFailure = { error ->
                    Timber.e(error, "Restore failed")
                    val message = when {
                        error is com.yourname.expensetracker.data.backup.CostbackupBundle.WrongBackupPasswordException ->
                            "Incorrect password or corrupt backup file"
                        error.message?.contains("password", ignoreCase = true) == true ->
                            "Incorrect password or corrupt backup file"
                        error.message?.contains("denied", ignoreCase = true) == true ->
                            "Privacy gate denied: ${error.message}"
                        else -> "Restore failed: ${error.message ?: "Unknown error"}"
                    }
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        errorMessage = message
                    )
                }
            )
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Clears the current success message.
     */
    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    /**
     * Clears the restart-required flag after the user dismisses the message.
     */
    fun dismissRestartRequired() {
        _uiState.value = _uiState.value.copy(restartRequired = false)
    }
}
