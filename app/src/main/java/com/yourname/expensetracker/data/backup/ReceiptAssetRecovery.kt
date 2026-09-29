package com.yourname.expensetracker.data.backup

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * S2 (RP-03 asset recovery): the single per-asset state machine shared by the primary
 * restore loop ([com.yourname.expensetracker.data.repository.DatabaseBackupRepositoryImpl.restoreReceiptAssets])
 * and the startup ASSETS_RESTORING resume.
 *
 * The engine owns no DAO. Each caller reads the pointer and runs the path-only CAS inside
 * its own `restoreInternalWriteScope.run(...)` body (so DB ownership rows stay exact) and
 * feeds the result back through [admit] / [afterCas].
 *
 * Ledger: PENDING -> TEMP_WRITTEN -> FINAL_DURABLE -> DB_UPDATED -> COMPLETED; FAILED is
 * terminal. Every transition is journaled (non-best-effort) before the next side effect:
 * - bytes are streamed into an operation-scoped temp, bounded by the recorded size,
 *   fsync'd, and verified against the bundle SHA-256/size proof before TEMP_WRITTEN;
 * - publish is a no-replace move, so a foreign final file is never overwritten;
 * - a crash after publish (TEMP_WRITTEN, temp gone, final matches) is adopted, not
 *   reported as a collision;
 * - a crash after the CAS (FINAL_DURABLE, pointer already final) replays without a CAS;
 * - COMPLETED tasks are re-verified (proof, bytes, pointer) and never touch files or DB.
 *
 * Only operation-owned files are deleted: the temp, or a proof-matching final that this
 * operation published and the database does not reference.
 *
 * Known residuals: no directory fsync after publish; TOCTOU between the collision check
 * and the move is bounded by the no-replace move; stale temps from other operations are
 * not swept (no receipt-asset orphan cleanup job exists).
 */
