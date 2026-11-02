package com.yourname.expensetracker.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.database.dao.EntitySourceLinkDao
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * J-12-1 (fix-1.0.4 S2 / D5): age-based sweep of generated export files.
 *
 * Contract under test (from the plan, not from the implementation):
 * - only REGULAR files directly inside `files/exports/` are candidates;
 * - names starting with `.tmp_`, `expenses_` or `accountant_report_` are candidates;
 * - a candidate is deleted when it is at least the minimum age (default 1 hour) old;
 * - every other name and every sub-directory (and its contents) is kept;
 * - a missing directory returns the empty result (0).
 *
 * All files are real files in a JUnit [TemporaryFolder]. Timestamps are whole
 * seconds so coarse file-system mtime granularity cannot blur a boundary.
 */
class ExportDataRepositorySweepStaleExportFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var exportsDir: File

    private val now = 1_000_000_000_000L
    private val oneHourMs = 60L * 60L * 1000L
    private val old = now - 2L * oneHourMs
    private val fresh = now - 60_000L

    @Before
    fun setup() {
        filesDir = tmp.newFolder("files")
        exportsDir = File(filesDir, "exports").apply { mkdirs() }
        context = mockk()
        every { context.filesDir } returns filesDir
    }

    private fun newRepository(): ExportDataRepository = ExportDataRepository(
        context = context,
        expenseRepository = mockk(relaxed = true),
        categoryRepository = mockk(relaxed = true),
        deterministicExpenseExportPager = mockk(relaxed = true),
        backupEncryptionService = mockk(relaxed = true),
        readBarrier = mockk<DatabaseReadBarrier>(relaxed = true),
        sourceLinkDao = mockk<EntitySourceLinkDao>(relaxed = true)
    )

    private fun makeFile(name: String, lastModifiedMs: Long, dir: File = exportsDir): File {
        val f = File(dir, name)
        f.writeText("x")
        assertThat(f.setLastModified(lastModifiedMs)).isTrue()
        return f
    }

    // ---- deletes old files for each prefix ---------------------------------

    @Test
    fun `old files are deleted for each of the three prefixes`() {
        val tmpFile = makeFile(".tmp_expenses_1.csv.5b0c1d2e-0000-4000-8000-000000000001", old)
        val expenses = makeFile("expenses_1.csv", old)
        val report = makeFile("accountant_report_1.pdf", old)

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(3)
        assertThat(tmpFile.exists()).isFalse()
        assertThat(expenses.exists()).isFalse()
        assertThat(report.exists()).isFalse()
    }

    @Test
    fun `real export file names are deleted when old`() {
        val names = listOf(
            "expenses_xero_x.csv",
            "accountant_report_x.pdf",
            "expenses_1.csv.enc",
            "expenses_2.json",
            "expenses_3.iif",
            ".tmp_expenses_4.csv.abc"
        )
        val files = names.map { makeFile(it, old) }

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(names.size)
        files.forEach { assertThat(it.exists()).isFalse() }
    }

    @Test
    fun `fresh files with every prefix are kept`() {
        val kept = listOf(
            makeFile(".tmp_expenses_1.csv.abc", fresh),
            makeFile("expenses_1.csv", fresh),
            makeFile("accountant_report_1.pdf", fresh),
            makeFile("expenses_1.csv.enc", fresh)
        )

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(0)
        kept.forEach { assertThat(it.exists()).isTrue() }
    }

    @Test
    fun `only the old file is deleted when old and fresh share a prefix`() {
        val oldFile = makeFile("expenses_old.csv", old)
        val freshFile = makeFile("expenses_new.csv", fresh)

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(1)
        assertThat(oldFile.exists()).isFalse()
        assertThat(freshFile.exists()).isTrue()
    }

    // ---- boundary age ------------------------------------------------------

    @Test
    fun `default minimum age is one hour - exactly one hour old is deleted`() {
        val boundary = makeFile("expenses_boundary.csv", now - oneHourMs)

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(1)
        assertThat(boundary.exists()).isFalse()
    }

    @Test
    fun `default minimum age is one hour - one second younger is kept`() {
        val justUnder = makeFile("expenses_under.csv", now - oneHourMs + 1_000L)

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(0)
        assertThat(justUnder.exists()).isTrue()
    }

    @Test
    fun `default minimum age is one hour - one second older is deleted`() {
        val justOver = makeFile("expenses_over.csv", now - oneHourMs - 1_000L)

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(1)
        assertThat(justOver.exists()).isFalse()
    }

    @Test
    fun `default minimum age constant is one hour`() {
        assertThat(ExportDataRepository.EXPORT_SWEEP_MIN_AGE_MS).isEqualTo(oneHourMs)
    }

    @Test
    fun `explicit minimum age is honoured at its own boundary`() {
        val tenMin = 10L * 60L * 1000L
        val atBoundary = makeFile("expenses_a.csv", now - tenMin)
        val under = makeFile("expenses_b.csv", now - tenMin + 1_000L)

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now, minAgeMs = tenMin)

        assertThat(deleted).isEqualTo(1)
        assertThat(atBoundary.exists()).isFalse()
        assertThat(under.exists()).isTrue()
    }

    @Test
    fun `file modified in the future relative to now is kept`() {
        val future = makeFile("expenses_future.csv", now + oneHourMs)

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(0)
        assertThat(future.exists()).isTrue()
    }

    // ---- keeps unrelated names --------------------------------------------

    @Test
    fun `old unrelated names are kept - backups and temp export lookalikes`() {
        val kept = listOf(
            makeFile("expense_tracker_backup_1.db", old),
            makeFile("expense_tracker_backup_1.enc", old),
            makeFile("temp_export_x.db", old),
            makeFile("my_expenses_1.csv", old),
            makeFile("expenses.csv", old),
            makeFile("accountant_report.pdf", old),
            makeFile(".other_file", old),
            makeFile("notes.txt", old)
        )
        val target = makeFile("expenses_target.csv", old)

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(1)
        assertThat(target.exists()).isFalse()
        kept.forEach { assertThat(it.exists()).isTrue() }
    }

    @Test
    fun `expense_ prefix is not the expenses_ prefix`() {
        val lookalike = makeFile("expense_1.csv", old)

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(0)
        assertThat(lookalike.exists()).isTrue()
    }

    @Test
    fun `files outside the exports directory are never touched`() {
        val sibling = makeFile("expenses_outside.csv", old, dir = filesDir)
        val inside = makeFile("expenses_inside.csv", old)

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(1)
        assertThat(inside.exists()).isFalse()
        assertThat(sibling.exists()).isTrue()
    }

    // ---- no recursion ------------------------------------------------------

    @Test
    fun `plain subdirectory and its old matching files are kept`() {
        val sub = File(exportsDir, "archive").apply { mkdirs() }
        val inner1 = makeFile("expenses_inner.csv", old, dir = sub)
        val inner2 = makeFile(".tmp_inner", old, dir = sub)
        val inner3 = makeFile("accountant_report_inner.pdf", old, dir = sub)
        sub.setLastModified(old)

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(0)
        assertThat(sub.isDirectory).isTrue()
        assertThat(inner1.exists()).isTrue()
        assertThat(inner2.exists()).isTrue()
        assertThat(inner3.exists()).isTrue()
    }

    @Test
    fun `subdirectories named with each matching prefix are kept with their files`() {
        val dirs = listOf(".tmp_somedir", "expenses_dir", "accountant_report_dir")
        val inners = dirs.map { dirName ->
            val d = File(exportsDir, dirName).apply { mkdirs() }
            val inner = makeFile("expenses_inside.csv", old, dir = d)
            d.setLastModified(old)
            d to inner
        }
        val topLevel = makeFile("expenses_top.csv", old)

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(1)
        assertThat(topLevel.exists()).isFalse()
        inners.forEach { (dir, inner) ->
            assertThat(dir.isDirectory).isTrue()
            assertThat(inner.exists()).isTrue()
        }
    }

    // ---- missing directory -------------------------------------------------

    @Test
    fun `missing exports directory returns the empty result`() {
        every { context.filesDir } returns File(tmp.root, "no_such_files_dir")

        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(0)
    }

    @Test
    fun `empty exports directory returns the empty result`() {
        val deleted = newRepository().sweepStaleExportFiles(nowMs = now)

        assertThat(deleted).isEqualTo(0)
        assertThat(exportsDir.exists()).isTrue()
    }

    @Test
    fun `sweep is idempotent - second run deletes nothing more`() {
        val oldFile = makeFile("expenses_1.csv", old)
        val freshFile = makeFile("expenses_2.csv", fresh)
        val repo = newRepository()

        assertThat(repo.sweepStaleExportFiles(nowMs = now)).isEqualTo(1)
        assertThat(repo.sweepStaleExportFiles(nowMs = now)).isEqualTo(0)

        assertThat(oldFile.exists()).isFalse()
        assertThat(freshFile.exists()).isTrue()
    }
}
