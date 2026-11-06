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
 * J-12-1: [ExportDataRepository.deleteExportFile] removes exactly one generated export file that
 * is a regular file directly inside `files/exports/` with an export prefix, and refuses
 * everything else. All files are real files in a JUnit [TemporaryFolder].
 */
class ExportDataRepositoryDeleteExportFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var exportsDir: File

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

    private fun makeFile(name: String, dir: File = exportsDir): File =
        File(dir, name).apply { writeText("x") }

    @Test
    fun `deletes a valid expenses file in the exports directory`() {
        val file = makeFile("expenses_1700000000000.csv")

        val result = newRepository().deleteExportFile(file.absolutePath)

        assertThat(result).isTrue()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `deletes only the requested file and leaves its siblings`() {
        val target = makeFile("expenses_1.csv")
        val sibling = makeFile("expenses_2.csv")

        val result = newRepository().deleteExportFile(target.absolutePath)

        assertThat(result).isTrue()
        assertThat(target.exists()).isFalse()
        assertThat(sibling.exists()).isTrue()
    }

    @Test
    fun `deletes an encrypted export file with the expenses prefix`() {
        val file = makeFile("expenses_1.csv.enc")

        assertThat(newRepository().deleteExportFile(file.absolutePath)).isTrue()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `second delete of the same path returns false`() {
        val file = makeFile("expenses_1.csv")
        val repo = newRepository()

        assertThat(repo.deleteExportFile(file.absolutePath)).isTrue()
        assertThat(repo.deleteExportFile(file.absolutePath)).isFalse()
    }

    @Test
    fun `refuses dot dot traversal that escapes the exports directory`() {
        val outside = makeFile("expenses_outside.csv", dir = filesDir)
        val traversal = exportsDir.absolutePath + File.separator + ".." + File.separator + "expenses_outside.csv"

        val result = newRepository().deleteExportFile(traversal)

        assertThat(result).isFalse()
        assertThat(outside.exists()).isTrue()
    }

    @Test
    fun `refuses deep traversal through a sub directory`() {
        val sub = File(exportsDir, "sub").apply { mkdirs() }
        val outside = makeFile("expenses_outside.csv", dir = filesDir)
        val traversal = sub.absolutePath + File.separator + ".." + File.separator + ".." +
            File.separator + "expenses_outside.csv"

        val result = newRepository().deleteExportFile(traversal)

        assertThat(result).isFalse()
        assertThat(outside.exists()).isTrue()
    }

    @Test
    fun `refuses a matching file outside the exports directory`() {
        val outside = makeFile("expenses_outside.csv", dir = filesDir)

        val result = newRepository().deleteExportFile(outside.absolutePath)

        assertThat(result).isFalse()
        assertThat(outside.exists()).isTrue()
    }

    @Test
    fun `refuses a matching file in a sibling directory of exports`() {
        val other = File(filesDir, "exports_other").apply { mkdirs() }
        val file = makeFile("expenses_1.csv", dir = other)

        assertThat(newRepository().deleteExportFile(file.absolutePath)).isFalse()
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `refuses a sub directory even when named with the expenses prefix`() {
        val sub = File(exportsDir, "expenses_dir").apply { mkdirs() }
        val inner = makeFile("expenses_inner.csv", dir = sub)

        val result = newRepository().deleteExportFile(sub.absolutePath)

        assertThat(result).isFalse()
        assertThat(sub.isDirectory).isTrue()
        assertThat(inner.exists()).isTrue()
    }

    @Test
    fun `refuses a matching file nested in a sub directory of exports`() {
        val sub = File(exportsDir, "archive").apply { mkdirs() }
        val inner = makeFile("expenses_inner.csv", dir = sub)

        assertThat(newRepository().deleteExportFile(inner.absolutePath)).isFalse()
        assertThat(inner.exists()).isTrue()
    }

    @Test
    fun `refuses a wrong prefix inside the exports directory`() {
        val kept = listOf(
            makeFile("notes.txt"),
            makeFile("expense_1.csv"),
            makeFile("my_expenses_1.csv"),
            makeFile("expense_tracker_backup_1.db"),
            makeFile("other_file")
        )

        kept.forEach { assertThat(newRepository().deleteExportFile(it.absolutePath)).isFalse() }
        kept.forEach { assertThat(it.exists()).isTrue() }
    }

    @Test
    fun `refuses a blank or empty path`() {
        val file = makeFile("expenses_1.csv")
        val repo = newRepository()

        assertThat(repo.deleteExportFile("")).isFalse()
        assertThat(repo.deleteExportFile("   ")).isFalse()
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `refuses the exports directory itself`() {
        assertThat(newRepository().deleteExportFile(exportsDir.absolutePath)).isFalse()
        assertThat(exportsDir.isDirectory).isTrue()
    }

    @Test
    fun `returns false for a missing file`() {
        val missing = File(exportsDir, "expenses_missing.csv")

        assertThat(newRepository().deleteExportFile(missing.absolutePath)).isFalse()
    }

    @Test
    fun `returns false when the exports directory does not exist`() {
        every { context.filesDir } returns File(tmp.root, "no_such_files_dir")

        assertThat(newRepository().deleteExportFile("expenses_1.csv")).isFalse()
    }
}
