package com.yourname.expensetracker.data.repository

import android.content.Context
import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.database.dao.EntitySourceLinkDao
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RP-19 (19-C): conservative stale-temp-file sweep.
 *
 * Contract: only hidden `.tmp_`-prefixed files inside the exports directory
 * older than the minimum age are deleted; user-facing final exports are never
 * touched; a missing directory is a no-op.
 */
class ExportDataRepositoryStaleTempSweepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var exportsDir: File

    @Before
    fun setup() {
        exportsDir = File(tmp.newFolder("files"), "exports").apply { mkdirs() }
        context = mockk()
        every { context.filesDir } returns File(tmp.root, "files")
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

    private fun makeFile(name: String, lastModifiedMs: Long): File {
        val f = File(exportsDir, name)
        f.writeText("x")
        f.setLastModified(lastModifiedMs)
        return f
    }

    @Test
    fun `sweeps only stale dot-tmp files`() {
        val now = 1_000_000_000_000L
        val stale = makeFile(".tmp_expenses_1.csv.abc", now - 25L * 60 * 60 * 1000)
        val fresh = makeFile(".tmp_expenses_2.csv.def", now - 1000)
        val finalExport = makeFile("expenses_3.csv", now - 48L * 60 * 60 * 1000)
        val unrelatedHidden = makeFile(".other_file", now - 48L * 60 * 60 * 1000)

        val deleted = newRepository().sweepStaleTempFiles(nowMs = now)

        assertThat(deleted).isEqualTo(1)
        assertThat(stale.exists()).isFalse()
        assertThat(fresh.exists()).isTrue()
        assertThat(finalExport.exists()).isTrue()
        assertThat(unrelatedHidden.exists()).isTrue()
    }

    @Test
    fun `files exactly at the minimum age boundary are swept`() {
        val now = 1_000_000_000_000L
        val boundary = makeFile(".tmp_boundary.csv", now - ExportDataRepository.STALE_TEMP_MIN_AGE_MS)

        val deleted = newRepository().sweepStaleTempFiles(nowMs = now)

        assertThat(deleted).isEqualTo(1)
        assertThat(boundary.exists()).isFalse()
    }

    @Test
    fun `missing exports directory is a no-op`() {
        every { context.filesDir } returns File(tmp.root, "no_such_files_dir")

        val deleted = newRepository().sweepStaleTempFiles(nowMs = 1_000_000_000_000L)

        assertThat(deleted).isEqualTo(0)
    }

    @Test
    fun `subdirectories are never deleted`() {
        val now = 1_000_000_000_000L
        val dir = File(exportsDir, ".tmp_somedir").apply { mkdirs() }

        val deleted = newRepository().sweepStaleTempFiles(nowMs = now)

        assertThat(deleted).isEqualTo(0)
        assertThat(dir.exists()).isTrue()
    }
}
