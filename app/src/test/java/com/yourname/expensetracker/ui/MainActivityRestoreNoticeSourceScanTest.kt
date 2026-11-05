package com.yourname.expensetracker.ui

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * J-11-2 feature-scoped source pin (not a registered guard).
 *
 * No test referenced `backup_restore_notification_unreadable_notice` before, so the notice was recorded on
 * restore but never shown after the relaunch. `MainActivity` must consume the one-shot flag and render the string
 * through the snackbar. Comments are stripped first, so a commented-out call does not satisfy the pin. A planted
 * self-check proves the matcher can fail.
 */
class MainActivityRestoreNoticeSourceScanTest {

    private val sourceRoot: File by lazy {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}")
    }

    private val mainActivityFile = "com/yourname/expensetracker/ui/MainActivity.kt"

    private val consumeCall = Regex("""\.consumeNotificationUnreadable\s*\(\s*\)""")
    private val stringResourceCall = Regex(
        """stringResource\s*\(\s*R\.string\.backup_restore_notification_unreadable_notice\s*\)"""
    )

    private fun mainActivityWithoutComments(): String {
        val file = File(sourceRoot, mainActivityFile)
        assertTrue("missing $mainActivityFile", file.isFile)
        return SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    @Test
    fun j112_mainActivityConsumesTheNotificationUnreadableFlag() {
        assertTrue(
            "MainActivity must call consumeNotificationUnreadable()",
            consumeCall.containsMatchIn(mainActivityWithoutComments())
        )
    }

    @Test
    fun j112_mainActivityResolvesTheNotificationUnreadableNoticeString() {
        assertTrue(
            "MainActivity must resolve R.string.backup_restore_notification_unreadable_notice",
            stringResourceCall.containsMatchIn(mainActivityWithoutComments())
        )
    }

    @Test
    fun j112_mainActivityShowsTheResolvedNoticeInTheSnackbarGatedByTheConsumedFlag() {
        val source = mainActivityWithoutComments()
        val assigned = Regex("""val\s+(\w+)\s*=\s*stringResource\s*\(\s*R\.string\.backup_restore_notification_unreadable_notice""")
            .find(source)?.groupValues?.get(1)
        assertTrue("notice string must be bound to a val", assigned != null)
        val flag = Regex("""val\s+(\w+)\s*=\s*\w+\.consumeNotificationUnreadable\s*\(""")
            .find(source)?.groupValues?.get(1)
        assertTrue("consumed flag must be bound to a val", flag != null)
        val gatedShow = Regex(
            """if\s*\(\s*${Regex.escape(flag!!)}\s*\)\s*\{\s*\w+\.showSnackbar\s*\(\s*${Regex.escape(assigned!!)}\s*\)"""
        )
        assertTrue("the snackbar must show the notice only when the flag was consumed", gatedShow.containsMatchIn(source))
    }

    @Test
    fun j112_selfCheck_commentedOutCallDoesNotSatisfyThePin() {
        val planted = "// restoreVerificationNoticeStore.consumeNotificationUnreadable()\n" +
            "/* stringResource(R.string.backup_restore_notification_unreadable_notice) */\n"
        val stripped = SourceTextSanitizer.stripCommentsAndStringBodies(planted)
        assertTrue(!consumeCall.containsMatchIn(stripped))
        assertTrue(!stringResourceCall.containsMatchIn(stripped))
    }
}
