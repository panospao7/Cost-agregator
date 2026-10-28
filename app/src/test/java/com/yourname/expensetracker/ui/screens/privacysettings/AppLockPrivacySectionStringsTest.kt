package com.yourname.expensetracker.ui.screens.privacysettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * S22-a.1 (ux 9.1): the 24 strings the lock row, the enable explainer and the inline notices added to
 * `strings_privacy_section.xml` exist in the default, Spanish and Greek resources, carry the ux 9.1 English copy, are
 * real translations (not the English fallback) and carry the same placeholder set in every language.
 *
 * The files are parsed from the source tree (a plain JVM test, as the sibling string tests locate them), so the check
 * covers the declared resource, not a merged or fallback value. The es and el texts are DRAFT until the owner signs
 * them off (O-2): this test checks presence, shape and parity, not wording quality.
 */
class AppLockPrivacySectionStringsTest {

    /** The 24 new keys with their ux-flows 9.1 English copy (apostrophes unescaped). */
    private val newStrings: Map<String, String> = linkedMapOf(
        "privacy_toggle_app_lock" to "App lock",
        "privacy_app_lock_support" to "Ask for your fingerprint or screen lock when the app opens.",
        "privacy_app_lock_waiting" to "Waiting for confirmation…",
        "privacy_app_lock_unavailable_os" to "App lock needs Android 11 or newer. This device runs an older version.",
        "privacy_app_lock_unavailable_os_paused" to
            "App lock isn't supported on this Android version, so it is paused. Turn it off to remove this notice.",
        "privacy_app_lock_unavailable_no_secure" to
            "Set a PIN, pattern or password as your phone's screen lock to use app lock.",
        "privacy_app_lock_unavailable_now" to "App lock isn't available right now. Try again in a moment.",
        "privacy_app_lock_open_security_settings" to "Open security settings",
        "privacy_app_lock_unreadable" to
            "Can't read the app lock setting. If your phone has a screen lock, the app will ask you to unlock it.",
        "privacy_discreet_notifications_forced" to "Always on while app lock is on.",
        "privacy_review_lock_paused" to "App lock is paused, so Off is the only choice.",
        "privacy_app_lock_enable_title" to "Turn on app lock?",
        "privacy_app_lock_enable_point_unlock" to
            "The app asks for your fingerprint or screen lock when it opens, and after you leave it for the time you choose.",
        "privacy_app_lock_enable_point_notifications" to "Notifications show no amounts or details while app lock is on.",
        "privacy_app_lock_enable_point_replaced" to "Notifications already showing are replaced with the private version.",
        "privacy_app_lock_enable_point_screen" to
            "Screenshots and screen recording are blocked and the app is hidden in recent apps.",
        "privacy_app_lock_enable_point_background" to
            "Reminders and automatic capture keep working while the app is locked.",
        "privacy_app_lock_enable_point_face" to
            "Only your screen lock or a strong biometric, like your fingerprint, can unlock the app. " +
            "Basic face unlock isn't used.",
        "privacy_app_lock_enable_point_api30_actions" to
            "Bill reminders won't show Snooze or Dismiss while app lock is on.",
        "privacy_app_lock_enable_continue" to "Continue",
        "privacy_app_lock_snackbar_on" to "App lock is on",
        "privacy_app_lock_snackbar_off" to "App lock is off",
        "privacy_app_lock_not_enabled" to "App lock was not turned on.",
        "privacy_app_lock_enable_failed" to "Couldn't turn on app lock. Try again."
    )

    /** Strings the new UI reuses from other files instead of declaring (ux 9.1 notes), declared in every language. */
    private val reusedKeys = listOf("applock_msg_too_many_attempts", "privacy_app_lock_retry")

    /** The Cancel label the explainer reuses (ux 9.1: `privacy_confirm_cancel`); it is declared in the default file only. */
    private val reusedCancelKey = "privacy_confirm_cancel"

    private val locales = listOf("values", "values-es", "values-el")

    private fun resRoot(): File {
        val root = listOf("src/main/res", "app/src/main/res", "../app/src/main/res")
            .map { File(it) }
            .firstOrNull { it.isDirectory }
        assertTrue("res root not found from ${System.getProperty("user.dir")}", root != null)
        return root!!
    }

    private fun sectionFile(folder: String): File {
        val file = File(resRoot(), "$folder/strings_privacy_section.xml")
        assertTrue("$folder/strings_privacy_section.xml is missing", file.isFile)
        return file
    }

