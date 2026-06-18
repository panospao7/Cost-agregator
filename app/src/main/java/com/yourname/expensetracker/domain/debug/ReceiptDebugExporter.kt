package com.yourname.expensetracker.domain.debug

import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy-gated receiver for parser debug data exports.
 *
 * Extracted from [ReceiptRepository] so that the general repository surface
 * no longer exposes raw OCR/debug data to all callers.  All exports are
 * blocked unless the caller supplies an explicit [exportConsent] reason and
 * the current privacy policy allows the requested level of detail.
 *
 * P3-NEW-11 / P3-CUR-05: Debug export privacy gate.
 */
@Singleton
class ReceiptDebugExporter @Inject constructor(
    private val scannedReceiptDao: ScannedReceiptDao,
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val diagnosticEventWriter: DiagnosticEventWriter
) {

    /**
     * Exports parser debug data for all receipts, respecting the privacy gate.
     *
     * @param includeRawOcrText When true, raw OCR text is included.
     *        Requires explicit consent and a non-restrictive storage mode.
     * @param exportConsent A non-blank reason describing who requested the
     *        export and why. Used for audit trail.
     * @param requestedBy An identifier for the requesting entity.
     * @return The debug export string, or an error message if blocked.
     */
    suspend fun exportParserDebugData(
        includeRawOcrText: Boolean = false,
        exportConsent: String,
        requestedBy: String
    ): DebugExportResult {
        // Gate: require explicit consent reason
        if (exportConsent.isBlank()) {
            writeDebugExportAudit("DENIED", requestedBy, includeRawOcrText, "consent_blank")
            return DebugExportResult.Denied("Export consent reason is required")
        }

        // Gate: raw text requires permissive storage mode (STORE_RAW only)
        if (includeRawOcrText) {
            val mode = privacySettingsRepository.getSettings().rawOcrStorageMode
            if (mode != RawStorageMode.STORE_RAW) {
                writeDebugExportAudit("DENIED", requestedBy, includeRawOcrText, "storage_mode_${mode.name}")
                return DebugExportResult.Denied(
                    "Raw OCR export blocked: storage mode is $mode (requires STORE_RAW)"
                )
            }
        }

        val totalCount = scannedReceiptDao.getCount()
        val sb = StringBuilder()
        sb.append("=== EXPORTED PARSER DEBUG DATA ($totalCount RECEIPTS) ===\n")
        sb.append("=== Requested by: $requestedBy, Reason: $exportConsent ===\n\n")

        val pageSize = 100
        var offset = 0

        while (true) {
            val page = scannedReceiptDao.getReceiptsPaged(pageSize, offset)
            if (page.isEmpty()) break

            page.forEachIndexed { index, receipt ->
                sb.append("--- RECEIPT #${offset + index + 1} (ID: ${receipt.id}) ---\n")
                sb.append(formatReceiptDebug(receipt, includeRawOcrText))
                sb.append("\n\n")
            }
            offset += pageSize
        }
        writeDebugExportAudit("ALLOWED", requestedBy, includeRawOcrText, null)
        return DebugExportResult.Allowed(sb.toString())
    }

    /**
     * Formats debug information for a single receipt.
     */
    suspend fun debugReceipt(
        receiptId: Long,
        includeRawOcrText: Boolean = false,
        exportConsent: String,
        requestedBy: String
    ): DebugExportResult {
        if (exportConsent.isBlank()) {
            writeDebugExportAudit("DENIED", requestedBy, includeRawOcrText, "consent_blank")
            return DebugExportResult.Denied("Export consent reason is required")
        }
        // P3-718-04: Apply same storage-mode gate as bulk export
        if (includeRawOcrText) {
            val mode = privacySettingsRepository.getSettings().rawOcrStorageMode
            if (mode != RawStorageMode.STORE_RAW) {
                writeDebugExportAudit("DENIED", requestedBy, includeRawOcrText, "storage_mode_${mode.name}")
                return DebugExportResult.Denied("Raw OCR export blocked: storage mode is $mode (requires STORE_RAW)")
            }
        }
        val receipt = scannedReceiptDao.getById(receiptId)
            ?: run {
                writeDebugExportAudit("DENIED", requestedBy, includeRawOcrText, "receipt_not_found")
                return DebugExportResult.Denied("Receipt not found: $receiptId")
            }
        writeDebugExportAudit("ALLOWED", requestedBy, includeRawOcrText, null)
        return DebugExportResult.Allowed(formatReceiptDebug(receipt, includeRawOcrText))
    }

    private fun formatReceiptDebug(receipt: com.yourname.expensetracker.data.database.entity.ScannedReceipt, includeRaw: Boolean, includeImagePath: Boolean = false): String {
        return buildString {
            appendLine("═════════════════════════════════════════")
            appendLine("RECEIPT DEBUG REPORT (ID: ${receipt.id})")
            appendLine("═════════════════════════════════════════")
            appendLine()
            if (includeImagePath) {
                appendLine("IMAGE PATH: ${receipt.imagePath}")
            } else {
                appendLine("IMAGE PATH: [REDACTED]")
            }
            appendLine()

            if (includeRaw) {
                appendLine("RAW OCR TEXT:")
                appendLine("┌─────────────────────────────────────┐")
                appendLine("${receipt.rawOcrText}")
                appendLine("└─────────────────────────────────────┘")
                appendLine()
            } else {
                appendLine("RAW OCR TEXT: [REDACTED — consent required]")
                appendLine("  Length: ${receipt.rawOcrText.length} characters")
                appendLine()
            }

            appendLine("PARSED VALUES:")
            appendLine("  • Merchant:     ${receipt.parsedMerchant ?: "NULL"}")
            appendLine("  • Total:        ${receipt.parsedTotal ?: "NULL"}")
            appendLine("  • Date:         ${receipt.parsedDate?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "NULL"}")
            appendLine("  • Tax:          ${receipt.parsedTaxAmount ?: "NULL"}")
            appendLine("  • Currency:     ${receipt.currency}")
            appendLine("  • Confidence:   ${receipt.confidence}")
            appendLine()
            appendLine("LINE ITEMS:")
            appendLine("${receipt.parsedItems ?: "None"}")
            appendLine()
            appendLine("═════════════════════════════════════════")
        }
    }

    private suspend fun writeDebugExportAudit(decision: String, requestedBy: String, includeRaw: Boolean, denyReason: String?) {
        try {
            val md = SafeEventMetadata.builder()
                .put("decision", decision)
                .put("includeRaw", includeRaw.toString())
                .put("requestedBy", requestedBy.take(64))
            if (denyReason != null) md.put("denyReason", denyReason.take(128))
            diagnosticEventWriter.emit(DiagnosticEvent(
                pipeline = AppPipeline.RECEIPT,
                stage = "debug_export",
                outcome = if (decision == "ALLOWED") EventOutcome.COMPLETED else EventOutcome.FAILED_FINAL,
                correlationId = "",
                entityType = "DEBUG_EXPORT",
                entityId = null,
                metadata = md.build()
            ))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { Timber.w(e, "Failed to write debug export audit") }
    }
}

sealed interface DebugExportResult {
    data class Allowed(val content: String) : DebugExportResult
    data class Denied(val reason: String) : DebugExportResult
}
