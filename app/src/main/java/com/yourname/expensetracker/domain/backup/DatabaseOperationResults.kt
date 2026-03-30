package com.yourname.expensetracker.domain.backup

sealed class DatabaseExportResult {
    object Loading : DatabaseExportResult()
    data class Success(val filePath: String) : DatabaseExportResult()
    data class Error(val message: String) : DatabaseExportResult()
}

sealed class DatabaseImportResult {
    object Loading : DatabaseImportResult()
    data class Success(val summary: DatabaseImportSummary) : DatabaseImportResult()
    object SuccessNeedsRestart : DatabaseImportResult()
    data class Error(val message: String) : DatabaseImportResult()
}