    /** name -> raw (still escaped) value, in document order; duplicates are kept as separate entries. */
    private fun declarations(file: File): List<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).map { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private fun values(folder: String): Map<String, String> = declarations(sectionFile(folder)).toMap()

    private fun unescape(raw: String): String = raw.replace("\\'", "'").replace("\\\"", "\"")

    private val placeholder = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]|\\{[^}]*\\}")

    private fun placeholders(value: String): List<String> = placeholder.findAll(value).map { it.value }.toList().sorted()

    @Test
    fun `there are exactly 24 new keys`() {
        assertEquals(24, newStrings.size)
    }

    @Test
    fun `every new key is declared exactly once in each language file`() {
        locales.forEach { folder ->
            val names = declarations(sectionFile(folder)).map { it.first }
            newStrings.keys.forEach { key ->
                assertEquals("$folder declares $key once", 1, names.count { it == key })
            }
        }
    }

    @Test
    fun `the default file carries the ux 9-1 English copy for every new key`() {
        val actual = values("values")
        newStrings.forEach { (key, expected) ->
            assertEquals("values/$key", expected, unescape(actual.getValue(key)))
        }
    }

    @Test
    fun `es and el carry a non blank text for every new key`() {
        listOf("values-es", "values-el").forEach { folder ->
            val actual = values(folder)
            newStrings.keys.forEach { key ->
                assertTrue("$folder/$key is blank", actual.getValue(key).isNotBlank())
            }
        }
    }

    @Test
    fun `es and el texts differ from the English text, so none is an untranslated fallback`() {
        val english = values("values")
        listOf("values-es", "values-el").forEach { folder ->
            val actual = values(folder)
            newStrings.keys.forEach { key ->
                assertNotEquals("$folder/$key fell back to English", english.getValue(key), actual.getValue(key))
            }
        }
    }

    @Test
    fun `each new key has the same placeholder set in all three languages`() {
        val english = values("values")
        listOf("values-es", "values-el").forEach { folder ->
            val actual = values(folder)
            newStrings.keys.forEach { key ->
                assertEquals(
                    "$folder/$key placeholders",
                    placeholders(english.getValue(key)),
                    placeholders(actual.getValue(key))
                )
            }
        }
    }

    @Test
    fun `the new keys are plain constant text with no format placeholder in any language`() {
        locales.forEach { folder ->
            val actual = values(folder)
            newStrings.keys.forEach { key ->
                val value = actual.getValue(key)
                assertFalse("$folder/$key has a % placeholder", value.contains('%'))
                assertFalse("$folder/$key has a {} placeholder", value.contains('{') || value.contains('}'))
            }
        }
    }

    @Test
    fun `no new value has an unescaped apostrophe or a stray escape that aapt would reject`() {
        locales.forEach { folder ->
            val actual = values(folder)
            newStrings.keys.forEach { key ->
                val value = actual.getValue(key)
                if (!value.startsWith("\"")) {
                    assertFalse(
                        "$folder/$key has an unescaped apostrophe",
                        Regex("(?<!\\\\)'").containsMatchIn(value)
                    )
                }
                assertFalse("$folder/$key starts with @ or ?", value.startsWith("@") || value.startsWith("?"))
            }
        }
    }

    @Test
    fun `the three language files declare the same set of keys`() {
        val reference = declarations(sectionFile("values")).map { it.first }.toSet()
        listOf("values-es", "values-el").forEach { folder ->
            assertEquals("$folder key set", reference, declarations(sectionFile(folder)).map { it.first }.toSet())
        }
    }

    @Test
    fun `the strings the new UI reuses exist in every language`() {
        locales.forEach { folder ->
            val declared = File(resRoot(), folder)
                .listFiles { file -> file.isFile && file.name.endsWith(".xml") }
                .orEmpty()
                .flatMap { declarations(it).map { declaration -> declaration.first } }
                .toSet()
            reusedKeys.forEach { key ->
                assertTrue("$folder does not declare the reused string $key", key in declared)
            }
        }
    }

    @Test
    fun `the reused Cancel label resolves, at least from the default file`() {
        val declared = File(resRoot(), "values")
            .listFiles { file -> file.isFile && file.name.endsWith(".xml") }
            .orEmpty()
            .flatMap { declarations(it).map { declaration -> declaration.first } }
            .toSet()
        assertTrue("values does not declare $reusedCancelKey", reusedCancelKey in declared)
    }
}