internal class ReceiptAssetRecovery(
    private val journal: RestoreJournal,
    private val receiptsDir: File,
    private val sourceDir: File?
) {

    sealed interface Step {
        val entry: RestoreJournal.JournalEntry
        val task: RestoreJournal.AssetRestoreTask

        /** Terminal for this call. [restoredNow] is true only when this call moved the pointer. */
        data class Done(
            override val entry: RestoreJournal.JournalEntry,
            override val task: RestoreJournal.AssetRestoreTask,
            val restoredNow: Boolean = false
        ) : Step

        /** Caller must read the receipt row and pass it to [admit]. */
        data class NeedsPointer(
            override val entry: RestoreJournal.JournalEntry,
            override val task: RestoreJournal.AssetRestoreTask
        ) : Step

        /** Caller must CAS `expected -> target` inside its restore write scope, then call [afterCas]. */
        data class NeedsCas(
            override val entry: RestoreJournal.JournalEntry,
            override val task: RestoreJournal.AssetRestoreTask,
            val expected: String?,
            val target: String
        ) : Step
    }

    enum class CasOutcome {
        /** Readback proves the pointer moved to the target. */
        CONFIRMED,
        /** Zero rows and readback proves the pointer is not the target. */
        NOT_APPLIED,
        /** Row count and readback disagree; the pointer state is not trustworthy. */
        UNCONFIRMED
    }

    /** Identity and pre-DB checks. Never touches the database. */
    fun begin(entry: RestoreJournal.JournalEntry, task: RestoreJournal.AssetRestoreTask): Step {
        if (task.status == RestoreJournal.AssetRestoreStatus.FAILED) return Step.Done(entry, task)

        val src = task.sourceRelativePath
        if (src.isBlank() || src == "." || src == ".." || src.contains('/') || src.contains('\\')) {
            return fail(entry, task, RestoreJournal.ASSET_REASON_INVALID_TARGET)
        }
        val finalName = RestoreJournal.deriveAssetTargetName(task.receiptId, src.substringAfterLast('.', ""))
            ?: return fail(entry, task, RestoreJournal.ASSET_REASON_INVALID_TARGET)
        val journalName = task.targetPath?.let { File(it).name }
        if (journalName != null && journalName != finalName) {
            return fail(entry, task, RestoreJournal.ASSET_REASON_INVALID_TARGET)
        }
        val finalFile = File(receiptsDir, finalName)

        if (task.status == RestoreJournal.AssetRestoreStatus.PENDING) {
            if (!sourceFile(task).isUsableSource()) {
                return fail(entry, task, RestoreJournal.ASSET_REASON_SOURCE_MISSING)
            }
            // A PENDING task has not published yet: any final file is foreign.
            if (finalFile.exists()) return fail(entry, task, RestoreJournal.ASSET_REASON_TARGET_COLLISION)
        }
        // A pre-CAS task without its original pointer cannot authorize a replay.
        val preCas = task.status == RestoreJournal.AssetRestoreStatus.PENDING ||
            task.status == RestoreJournal.AssetRestoreStatus.TEMP_WRITTEN ||
            task.status == RestoreJournal.AssetRestoreStatus.FINAL_DURABLE
        if (preCas && !task.expectedImagePathRecorded) {
            return fail(entry, task, RestoreJournal.ASSET_REASON_EXPECTED_PATH_MISSING)
        }
        if (!hasProof(task)) return fail(entry, task, RestoreJournal.ASSET_REASON_PROOF_MISSING)
        return Step.NeedsPointer(entry, task.copy(targetPath = finalFile.absolutePath))
    }

    /**
     * Applies the file-side transitions for a task whose receipt row was read by the caller.
     * [rowExists]/[pointer] must come from the fresh restored database inside the restore scope.
     */
    fun admit(step: Step.NeedsPointer, rowExists: Boolean, pointer: String?): Step {
        var entry = step.entry
        var task = step.task
        val finalFile = File(task.targetPath!!)
        val tempFile = tempFor(entry, finalFile)
        val expected = task.expectedImagePath
        val finalPath = finalFile.absolutePath

        if (!rowExists) {
            if (task.status == RestoreJournal.AssetRestoreStatus.PENDING ||
                task.status == RestoreJournal.AssetRestoreStatus.TEMP_WRITTEN
            ) deleteOwned(tempFile)
            return fail(entry, task, RestoreJournal.ASSET_REASON_ROW_MISSING)
        }

        when (task.status) {
            RestoreJournal.AssetRestoreStatus.COMPLETED -> {
                // Repeated recovery: verify only, never touch files or the database.
                if (!matchesProof(finalFile, task)) {
                    return fail(entry, task, RestoreJournal.ASSET_REASON_INTEGRITY_MISMATCH)
                }
                if (pointer != finalPath) return fail(entry, task, RestoreJournal.ASSET_REASON_IMAGE_PATH_CONFLICT)
                return Step.Done(entry, task)
            }
            RestoreJournal.AssetRestoreStatus.DB_UPDATED -> {
                if (pointer != finalPath) return fail(entry, task, RestoreJournal.ASSET_REASON_IMAGE_PATH_CONFLICT)
                if (!matchesProof(finalFile, task)) {
                    return fail(entry, task, RestoreJournal.ASSET_REASON_INTEGRITY_MISMATCH)
                }
                val done = persist(entry, task, RestoreJournal.AssetRestoreStatus.COMPLETED)
                return Step.Done(done.first, done.second)
            }
            RestoreJournal.AssetRestoreStatus.PENDING,
            RestoreJournal.AssetRestoreStatus.TEMP_WRITTEN -> {
                if (pointer != expected) {
                    // Newer/null pointer: the verified database wins; only our temp is discarded.
                    deleteOwned(tempFile)
                    return fail(entry, task, RestoreJournal.ASSET_REASON_IMAGE_PATH_CONFLICT)
                }
                when (val staged = stage(entry, task, finalFile, tempFile)) {
                    is Step.Done -> return staged
                    else -> {
                        entry = staged.entry
                        task = staged.task
                    }
                }
            }
            RestoreJournal.AssetRestoreStatus.FINAL_DURABLE -> {
                if (!matchesProof(finalFile, task)) {
                    return fail(entry, task, RestoreJournal.ASSET_REASON_INTEGRITY_MISMATCH)
                }
                deleteOwned(tempFile)
            }
            RestoreJournal.AssetRestoreStatus.FAILED -> return Step.Done(entry, task)
        }

        // FINAL_DURABLE with a proven final file.
        return when (pointer) {
            finalPath -> {
                // Crash after the CAS but before DB_UPDATED: replay without a second mutation.
                val updated = persist(entry, task, RestoreJournal.AssetRestoreStatus.DB_UPDATED)
                val done = persist(updated.first, updated.second, RestoreJournal.AssetRestoreStatus.COMPLETED)
                Step.Done(done.first, done.second)
            }
            expected -> Step.NeedsCas(entry, task, expected, finalPath)
            else -> fail(entry, task, RestoreJournal.ASSET_REASON_IMAGE_PATH_CONFLICT)
        }
    }

    /** Records the result of the caller's path-only CAS + read-back. */
    fun afterCas(step: Step.NeedsCas, outcome: CasOutcome): Step {
        return when (outcome) {
            CasOutcome.CONFIRMED -> {
                val updated = persist(step.entry, step.task, RestoreJournal.AssetRestoreStatus.DB_UPDATED)
                val done = persist(updated.first, updated.second, RestoreJournal.AssetRestoreStatus.COMPLETED)
                Step.Done(done.first, done.second, restoredNow = true)
            }
            CasOutcome.NOT_APPLIED -> {
                // The row provably does not reference the file this operation published.
                deleteOwned(File(step.target))
                fail(step.entry, step.task, RestoreJournal.ASSET_REASON_IMAGE_PATH_CONFLICT)
            }
            // Pointer state is untrustworthy: keep the file, the database may reference it.
            CasOutcome.UNCONFIRMED -> fail(step.entry, step.task, RestoreJournal.ASSET_REASON_IMAGE_PATH_CONFLICT)
        }
    }

    /** Marks the task FAILED with a controlled reason (non-best-effort journal write). */
    fun fail(
        entry: RestoreJournal.JournalEntry,
        task: RestoreJournal.AssetRestoreTask,
        reasonCode: String
    ): Step.Done {
        val updated = persist(entry, task, RestoreJournal.AssetRestoreStatus.FAILED, reasonCode)
        return Step.Done(updated.first, updated.second)
    }

    // -- file stages ---------------------------------------------------------------------------

    /**
     * PENDING/TEMP_WRITTEN -> FINAL_DURABLE. Returns [Step.Done] on failure, otherwise a
     * [Step.NeedsPointer] carrying the FINAL_DURABLE entry/task.
     */
    private fun stage(
        entry0: RestoreJournal.JournalEntry,
        task0: RestoreJournal.AssetRestoreTask,
        finalFile: File,
        tempFile: File
    ): Step {
        var entry = entry0
        var task = task0
        if (task.status == RestoreJournal.AssetRestoreStatus.TEMP_WRITTEN) {
            val tempProven = tempFile.isFile && matchesProof(tempFile, task)
            if (!tempFile.exists() && finalFile.exists()) {
                // Crash after publish, before FINAL_DURABLE: adopt only a proof-matching output.
                if (!matchesProof(finalFile, task)) {
                    return fail(entry, task, RestoreJournal.ASSET_REASON_TARGET_COLLISION)
                }
                val published = persist(entry, task, RestoreJournal.AssetRestoreStatus.FINAL_DURABLE)
                return Step.NeedsPointer(published.first, published.second)
            }
            if (finalFile.exists()) {
                // Our temp was never published, so the final file is foreign.
                deleteOwned(tempFile)
                return fail(entry, task, RestoreJournal.ASSET_REASON_TARGET_COLLISION)
            }
            if (!tempProven) {
                deleteOwned(tempFile)
                if (!sourceFile(task).isUsableSource()) {
                    return fail(entry, task, RestoreJournal.ASSET_REASON_SOURCE_MISSING)
                }
                copyFailure(task, tempFile)?.let { return fail(entry, task, it) }
            }
        } else {
            if (finalFile.exists()) return fail(entry, task, RestoreJournal.ASSET_REASON_TARGET_COLLISION)
            deleteOwned(tempFile)
            copyFailure(task, tempFile)?.let { return fail(entry, task, it) }
            val written = try {
                persist(entry, task, RestoreJournal.AssetRestoreStatus.TEMP_WRITTEN)
            } catch (e: Exception) {
                deleteOwned(tempFile)
                throw e
            }
            entry = written.first
            task = written.second
        }

        publishFailure(tempFile, finalFile)?.let { reason ->
            deleteOwned(tempFile)
            return fail(entry, task, reason)
        }
        val published = persist(entry, task, RestoreJournal.AssetRestoreStatus.FINAL_DURABLE)
        return Step.NeedsPointer(published.first, published.second)
    }

    /** Streams the source into [tempFile], bounded and hashed; returns a reason code on failure. */
    private fun copyFailure(task: RestoreJournal.AssetRestoreTask, tempFile: File): String? {
        val expectedSize = task.expectedSize!!
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var complete = false
        try {
            FileInputStream(sourceFile(task)).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > expectedSize) return RestoreJournal.ASSET_REASON_INTEGRITY_MISMATCH
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    journal.testAssetIo?.invoke(tempFile, RestoreJournal.IoStage.SYNC)
                    // TEMP_WRITTEN requires durable bytes: an fsync failure fails the task.
                    output.fd.sync()
                }
            }
            if (total != expectedSize || hex(digest.digest()) != task.expectedSha256) {
                return RestoreJournal.ASSET_REASON_INTEGRITY_MISMATCH
            }
            complete = true
            return null
        } catch (_: IOException) {
            return RestoreJournal.ASSET_REASON_WRITE_FAILED
        } finally {
            if (!complete) deleteOwned(tempFile)
        }
    }

    /** No-replace publish: a foreign final file is never overwritten. */
    private fun publishFailure(tempFile: File, finalFile: File): String? = try {
        journal.testAssetIo?.invoke(finalFile, RestoreJournal.IoStage.MOVE)
        java.nio.file.Files.move(tempFile.toPath(), finalFile.toPath())
        null
    } catch (_: java.nio.file.FileAlreadyExistsException) {
        RestoreJournal.ASSET_REASON_TARGET_COLLISION
    } catch (_: IOException) {
        RestoreJournal.ASSET_REASON_WRITE_FAILED
    }

    // -- helpers -------------------------------------------------------------------------------

    private fun persist(
        entry: RestoreJournal.JournalEntry,
        task: RestoreJournal.AssetRestoreTask,
        status: RestoreJournal.AssetRestoreStatus,
        error: String? = null
    ): Pair<RestoreJournal.JournalEntry, RestoreJournal.AssetRestoreTask> {
        val next = task.copy(status = status, error = error)
        val updated = entry.copy(
            assetTasks = entry.assetTasks.map { t ->
                if (t.receiptId == task.receiptId && t.sourceRelativePath == task.sourceRelativePath) next else t
            }
        )
        // Required transition: a durability failure propagates and keeps recovery resumable.
        journal.writeJournal(updated)
        return updated to next
    }

    private fun sourceFile(task: RestoreJournal.AssetRestoreTask): File? =
        sourceDir?.let { File(it, task.sourceRelativePath) }

    private fun File?.isUsableSource(): Boolean = this != null && isFile

    private fun hasProof(task: RestoreJournal.AssetRestoreTask): Boolean =
        task.expectedSha256 != null && task.expectedSize != null

    private fun matchesProof(file: File, task: RestoreJournal.AssetRestoreTask): Boolean = try {
        file.isFile && file.length() == task.expectedSize &&
            CostbackupBundle.sha256Hex(file) == task.expectedSha256
    } catch (_: IOException) {
        false
    }

    /** Operation-scoped temp name, so a stale temp from another operation is never adopted. */
    private fun tempFor(entry: RestoreJournal.JournalEntry, finalFile: File): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(entry.operationId.toByteArray(Charsets.UTF_8))
        return File(finalFile.parentFile, ".${finalFile.name}.${hex(digest).take(16)}.tmp")
    }

    private fun deleteOwned(file: File) {
        if (file.exists()) file.delete()
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val BUNDLE_RECEIPTS_PREFIX = "files/receipts/"

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** Receipt images inside an extracted costbackup root (see [CostbackupBundle.create]). */
        fun receiptsSourceDir(extractRoot: File): File = File(extractRoot, "files/receipts")

        /**
         * Bundle proofs keyed by receipt file name, from the extracted checksums.json.
         * Unreadable or malformed manifests yield an empty map, so every task fails closed
         * with [RestoreJournal.ASSET_REASON_PROOF_MISSING].
         */
        fun readBundleProofs(extractRoot: File): Map<String, String> = try {
            val file = File(extractRoot, "checksums.json")
            if (!file.isFile) {
                emptyMap()
            } else {
                CostbackupBundle.ChecksumsManifest.fromJson(org.json.JSONObject(file.readText()))
                    .entries
                    .filterKeys { it.startsWith(BUNDLE_RECEIPTS_PREFIX) }
                    .mapKeys { it.key.removePrefix(BUNDLE_RECEIPTS_PREFIX) }
                    .filterValues { RestoreJournal.isSha256Hex(it) }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyMap()
        }
    }
}
