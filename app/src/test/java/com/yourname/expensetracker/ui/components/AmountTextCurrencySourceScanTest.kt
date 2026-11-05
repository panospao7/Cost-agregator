package com.yourname.expensetracker.ui.components

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fix-1.0.4: feature-scoped source pin (not a registered guard). `AmountText` must take its currency from the
 * caller: no default for `currency`, and no device-locale currency lookup inside the function. Every
 * `AmountText(` call in HomeScreen.kt must pass `currency =`. The scan is limited to the function body /
 * the balanced call argument list, with comments and string bodies blanked, so unrelated text elsewhere in
 * the file cannot satisfy or break it.
 */
class AmountTextCurrencySourceScanTest {

    private val sourceRoot: File by lazy {
        listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        ).firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}")
    }

    private val bentoFile = "com/yourname/expensetracker/ui/components/BentoCard.kt"
    private val homeScreenFile = "com/yourname/expensetracker/ui/screens/home/HomeScreen.kt"

    private fun sanitizedOf(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("missing $relative", file.isFile)
        return SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    /** Text from the opening bracket at [openIndex] to its balanced closing bracket (inclusive). */
    private fun balanced(text: String, openIndex: Int, open: Char, close: Char): String {
        var depth = 0
        for (i in openIndex until text.length) {
            if (text[i] == open) depth++
            if (text[i] == close) {
                depth--
                if (depth == 0) return text.substring(openIndex, i + 1)
            }
        }
        error("unbalanced $open at $openIndex")
    }

    /** Parameter list and body of `fun AmountText(` (declaration only), limited to that function. */
    private fun amountTextDeclaration(sanitized: String): Pair<String, String> {
        val start = Regex("""\bfun\s+AmountText\s*\(""").find(sanitized)
        assertTrue("fun AmountText not found", start != null)
        val paramsOpen = sanitized.indexOf('(', start!!.range.first)
        val params = balanced(sanitized, paramsOpen, '(', ')')
        val bodyOpen = sanitized.indexOf('{', paramsOpen + params.length)
        val body = balanced(sanitized, bodyOpen, '{', '}')
        return params to body
    }

    private val currencyParamWithDefault = Regex("""\bcurrency\s*:\s*[^,)]*=""")

    private fun callArgumentLists(sanitized: String): List<String> =
        Regex("""(?<![A-Za-z0-9_.])AmountText\s*\(""").findAll(sanitized)
            .filter { !sanitized.substring(maxOf(0, it.range.first - 8), it.range.first).contains("fun ") }
            .map { balanced(sanitized, sanitized.indexOf('(', it.range.first), '(', ')') }
            .toList()

    @Test
    fun amountText_declaresCurrencyWithNoDefault() {
        val (params, _) = amountTextDeclaration(sanitizedOf(bentoFile))

        assertTrue("currency parameter missing", Regex("""\bcurrency\s*:\s*String\b""").containsMatchIn(params))
        assertFalse("currency must have no default", currencyParamWithDefault.containsMatchIn(params))
    }

    @Test
    fun amountText_doesNotUseDeviceLocaleOrCurrencyLookup() {
        val (_, body) = amountTextDeclaration(sanitizedOf(bentoFile))

        assertTrue("scan read an implausibly small body", body.contains("Text("))
        assertFalse(body.contains("Locale.getDefault"))
        assertFalse(body.contains("Currency.getInstance"))
    }

    @Test
    fun homeScreen_everyAmountTextCallPassesCurrency() {
        val calls = callArgumentLists(sanitizedOf(homeScreenFile))

        assertTrue("expected at least the two Home hero AmountText calls, found ${calls.size}", calls.size >= 2)
        calls.forEachIndexed { index, args ->
            assertTrue("AmountText call #$index lacks currency =", Regex("""\bcurrency\s*=""").containsMatchIn(args))
        }
    }

    @Test
    fun selfCheck_defaultedCurrencyAndMissingCurrencyAreDetected() {
        val planted = """
            fun AmountText(amount: Double, currency: String = "EUR", modifier: Int = 0) {
                val c = Currency.getInstance(Locale.getDefault())
            }
            fun other() {
                AmountText(amount = 1.0)
                AmountText(amount = 1.0, currency = "USD")
            }
        """.trimIndent()
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(planted)

        val (params, body) = amountTextDeclaration(sanitized)
        assertTrue(currencyParamWithDefault.containsMatchIn(params))
        assertTrue(body.contains("Locale.getDefault"))
        assertTrue(body.contains("Currency.getInstance"))
        val calls = callArgumentLists(sanitized)
        assertEquals(2, calls.size)
        assertFalse(Regex("""\bcurrency\s*=""").containsMatchIn(calls[0]))
        assertTrue(Regex("""\bcurrency\s*=""").containsMatchIn(calls[1]))
    }
}
