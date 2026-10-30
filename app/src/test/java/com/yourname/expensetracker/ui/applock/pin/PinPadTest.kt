package com.yourname.expensetracker.ui.applock.pin

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.yourname.expensetracker.architecture.SourceTextSanitizer
import com.yourname.expensetracker.domain.privacy.applock.pin.PinFormatPolicy
import com.yourname.expensetracker.ui.applock.AppLockComposeTestBase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** INV-38 / AE-119: PIN digits live only in a zero-filled CharArray; the pad exposes only a digit count. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PinPadTest : AppLockComposeTestBase() {

    private fun backing(buffer: PinEntryBuffer): CharArray {
        val field = PinEntryBuffer::class.java.getDeclaredField("chars")
        field.isAccessible = true
        return field.get(buffer) as CharArray
    }

    private fun allZero(a: CharArray) = a.all { it == '\u0000' }

    @Test
    fun inv38_append_acceptsDigitsOnly() {
        val b = PinEntryBuffer()
        assertTrue(b.append('0'))
        assertTrue(b.append('9'))
        assertFalse(b.append('a'))
        assertFalse(b.append(' '))
        assertFalse(b.append('٥')) // Arabic-Indic digit is not an ASCII digit
        assertEquals(2, b.length)
    }

    @Test
    fun inv38_append_rejectsBeyondCapacity() {
        val b = PinEntryBuffer()
        repeat(PinFormatPolicy.MAX_LENGTH) { assertTrue(b.append('1')) }
        assertFalse(b.append('2'))
        assertEquals(PinFormatPolicy.MAX_LENGTH, b.length)
        assertTrue(backing(b).all { it == '1' })
    }

    @Test
    fun inv38_deleteLast_zeroFillsRemovedSlot_andIsNoOpWhenEmpty() {
        val b = PinEntryBuffer()
        b.deleteLast()
        assertEquals(0, b.length)
        b.append('4'); b.append('7')
        b.deleteLast()
        assertEquals(1, b.length)
        assertEquals('4', backing(b)[0])
        assertEquals('\u0000', backing(b)[1])
    }

    @Test
    fun inv38_clear_zeroFillsAll() {
        val b = PinEntryBuffer()
        "123456".forEach { b.append(it) }
        b.clear()
        assertEquals(0, b.length)
        assertTrue(allZero(backing(b)))
    }

    @Test
    fun inv38_consumeCopy_returnsDigits_thenBufferIsEmptyAndZeroed() {
        val b = PinEntryBuffer()
        "135790".forEach { b.append(it) }
        val copy = b.consumeCopy()
        assertArrayEquals("135790".toCharArray(), copy)
        assertEquals(0, b.length)
        assertTrue(allZero(backing(b)))
        assertEquals(0, b.consumeCopy().size)
    }

    // ---- AE-119 source pin ----

    private val pinPadSource: String by lazy {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory } ?: error("production source root not found")
        val file = File(root, "com/yourname/expensetracker/ui/applock/pin/PinPad.kt")
        assertTrue("missing PinPad.kt", file.isFile)
        SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    private val forbidden = listOf(
        "rememberSaveable", "SavedStateHandle", "Bundle", "concatToString", "joinToString",
        "buffer.toString", "chars.toString", "Log.", "Timber."
    )

    @Test
    fun ae119_pinPadSource_neverConvertsBufferToStringOrPersistsOrLogs() {
        assertEquals(emptyList<String>(), forbidden.filter { pinPadSource.contains(it) })
        assertFalse("String( constructor", Regex("""\bString\s*\(""").containsMatchIn(pinPadSource))
        // The only toString() allowed is on the single key Char `d`.
        val bad = Regex("""(\w+)\.toString\(\)""").findAll(pinPadSource).map { it.groupValues[1] }.filter { it != "d" }
        assertEquals(emptyList<String>(), bad.toList())
    }

    @Test
    fun ae119_scan_selfCheck_detectsPlantedViolation() {
        val planted = SourceTextSanitizer.stripCommentsAndStringBodies("val x = rememberSaveable { buffer.toString() }")
        assertTrue(forbidden.any { planted.contains(it) })
    }

    // ---- Compose behaviour ----

    private val buffer = PinEntryBuffer()
    private val padEnabled = androidx.compose.runtime.mutableStateOf(true)
    private var confirmed: CharArray? = null
    private var confirmCalls = 0
    private var fingerprintCalls = 0

    private fun show(showFingerprint: Boolean = false) {
        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                PinDotRow(count = buffer.length)
                PinPad(
                    buffer = buffer,
                    enabled = padEnabled.value,
                    onConfirm = { confirmed = it; confirmCalls++ },
                    showFingerprintKey = showFingerprint,
                    onFingerprint = { fingerprintCalls++ }
                )
            }
        }
    }

    private fun press(vararg digits: Char) =
        digits.forEach { composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + it).performClick() }

    private fun dotRowDescription(): String? = composeRule.onNodeWithTag(PinPadTags.DOT_ROW)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun inv38_dotRow_announcesPluralDigitCount_andNoDigitText() {
        show()
        assertEquals("0 digits entered", dotRowDescription())
        press('9')
        assertEquals("1 digit entered", dotRowDescription())
        press('8', '7')
        assertEquals("3 digits entered", dotRowDescription())
        val config = composeRule.onNodeWithTag(PinPadTags.DOT_ROW).fetchSemanticsNode().config
        assertNull(config.getOrNull(SemanticsProperties.Text))
        assertNull(config.getOrNull(SemanticsProperties.EditableText))
        assertFalse(dotRowDescription()!!.contains("987"))
    }

    @Test
    fun inv38_confirm_disabledBelowMinLength_enabledAtMinLength() {
        show()
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).assertIsNotEnabled()
        press('1', '3', '5', '7', '9')
        assertEquals(PinFormatPolicy.MIN_LENGTH - 1, buffer.length)
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).assertIsNotEnabled()
        press('0')
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).assertIsEnabled()
        composeRule.onNodeWithTag(PinPadTags.KEY_DELETE).performClick()
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).assertIsNotEnabled()
        assertEquals(0, confirmCalls)
    }

    @Test
    fun inv38_confirm_passesDigitCopy_andEmptiesBuffer() {
        show()
        press('2', '4', '6', '8', '0', '1')
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).performClick()
        assertEquals(1, confirmCalls)
        assertArrayEquals("246801".toCharArray(), confirmed)
        assertEquals(0, buffer.length)
        assertTrue(allZero(backing(buffer)))
        assertEquals("0 digits entered", dotRowDescription())
    }

    @Test
    fun inv38_disabledPad_disablesKeys_andClearsBuffer() {
        show()
        press('1', '2', '3')
        assertEquals(3, buffer.length)
        padEnabled.value = false
        composeRule.waitForIdle()
        assertEquals(0, buffer.length)
        assertTrue(allZero(backing(buffer)))
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "5").assertIsNotEnabled()
        composeRule.onNodeWithTag(PinPadTags.KEY_DELETE).assertIsNotEnabled()
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun inv38_fingerprintKey_hiddenByDefault_shownAndCalledWhenRequested() {
        show(showFingerprint = false)
        composeRule.onAllNodesWithTag(PinPadTags.KEY_FINGERPRINT).assertCountEquals(0)
    }

    @Test
    fun inv38_fingerprintKey_clickInvokesCallback() {
        show(showFingerprint = true)
        composeRule.onNodeWithTag(PinPadTags.KEY_FINGERPRINT).performClick()
        assertEquals(1, fingerprintCalls)
        assertEquals(0, buffer.length)
    }

    private fun showWithFingerprintEnabled(fingerprintEnabled: Boolean) {
        composeRule.setContent {
            PinPad(
                buffer = buffer,
                enabled = false, // digit keys disabled (e.g. a PIN delay is active)
                onConfirm = { confirmCalls++ },
                showFingerprintKey = true,
                onFingerprint = { fingerprintCalls++ },
                fingerprintEnabled = fingerprintEnabled
            )
        }
    }

    @Test
    fun s27_fingerprintEnabled_true_keyEnabledAndClickable_whileDigitKeysDisabled() {
        showWithFingerprintEnabled(true)
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "5").assertIsNotEnabled()
        composeRule.onNodeWithTag(PinPadTags.KEY_FINGERPRINT).assertIsEnabled()
        composeRule.onNodeWithTag(PinPadTags.KEY_FINGERPRINT).performClick()
        assertEquals(1, fingerprintCalls)
    }

    @Test
    fun s27_fingerprintEnabled_false_keyDisabled_andNotClickable() {
        showWithFingerprintEnabled(false)
        composeRule.onNodeWithTag(PinPadTags.KEY_FINGERPRINT).assertIsNotEnabled()
        composeRule.onNodeWithTag(PinPadTags.KEY_FINGERPRINT).performClick()
        assertEquals(0, fingerprintCalls)
    }

    @Test
    fun s27_rememberPinEntryBuffer_clearsDigitsOnLifecycleStop() {
        var remembered: PinEntryBuffer? = null
        var owner: androidx.lifecycle.LifecycleOwner? = null
        composeRule.setContent {
            val b = rememberPinEntryBuffer()
            remembered = b
            owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            PinPad(buffer = b, enabled = true, onConfirm = { })
        }
        press('1', '2', '3')
        assertEquals(3, remembered!!.length) // control: digits are held while the screen is started
        val registry = owner!!.lifecycle as androidx.lifecycle.LifecycleRegistry
        composeRule.runOnUiThread { registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        composeRule.waitForIdle()
        assertEquals(0, remembered!!.length)
        assertTrue(allZero(backing(remembered!!)))
    }
}
