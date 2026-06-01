package com.yourname.expensetracker.data.repository

import android.content.Context
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseReadPolicy
import com.yourname.expensetracker.data.database.dao.EntitySourceLinkDao
import com.yourname.expensetracker.data.database.entity.EntitySourceLink
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportDataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val deterministicExpenseExportPager: DeterministicExpenseExportPager,
    private val backupEncryptionService: BackupEncryptionService,
    private val readBarrier: DatabaseReadBarrier,
    /** PR7: Source link DAO for bulk provenance queries during export. */
    private val sourceLinkDao: EntitySourceLinkDao
) {
    /**
     * ## BAK-13 / P12-CURRENT-003: Export ordering — NOT a true snapshot
     * Uses [DeterministicExpenseExportPager], which performs **keyset (cursor)
     * pagination** ordered by `(date ASC, id ASC)`. This guarantees:
     * - deterministic, stable ordering across pages, and
     * - every row visited at most once (no offset-shift duplicates/skips).
     *
     * It does **NOT** provide point-in-time snapshot consistency: rows inserted
     * with a `(date, id)` ahead of the cursor can appear mid-export, rows inserted
     * behind the cursor are missed, and [countExpensesBetween] is a separate query
     * that is not anchored to the paged read — so a JSON/CSV `rowCount` can disagree
     * with the streamed rows under concurrent writes. A real `export_snapshot_rows`
     * table (count + stream from one frozen operationId) is the planned fix
     * (P12-P1-04 / PR-SNAP) and is NOT yet implemented. Do not describe this path as
     * snapshot-consistent until that table exists.
     */

    /**
     * Get expenses in a date range via [DeterministicExpenseExportPager] keyset
     * pagination (deterministic `(date, id)` ordering — NOT a point-in-time
     * snapshot; see the class KDoc). Delegates to [getExpensesBetweenForExport].
     */
    private val exportOp = DatabaseAccessOperation(
        name = "ExportDataRepository.export",
        pipeline = "P12",
        entity = "Expense"
    )

    suspend fun getExpensesBetween(startDate: Long, endDate: Long): List<Expense> {
        readBarrier.checkReadAllowed(exportOp, DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ)
        return deterministicExpenseExportPager.fetchAllBetween(startDate, endDate)
    }

    /**
     * Same as [getExpensesBetween] but limits the returned rows to [maxRows].
     * Prevents OOM when only a sample is needed (e.g. pre-export validation).
     */
    suspend fun getExpensesBetween(startDate: Long, endDate: Long, maxRows: Int): List<Expense> {
        readBarrier.checkReadAllowed(exportOp, DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ)
        return deterministicExpenseExportPager.fetchAllBetween(startDate, endDate, maxRows = maxRows)
    }

    suspend fun getExpensesBetweenForExport(startDate: Long, endDate: Long): List<Expense> {
        readBarrier.checkReadAllowed(exportOp, DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ)
        return deterministicExpenseExportPager.fetchAllBetween(startDate, endDate)
    }

    suspend fun countExpensesBetween(startDate: Long, endDate: Long): Int {
        readBarrier.checkReadAllowed(exportOp, DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ)
        return deterministicExpenseExportPager.countBetween(startDate, endDate)
    }

    /**
     * Creates the plaintext export destination handle in the app-private
     * `exports/` directory.
     *
     * ## Encryption (P12-NEW-01)
     * Encryption is wired via [encryptExportFile]: when the caller requests an
     * encrypted export it encrypts a hidden temp file directly into a `.enc`
     * file so plaintext never lands at this shareable path. This directory is
     * app-private (not world-readable without root), but an ADB/cloud backup
     * could otherwise expose unencrypted exports — hence the encrypted option.
     */
    fun createExportFile(extension: String, timestampMs: Long): File {
        // P12-PR2 (NEW-P12-004): Sanitize extension to prevent path traversal
        val safeExtension = extension.replace(Regex("[^a-zA-Z0-9]"), "").take(10).ifEmpty { "txt" }
        val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
        return File(exportDir, "expenses_${timestampMs}.$safeExtension")
    }

    /**
     * Fetches a single page of expenses using keyset cursor pagination.
     * Thin wrapper around [DeterministicExpenseExportPager.fetchPage].
     *
     * Used by [streamExpensesToWriter] in [com.yourname.expensetracker.ui.screens.export.ExportOptionsViewModel]
     * to stream export data without loading all rows into memory.
     *
     * @param startDate Start of the date range (inclusive).
     * @param endDate   End of the date range (exclusive).
     * @param pageSize  Number of rows per page.
     * @param lastDate  Cursor date from last row of previous page (null for first page).
     * @param lastId    Cursor id from last row of previous page (null for first page).
     */
    suspend fun getExpensesPage(
        startDate: Long,
        endDate: Long,
        pageSize: Int,
        lastDate: Long?,
        lastId: Long?
    ): List<Expense> {
        readBarrier.checkReadAllowed(exportOp, DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ)
        return deterministicExpenseExportPager.fetchPage(
        startDate = startDate,
        endDate = endDate,
        pageSize = pageSize,
        lastDate = lastDate,
        lastId = lastId
        )
    }

    suspend fun getCategoryNameMap(): Map<Long, String> {
        readBarrier.checkReadAllowed(exportOp, DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ)
        return categoryRepository.getAll().associate { it.id to it.name }
    }

    /**
     * ## PR7 / P12-CURRENT-020: Bulk provenance read for an export page.
     *
     * Returns the source links for [expenseIds] grouped by the target expense id.
     * Guarded by the same [DatabaseReadBarrier] policy as every other export read
     * so provenance reads cannot slip through during a restore/snapshot window.
     * The raw [EntitySourceLinkDao] is intentionally NOT exposed — callers must go
     * through this barrier-checked entry point (previously the VM read the DAO
     * directly, bypassing the barrier).
     */
    suspend fun getSourceLinksForExpenses(expenseIds: List<Long>): Map<Long, List<EntitySourceLink>> {
        readBarrier.checkReadAllowed(exportOp, DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ)
        return sourceLinkDao.getForExpenses(expenseIds).groupBy { it.targetEntityId }
    }

    /**
     * ## SRH-29 / P12-NEW-01: Export file encryption
     *
     * Encrypts [plaintextFile] into [encryptedFile] using [BackupEncryptionService]
     * with the caller-supplied [password] (AES-256-GCM, PBKDF2-derived key).
     *
     * ### Fail-closed contract
     * - This method does **not** delete [plaintextFile]; the caller owns the
     *   plaintext's lifecycle and is responsible for deleting it (success or
     *   failure) so no cleartext financial data is left on disk.
     * - The caller should encrypt a *hidden temp* file directly into the final
     *   shareable path so plaintext never lands at a path the user can share.
     * - There is **no** default/constant passphrase: [password] must be a real
     *   user-supplied secret (enforced by the caller).
     *
     * @param plaintextFile The unencrypted source file to encrypt.
     * @param encryptedFile  The destination file for the ciphertext.
     * @param password       The user-supplied encryption passphrase (non-blank).
     * @throws Exception if encryption fails (e.g. I/O error). On failure the
     *   caller must delete any partial [encryptedFile].
     */
    fun encryptExportFile(plaintextFile: File, encryptedFile: File, password: String) {
        FileOutputStream(encryptedFile).use { fos ->
            backupEncryptionService.encrypt(plaintextFile, fos, password)
        }
    }
}
