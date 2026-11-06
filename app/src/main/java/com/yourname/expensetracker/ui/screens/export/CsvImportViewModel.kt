package com.yourname.expensetracker.ui.screens.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.ui.util.guardedWrite
import com.yourname.expensetracker.util.CsvExpenseImporter
import com.yourname.expensetracker.util.CsvImportFailureCode
import com.yourname.expensetracker.util.CsvImportPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject

/** Bounded, user-facing reason a CSV import could not proceed (J-12-2). Never carries raw text. */
sealed interface CsvImportError {
    /** The picked file is larger than [CsvImportViewModel.MAX_CSV_BYTES]. */
    object SizeLimitExceeded : CsvImportError

    /** The file could not be opened or read. */
    object ReadFailed : CsvImportError

    /** The importer reported a bounded [code]. */
    data class Failure(val code: CsvImportFailureCode) : CsvImportError
}

/** State machine of the Export-screen "Import CSV" card. */
sealed interface CsvImportUiState {
    object Idle : CsvImportUiState

    /** File read plus the write-free preview analysis. */
    object Reading : CsvImportUiState

    data class Preview(val preview: CsvImportPreview) : CsvImportUiState

    data class Importing(val current: Int, val total: Int) : CsvImportUiState

    data class Result(val imported: Int, val duplicates: Int, val failed: Int) : CsvImportUiState

    /** The user cancelled; rows up to [processedRows] were already handled and are kept. */
    data class Cancelled(val processedRows: Int) : CsvImportUiState

    data class Error(val error: CsvImportError) : CsvImportUiState

    /** The maintenance write barrier rejected the import; message is `ui_write_blocked_maintenance`. */
    object WriteBlocked : CsvImportUiState
}

/**
 * J-12-2: drives the CSV import card. Talks only to [CsvExpenseImporter]
 * (preview is write-free; the import goes through the lifecycle coordinator
 * inside the importer). The file URI and content are never logged; the
 * content string lives in memory only between pick and Result/Cancel/Error.
 */
@HiltViewModel
class CsvImportViewModel @Inject constructor(
    private val importer: CsvExpenseImporter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _state = MutableStateFlow<CsvImportUiState>(CsvImportUiState.Idle)
    val state: StateFlow<CsvImportUiState> = _state.asStateFlow()

    private var pendingContent: String? = null
    private var activeJob: Job? = null

    /**
     * Called with the picked document. [openStream] opens the stream lazily on
     * the IO dispatcher so the URI never enters this class. Ignored while a
     * read, preview or import is already running (double-tap guard).
     */
    fun onFileSelected(openStream: () -> InputStream?) {
        val current = _state.value
        if (current is CsvImportUiState.Reading ||
            current is CsvImportUiState.Importing ||
            current is CsvImportUiState.Preview
        ) {
            return
        }
        if (!_state.compareAndSet(current, CsvImportUiState.Reading)) return
        clearContent()
        activeJob = viewModelScope.launch {
            try {
                val read = withContext(ioDispatcher) { readBounded(openStream) }
                when (read) {
                    is ReadOutcome.TooLarge -> _state.value = CsvImportUiState.Error(CsvImportError.SizeLimitExceeded)
                    is ReadOutcome.Failed -> _state.value = CsvImportUiState.Error(CsvImportError.ReadFailed)
                    is ReadOutcome.Text -> {
                        val preview = importer.preview(read.content)
                        val header = preview.headerFailure
                        if (header != null) {
                            _state.value = CsvImportUiState.Error(CsvImportError.Failure(header))
                        } else {
                            pendingContent = read.content
                            _state.value = CsvImportUiState.Preview(preview)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = CsvImportUiState.Error(CsvImportError.Failure(CsvImportFailureCode.INTERNAL))
            }
        }
    }

    /** Confirm in the preview dialog: starts the import exactly once. */
    fun confirmImport() {
        val current = _state.value as? CsvImportUiState.Preview ?: return
        val content = pendingContent ?: return
        if (current.preview.validRows <= 0) return
        if (!_state.compareAndSet(current, CsvImportUiState.Importing(0, current.preview.totalRows))) return
        activeJob = viewModelScope.launch {
            try {
                var blocked = false
                val result = guardedWrite(onBlocked = { blocked = true }) {
                    importer.importFromContent(content) { done, total ->
                        _state.update { if (it is CsvImportUiState.Importing) CsvImportUiState.Importing(done, total) else it }
                    }
                }
                currentCoroutineContext().ensureActive()
                _state.value = when {
                    blocked || result == null -> CsvImportUiState.WriteBlocked
                    result is CsvExpenseImporter.ImportResult.Success ->
                        CsvImportUiState.Result(result.imported, result.duplicates, result.errors)
                    result is CsvExpenseImporter.ImportResult.Error ->
                        if (result.code == CsvImportFailureCode.WRITE_BLOCKED) {
                            CsvImportUiState.WriteBlocked
                        } else {
                            CsvImportUiState.Error(CsvImportError.Failure(result.code))
                        }
                    else -> CsvImportUiState.Error(CsvImportError.Failure(CsvImportFailureCode.INTERNAL))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = CsvImportUiState.Error(CsvImportError.Failure(CsvImportFailureCode.INTERNAL))
            } finally {
                clearContent()
            }
        }
    }

    /** Cancel in the preview dialog: nothing was written. */
    fun cancelPreview() {
        if (_state.value !is CsvImportUiState.Preview) return
        clearContent()
        _state.value = CsvImportUiState.Idle
    }

    /** Cancel while importing: cancels the job; rows already imported are kept. */
    fun cancelImport() {
        val current = _state.value as? CsvImportUiState.Importing ?: return
        activeJob?.cancel()
        activeJob = null
        clearContent()
        _state.value = CsvImportUiState.Cancelled(current.current)
    }

    /** Dismiss a Result / Cancelled / Error / WriteBlocked card. */
    fun dismiss() {
        val current = _state.value
        if (current is CsvImportUiState.Reading ||
            current is CsvImportUiState.Importing ||
            current is CsvImportUiState.Preview
        ) {
            return
        }
        clearContent()
        _state.value = CsvImportUiState.Idle
    }

    override fun onCleared() {
        clearContent()
        super.onCleared()
    }

    private fun clearContent() {
        pendingContent = null
    }

    private sealed interface ReadOutcome {
        class Text(val content: String) : ReadOutcome
        object TooLarge : ReadOutcome
        object Failed : ReadOutcome
    }

    /** Bounded stream read: stops as soon as more than [MAX_CSV_BYTES] bytes are seen. */
    private suspend fun readBounded(openStream: () -> InputStream?): ReadOutcome {
        return try {
            val stream = openStream() ?: return ReadOutcome.Failed
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_CSV_BYTES) return ReadOutcome.TooLarge
                    out.write(buffer, 0, n)
                }
                ReadOutcome.Text(String(out.toByteArray(), Charsets.UTF_8))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReadOutcome.Failed
        }
    }

    companion object {
        /** Maximum accepted CSV size: 5 MB. */
        const val MAX_CSV_BYTES: Int = 5 * 1024 * 1024
        private const val READ_BUFFER_BYTES = 8 * 1024
    }
}
