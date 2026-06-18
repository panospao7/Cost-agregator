package com.yourname.expensetracker.util

import javax.inject.Inject
import javax.inject.Singleton

data class ImportResult(
    val success: Boolean,
    val importedCount: Int,
    val skippedCount: Int,
    val errorCount: Int,
    val errors: List<String>,
    val expenseIds: List<Long>
)

enum class ImportFormat {
    CSV_LEGACY, CSV_FULL, JSON_V2, JSON_V1, UNKNOWN
}

@Singleton
class ImportCoordinator @Inject constructor(
    private val csvImporter: CsvExpenseImporter,
    private val jsonImporter: JsonExpenseImporter
) {
    suspend fun importFromContent(content: String, fileImportRunId: Long? = null): ImportResult {
        val format = detectFormat(content)
        return when (format) {
            ImportFormat.CSV_LEGACY, ImportFormat.CSV_FULL -> {
                when (val r = csvImporter.importFromContent(content, fileImportRunId)) {
                    is CsvExpenseImporter.ImportResult.Success -> ImportResult(true, r.imported, r.duplicates, r.errors, emptyList(), emptyList())
                    is CsvExpenseImporter.ImportResult.Error -> ImportResult(false, 0, 0, 1, listOf(r.message), emptyList())
                }
            }
            ImportFormat.JSON_V1, ImportFormat.JSON_V2 -> jsonImporter.importFromContent(content, fileImportRunId)
            ImportFormat.UNKNOWN -> ImportResult(false, 0, 0, 1, listOf("Unrecognized import format"), emptyList())
        }
    }

    fun detectFormat(content: String): ImportFormat {
        val trimmed = content.trimStart()
        val withoutBom = if (trimmed.startsWith("\uFEFF")) trimmed.drop(1) else trimmed
        return when {
            withoutBom.startsWith("{") -> {
                try {
                    val json = org.json.JSONObject(withoutBom)
                    val version = json.optInt("schemaVersion", 1)
                    if (version >= 2) ImportFormat.JSON_V2 else ImportFormat.JSON_V1
                } catch (_: Exception) {
                    ImportFormat.UNKNOWN
                }
            }
            withoutBom.contains(",") -> {
                val firstDataLine = withoutBom.lines().firstOrNull { line ->
                    val t = line.trim()
                    t.isNotEmpty() && !t.startsWith("#")
                } ?: return ImportFormat.UNKNOWN
                val lower = firstDataLine.lowercase()
                when {
                    lower.contains("id") && lower.contains("effectiv") -> ImportFormat.CSV_FULL
                    lower.contains("date") && lower.contains("amount") -> ImportFormat.CSV_LEGACY
                    else -> ImportFormat.CSV_LEGACY
                }
            }
            else -> ImportFormat.UNKNOWN
        }
    }
}