package com.yourname.expensetracker.ui.screens.naturallanguage

import com.yourname.expensetracker.domain.util.CurrencyFormatter
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Chip text for the interpretation card is `stringResource(nlp_amount_*_format, formatted...)` where
 * `formatted` comes from [CurrencyFormatter.formatMoney] in the home currency. `InterpretationCard` and
 * `TransactionResultCard` are Compose functions with no JVM seam in this module, so this class pins what is
 * reachable without changing production: the format resources (read from the XML) and the formatter calls.
 */
class NaturalLanguageSearchStringsTest {

    private lateinit var previousLocale: Locale

    @Before
    fun fixLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun stringsFiles(): List<File> =
        resDir().listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .map { File(it, "strings.xml") }
            .filter { it.isFile }

    private fun value(file: File, name: String): String? =
        Regex("""<string\s+name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1)

    private val oneArgKeys = listOf("nlp_amount_over_format", "nlp_amount_under_format", "nlp_amount_exactly_format")

    @Test
    fun `base locale defines all four amount formats`() {
        val base = File(resDir(), "values/strings.xml")
        (oneArgKeys + "nlp_amount_between_format").forEach { key ->
            assertTrue("$key missing in base strings.xml", value(base, key) != null)
        }
    }

    @Test
    fun `over under and exactly formats take exactly one string argument in every locale`() {
        stringsFiles().forEach { file ->
            oneArgKeys.forEach { key ->
                val text = value(file, key) ?: return@forEach // a locale may fall back to base
                assertTrue("$key in ${file.parentFile.name} must contain %1\$s: $text", text.contains("%1\$s"))
                assertFalse("$key in ${file.parentFile.name} must not take a second argument: $text", text.contains("%2\$"))
                assertFalse("$key in ${file.parentFile.name} must use positional args only: $text", Regex("%(?!\\d\\$|%)[sd]").containsMatchIn(text))
            }
        }
    }

    @Test
    fun `between format takes two string arguments in every locale`() {
        stringsFiles().forEach { file ->
            val text = value(file, "nlp_amount_between_format") ?: return@forEach
            assertTrue("between in ${file.parentFile.name} must contain %1\$s: $text", text.contains("%1\$s"))
            assertTrue("between in ${file.parentFile.name} must contain %2\$s: $text", text.contains("%2\$s"))
            assertFalse("between in ${file.parentFile.name} must not take a third argument: $text", text.contains("%3\$"))
        }
    }

    @Test
    fun `base formats render the home currency amounts the chip passes in`() {
        val base = File(resDir(), "values/strings.xml")
        val low = CurrencyFormatter.formatMoney(10.0, "EUR")
        val high = CurrencyFormatter.formatMoney(50.0, "EUR")

        assertEquals("Over $high", String.format(Locale.US, value(base, "nlp_amount_over_format")!!, high))
        assertEquals("Under $high", String.format(Locale.US, value(base, "nlp_amount_under_format")!!, high))
        assertEquals(high, String.format(Locale.US, value(base, "nlp_amount_exactly_format")!!, high))
        assertEquals(
            "Between $low and $high",
            String.format(Locale.US, value(base, "nlp_amount_between_format")!!, low, high)
        )
    }

    @Test
    fun `formatter output used by the chips carries the home currency symbol and two decimals`() {
        val text = CurrencyFormatter.formatMoney(50.0, "EUR")

        assertTrue(text, text.contains("€"))
        assertTrue(text, text.contains("50.00"))
        // Same value, different home currency: the symbol follows the home currency, not a fixed euro.
        val dollars = CurrencyFormatter.formatMoney(50.0, "USD")
        assertTrue(dollars, dollars.contains("$") && dollars.contains("50.00") && !dollars.contains("€"))
    }
}
