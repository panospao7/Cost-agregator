package com.yourname.expensetracker.data.repository

import android.content.Context
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseReadPolicy
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
    private val readBarrier: DatabaseReadBarrier
) {
    /**
     * ## BAK-13: Export no snapshot consistency
     * Uses [deterministicExpenseExportPager] for stable ID-based snapshot consistency.
     * The pager anchors on a fixed set of expense IDs at the start of the export,
     * preventing phantom reads (rows added/deleted mid-export) from causing
     * inconsistent results between data fetching and counting.
     */

    /**
     * Get expenses in a date range using stable ID snapshot for consistency.
     * Delegates to [getExpensesBetweenForExport] which uses the deterministic pager.
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

    suspend fun getExpensesBetweenForExport(startDate: Long, endDate: Long): List<Expense> {
        readBarrier.checkReadAllowed(exportOp, DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ)
        return deterministicExpenseExportPager.fetchAllBetween(startDate, endDate)
    }

    suspend fun countExpensesBetween(startDate: Long, endDate: Long): Int {
        readBarrier.checkReadAllowed(exportOp, DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ)
        return deterministicExpenseExportPager.countBetween(startDate, endDate)
    }

    /**
     * ## SRH-29: Export file encryption (planned)
     * Currently export files are written as plaintext to the app's private
     * `exports/` directory. While this directory is not accessible to other
     * apps without root, a backup of the app data (ADB backup, cloud backup)
     * would expose the exported CSV/JSON data in cleartext.
     *
     * The plan is to encrypt the export file before writing to disk using
     * [com.yourname.expensetracker.data.privacy.BackupEncryptionService]:
     * 1. Generate a one-time encryption key derived from the user's app-level
     *    encryption passphrase (or a randomly generated key that is itself
     *    wrapped with the passphrase via [SecureKeyStorage]).
     * 2. Encrypt the export content as an AES-256-GCM payload (the same scheme
     *    used by [BackupEncryptionService] for `.costbackup` bundles).
     * 3. Prepend a small header (magic bytes + version + wrapped key + IV) so
     *    the file is self-describing and can be decrypted on re-import.
     * 4. Change the file extension to `.xml.enc` / `.csv.enc` to signal that
     *    the file is encrypted.
     *
     * The caller (e.g. [ExportOptionsViewModel]) would check the user's privacy
     * preference and call [encryptExportFile] before sharing the file.
     */
    fun createExportFile(extension: String, timestampMs: Long): File {
        val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
        return File(exportDir, "expenses_${timestampMs}.$extension")
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
     * ## SRH-29: Export file encryption
     *
     * Encrypts the given export file using
     * [BackupEncryptionService] with the user-supplied [password].
     *
     * ### Encryption flow
     * 1. Streams the plaintext file through [BackupEncryptionService.encrypt]
     *    which applies AES-256-GCM with a key derived from [password] via PBKDF2.
     * 2. The encrypted output (salt + IV + ciphertext + GCM tag) is written to a
     *    new file with `.enc` appended to the original extension.
     * 3. On success, the original plaintext file is deleted.
     *
     * ### Usage
     * Callers (e.g. [ExportOptionsViewModel]) should check the user's privacy
     * preference and call this method before sharing the file when encryption
     * is enabled.
     *
     * @param exportFile The unencrypted export file to encrypt.
     * @param password   The user-supplied encryption passphrase.
     * @return The [File] handle of the encrypted file.
     * @throws Exception if encryption fails (e.g. I/O error).
     */
    fun encryptExportFile(exportFile: File, password: String): File {
        val encryptedFile = File(exportFile.parent, "${exportFile.name}.enc")
        FileOutputStream(encryptedFile).use { fos ->
            backupEncryptionService.encrypt(exportFile, fos, password)
        }
        exportFile.delete() // Replace plaintext with encrypted
        return encryptedFile
    }
}
