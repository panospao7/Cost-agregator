package com.yourname.expensetracker.ui.unsupported

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * App lock S21-c: the one new user-visible string, `unsupported_db_export_auth_denied`, exists in the default, Greek and
 * Spanish resources, is a plain constant (no format placeholder, so no value can be spliced in) and is a real
 * translation, not the English fallback. No existing parity test covers the unsupported-database strings.
 * The el and es texts are DRAFT until the owner signs them off; this test checks presence and shape, not wording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnsupportedDatabaseExportAuthStringsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun localized(languageTag: String): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(languageTag))
        return context.createConfigurationContext(configuration)
    }

    private fun denied(ctx: Context): String = ctx.getString(R.string.unsupported_db_export_auth_denied)

    private fun assertConstantText(value: String, label: String) {
        assertTrue("$label is blank", value.isNotBlank())
        assertFalse("$label has a % placeholder", value.contains('%'))
        assertFalse("$label has a {} placeholder", value.contains('{') || value.contains('}'))
    }

    @Test
    fun s21c_defaultDeniedMessage_isNonBlankAndPlaceholderFree() {
        assertConstantText(denied(context), "default")
    }

    @Test
    fun s21c_spanishDeniedMessage_existsAndDiffersFromDefault() {
        val value = denied(localized("es"))
        assertConstantText(value, "es")
        assertNotEquals("es fell back to the default text", denied(context), value)
    }

    @Test
    fun s21c_greekDeniedMessage_existsAndDiffersFromDefault() {
        val value = denied(localized("el"))
        assertConstantText(value, "el")
        assertNotEquals("el fell back to the default text", denied(context), value)
    }

    @Test
    fun s21c_threeLocalesCarryThreeDistinctTexts() {
        val texts = listOf(denied(context), denied(localized("es")), denied(localized("el")))
        assertEquals(3, texts.toSet().size)
    }

    @Test
    fun s21c_theStringIsDeclaredExactlyOnceInEachResourceFile() {
        val resRoot = listOf("src/main/res", "app/src/main/res", "../app/src/main/res")
            .map { File(it) }
            .firstOrNull { it.isDirectory }
        assertTrue("res root not found from ${System.getProperty("user.dir")}", resRoot != null)
        val declaration = Regex("<string\\s+name=\"unsupported_db_export_auth_denied\"")
        for (folder in listOf("values", "values-el", "values-es")) {
            val text = File(resRoot!!, "$folder/strings.xml").readText()
            assertEquals("$folder declares the string once", 1, declaration.findAll(text).count())
        }
    }
}
