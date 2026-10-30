package com.yourname.expensetracker.ui.screens.privacysettings.applockpin

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * INV-38 / AE-119 and INV-42 source pins over the App PIN settings ViewModel and the PIN steps (comments and string
 * bodies blanked first). A planted-violation self-check proves each rule can fail.
 */
class AppPinSettingsSourceScanTest {

    private val sourceRoot: File by lazy {
        listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        ).firstOrNull { it.isDirectory } ?: error("Could not locate production source root")
    }

    private val dir = "com/yourname/expensetracker/ui/screens/privacysettings/applockpin"
    private val files = listOf("$dir/AppPinSettingsViewModel.kt", "$dir/PinSetupFlow.kt")

    private val inv38Rules = mapOf(
        "SavedStateHandle" to Regex("""\bSavedStateHandle\b"""),
        "rememberSaveable" to Regex("""\brememberSaveable\b"""),
        "Bundle" to Regex("""\bBundle\b"""),
        "String(" to Regex("""\bString\s*\("""),
        "concatToString" to Regex("""\bconcatToString\b"""),
        "Log." to Regex("""\bLog\s*\."""),
        "Timber." to Regex("""\bTimber\s*\.""")
    )

    private val inv42Rules = mapOf(
        "forgot" to Regex("""(?i)forgot\w*"""),
        "clearPin" to Regex("""\bclearPin\w*"""),
        "resetPin" to Regex("""\breset(Pin|All|Attempts|App)\w*"""),
        "deleteAll" to Regex("""\bdeleteAll\b"""),
        "wipe" to Regex("""(?i)\bwipe\w*"""),
        "direct store or dao" to Regex("""\b(AppPinStore|[A-Za-z]*Dao)\b""")
    )

    private fun sanitized(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("missing production file $relative", file.isFile)
        return SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    private fun hits(rules: Map<String, Regex>, text: String): List<String> =
        rules.filterValues { it.containsMatchIn(text) }.keys.toList()

    @Test
    fun `INV-38 AE-119 the PIN settings ViewModel and steps never save, stringify or log PIN material`() {
        for (relative in files) assertEquals(relative, emptyList<String>(), hits(inv38Rules, sanitized(relative)))
    }

    @Test
    fun `INV-38 self-check each planted violation is caught and the comment-only mention is not`() {
        val planted = mapOf(
            "SavedStateHandle" to "class A(val s: SavedStateHandle)",
            "rememberSaveable" to "val x = rememberSaveable { 1 }",
            "Bundle" to "fun f(b: Bundle) = Unit",
            "String(" to "val s = String(pin)",
            "concatToString" to "val s = pin.concatToString()",
            "Log." to "Log.d(\"t\", \"m\")",
            "Timber." to "Timber.w(\"m\")"
        )
        for ((rule, code) in planted) {
            assertTrue("rule $rule", rule in hits(inv38Rules, SourceTextSanitizer.stripCommentsAndStringBodies(code)))
        }
        val commentOnly = "// never a SavedStateHandle or String(\n/* Log.d */ val ok = 1"
        assertEquals(emptyList<String>(), hits(inv38Rules, SourceTextSanitizer.stripCommentsAndStringBodies(commentOnly)))
        assertEquals(emptyList<String>(), hits(inv38Rules, SourceTextSanitizer.stripCommentsAndStringBodies("val r = stringResource(1) + x.toString()")))
    }

    @Test
    fun `INV-42 the PIN settings ViewModel has no reset, forgot or data-deleting action in code`() {
        assertEquals(emptyList<String>(), hits(inv42Rules, sanitized(files[0])))
        val planted = mapOf(
            "forgot" to "fun onForgotPin() = Unit",
            "clearPin" to "fun clearPinData() = Unit",
            "resetPin" to "fun resetPin() = Unit",
            "deleteAll" to "store.deleteAll()",
            "wipe" to "fun wipeData() = Unit",
            "direct store or dao" to "private val store: AppPinStore"
        )
        for ((rule, code) in planted) {
            assertTrue("rule $rule", rule in hits(inv42Rules, SourceTextSanitizer.stripCommentsAndStringBodies(code)))
        }
    }

    @Test
    fun `INV-42 the public API of the ViewModel exposes no reset or forgot action`() {
        val names = AppPinSettingsViewModel::class.java.methods.map { it.name.lowercase() }
        val offending = names.filter { n -> listOf("forgot", "clearpin", "resetpin", "wipe", "deleteall", "resetall").any { it in n } }
        assertEquals(emptyList<String>(), offending)
        assertTrue("expected API still present", "cancel" in names && "submitentry" in names)
    }
}
