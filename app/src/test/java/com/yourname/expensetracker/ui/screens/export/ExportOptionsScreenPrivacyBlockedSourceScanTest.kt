package com.yourname.expensetracker.ui.screens.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * J-12-1 (fix-1.0.4 S2): `ExportOptionsScreen` must render
 * `uiState.privacyBlocked` with the shared `PrivacyBlockedCard`.
 *
 * The repo has no Compose UI test harness for this screen; the existing
 * convention for "the UI renders X" is a production-source scan (see
 * `PrivacyBlockedUiStateTest`). This is therefore a SOURCE-PIN test, not a
 * rendering test.
 */
class ExportOptionsScreenPrivacyBlockedSourceScanTest {

    private fun screenSource(): String {
        val relative = "com/yourname/expensetracker/ui/screens/export/ExportOptionsScreen.kt"
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}")
        val file = File(root, relative)
        assertThat(file.exists()).isTrue()
        return file.readText()
    }

    private fun withoutComments(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")

    @Test
    fun `screen reads uiState privacyBlocked`() {
        assertThat(withoutComments(screenSource())).contains("uiState.privacyBlocked")
    }

    @Test
    fun `screen renders the blocked state with PrivacyBlockedCard`() {
        val source = withoutComments(screenSource())
        val pattern = Regex(
            "uiState\\.privacyBlocked\\?\\.let\\s*\\{\\s*(\\w+)\\s*->" +
                "[^}]*?PrivacyBlockedCard\\(\\s*state\\s*=\\s*\\1\\s*\\)"
        )
        assertThat(pattern.containsMatchIn(source)).isTrue()
    }

    @Test
    fun `screen does not render a raw privacy decision reason`() {
        val source = withoutComments(screenSource())
        assertThat(Regex("privacyBlocked[^\\n]*\\.reason\\b").containsMatchIn(source)).isFalse()
    }
}
