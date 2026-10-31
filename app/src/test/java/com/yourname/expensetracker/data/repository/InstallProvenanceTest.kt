package com.yourname.expensetracker.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.yourname.expensetracker.domain.ai.service.InstallOrigin
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import timber.log.Timber

/**
 * ai-fixes B2b (spec 1.8, INV-23): [PackageInstallProvenance] classifies the install from own-package
 * install and update times only, and never throws. Unit tests run with SDK_INT = 0, so the
 * pre-Tiramisu getPackageInfo(String, Int) branch is the one exercised here.
 */
@Suppress("DEPRECATION")
class InstallProvenanceTest {

    private val packageManager = mockk<PackageManager>()
    private val context = mockk<Context>().also {
        every { it.packageManager } returns packageManager
        every { it.packageName } returns "com.example.own"
    }

    private fun info(first: Long, last: Long) = PackageInfo().also {
        it.firstInstallTime = first
        it.lastUpdateTime = last
    }

    private fun stubInfo(info: PackageInfo) {
        every { packageManager.getPackageInfo("com.example.own", 0) } returns info
    }

    @Test
    fun `equal first install and last update times classify as NEW_INSTALL`() {
        stubInfo(info(first = 1_000L, last = 1_000L))

        assertEquals(InstallOrigin.NEW_INSTALL, PackageInstallProvenance(context).classify())
    }

    @Test
    fun `last update after first install classifies as UPGRADE`() {
        stubInfo(info(first = 1_000L, last = 2_000L))

        assertEquals(InstallOrigin.UPGRADE, PackageInstallProvenance(context).classify())
    }

    @Test
    fun `last update before first install is a clock anomaly and classifies as UNKNOWN`() {
        stubInfo(info(first = 2_000L, last = 1_000L))

        assertEquals(InstallOrigin.UNKNOWN, PackageInstallProvenance(context).classify())
    }

    @Test
    fun `missing times classify as UNKNOWN`() {
        stubInfo(info(first = 0L, last = 0L))

        assertEquals(InstallOrigin.UNKNOWN, PackageInstallProvenance(context).classify())
    }

    @Test
    fun `throwing PackageManager classifies as UNKNOWN and does not throw`() {
        every { packageManager.getPackageInfo("com.example.own", 0) } throws
            PackageManager.NameNotFoundException("com.example.own")

        assertEquals(InstallOrigin.UNKNOWN, PackageInstallProvenance(context).classify())
    }

    @Test
    fun `runtime failure from PackageManager classifies as UNKNOWN`() {
        every { packageManager.getPackageInfo("com.example.own", 0) } throws SecurityException("denied")

        assertEquals(InstallOrigin.UNKNOWN, PackageInstallProvenance(context).classify())
    }

    @Test
    fun `failing context packageManager classifies as UNKNOWN`() {
        val failingContext = mockk<Context>()
        every { failingContext.packageManager } throws IllegalStateException("no package manager")
        every { failingContext.packageName } returns "com.example.own"

        assertEquals(InstallOrigin.UNKNOWN, PackageInstallProvenance(failingContext).classify())
    }

    @Test
    fun `failure logs only the fixed message and never exception text or throwable`() {
        class Entry(val message: String, val throwable: Throwable?)

        val entries = mutableListOf<Entry>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                entries += Entry(message, t)
            }
        }
        Timber.plant(tree)
        try {
            every { packageManager.getPackageInfo("com.example.own", 0) } throws
                SecurityException("denied sensitive-detail")

            assertEquals(InstallOrigin.UNKNOWN, PackageInstallProvenance(context).classify())
        } finally {
            Timber.uproot(tree)
        }

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("Install provenance unavailable; treating as UNKNOWN", entry.message)
        assertNull("no throwable (stack trace) may be attached", entry.throwable)
        assertFalse(entry.message.contains("denied", ignoreCase = true))
        assertFalse(entry.message.contains("sensitive-detail"))
        assertFalse(entry.message.contains("com.example.own"))
    }

    @Test
    fun `only the own package is queried`() {
        stubInfo(info(first = 1_000L, last = 1_000L))

        PackageInstallProvenance(context).classify()

        verify(exactly = 1) { packageManager.getPackageInfo("com.example.own", 0) }
        verify(exactly = 0) { packageManager.getInstalledPackages(any<Int>()) }
        verify(exactly = 0) { packageManager.getInstalledApplications(any<Int>()) }
    }
}
