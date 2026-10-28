package com.yourname.expensetracker.ui.applock

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.privacy.applock.APP_LOCK_ALLOWED_AUTHENTICATORS
import com.yourname.expensetracker.domain.privacy.applock.AuthPromptCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * S15, INV-53 (constant, resource-backed prompt text; no user data), INV-23 (authenticator
 * combination), AE-114 (DISABLE confirm uses the disable title).
 *
 * No Activity is needed: [buildPromptInfo] only needs a Context, so no FragmentActivity has to be
 * registered with the package manager and the test is identical in the debug and release variants.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricPromptCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val promptStringIds: List<Int> = listOf(
        R.string.applock_prompt_title_enable,
        R.string.applock_prompt_subtitle_enable,
        R.string.applock_prompt_title_disable,
        R.string.applock_prompt_title_change,
        R.string.applock_prompt_title_unlock
    )

    private fun localized(languageTag: String): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(languageTag))
        return context.createConfigurationContext(configuration)
    }

    private fun text(@StringRes id: Int, ctx: Context = context): String = ctx.getString(id)

    // ---- title per copy --------------------------------------------------------------------

    @Test
    fun inv53_titleResource_perCopy() {
        assertEquals(R.string.applock_prompt_title_unlock, promptTitleRes(AuthPromptCopy.UNLOCK))
        assertEquals(R.string.applock_prompt_title_enable, promptTitleRes(AuthPromptCopy.ENABLE))
        assertEquals(R.string.applock_prompt_title_disable, promptTitleRes(AuthPromptCopy.DISABLE))
        assertEquals(R.string.applock_prompt_title_change, promptTitleRes(AuthPromptCopy.CHANGE))
    }

    @Test
    fun inv53_promptInfoTitle_isTheResourceTextForEveryCopy() {
        for (copy in AuthPromptCopy.values()) {
            val info = buildPromptInfo(context, copy)
            assertEquals("copy $copy", text(promptTitleRes(copy)), info.title.toString())
        }
    }

    @Test
    fun inv53_titles_areDistinctPerCopy() {
        val titles = AuthPromptCopy.values().map { buildPromptInfo(context, it).title.toString() }

        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun ae114_disable_usesTheDisableTitle() {
        val info = buildPromptInfo(context, AuthPromptCopy.DISABLE)

        assertEquals(text(R.string.applock_prompt_title_disable), info.title.toString())
        assertNotEquals(text(R.string.applock_prompt_title_enable), info.title.toString())
        assertNotEquals(text(R.string.applock_prompt_title_change), info.title.toString())
        assertNotEquals(text(R.string.applock_prompt_title_unlock), info.title.toString())
    }

    // ---- subtitle: ENABLE only -------------------------------------------------------------

    @Test
    fun inv53_subtitleResource_isPresentForEnableOnly() {
        assertEquals(R.string.applock_prompt_subtitle_enable, promptSubtitleRes(AuthPromptCopy.ENABLE))
        assertNull(promptSubtitleRes(AuthPromptCopy.UNLOCK))
        assertNull(promptSubtitleRes(AuthPromptCopy.DISABLE))
        assertNull(promptSubtitleRes(AuthPromptCopy.CHANGE))
    }

    @Test
    fun inv53_enableSubtitle_isSetOnThePrompt() {
        val subtitle: CharSequence? = buildPromptInfo(context, AuthPromptCopy.ENABLE).subtitle

        assertEquals(text(R.string.applock_prompt_subtitle_enable), subtitle?.toString())
    }

    @Test
    fun inv53_otherCopies_haveNoSubtitle() {
        for (copy in listOf(AuthPromptCopy.UNLOCK, AuthPromptCopy.DISABLE, AuthPromptCopy.CHANGE)) {
            val subtitle: CharSequence? = buildPromptInfo(context, copy).subtitle
            assertTrue("copy $copy subtitle=$subtitle", subtitle.isNullOrEmpty())
        }
    }

    // ---- no description, no negative button -------------------------------------------------

    @Test
    fun inv53_noCopy_hasADescription() {
        for (copy in AuthPromptCopy.values()) {
            val description: CharSequence? = buildPromptInfo(context, copy).description
            assertTrue("copy $copy description=$description", description.isNullOrEmpty())
        }
    }

    @Test
    fun inv53_noCopy_hasANegativeButton() {
        for (copy in AuthPromptCopy.values()) {
            val negative: CharSequence? = buildPromptInfo(context, copy).negativeButtonText
            assertTrue("copy $copy negative=$negative", negative.isNullOrEmpty())
        }
    }

    // ---- authenticators --------------------------------------------------------------------

    @Test
    fun inv23_promptInfo_allowsStrongOrDeviceCredentialForEveryCopy() {
        for (copy in AuthPromptCopy.values()) {
            val info = buildPromptInfo(context, copy)
            assertEquals("copy $copy", BIOMETRIC_STRONG or DEVICE_CREDENTIAL, info.allowedAuthenticators)
            assertEquals("copy $copy", 0x800F, info.allowedAuthenticators)
        }
    }

    @Test
    fun inv23_promptInfo_neverAllowsBiometricWeak() {
        val weakOnlyBits = BIOMETRIC_WEAK and BIOMETRIC_STRONG.inv()
        assertTrue(weakOnlyBits != 0)
        for (copy in AuthPromptCopy.values()) {
            val allowed = buildPromptInfo(context, copy).allowedAuthenticators
            assertEquals("copy $copy", 0, allowed and weakOnlyBits)
            // BIOMETRIC_STRONG is a subset of BIOMETRIC_WEAK: masking with WEAK leaves exactly STRONG.
            assertEquals("copy $copy", BIOMETRIC_STRONG, allowed and BIOMETRIC_WEAK)
            assertEquals("copy $copy", 0x800F, allowed)
        }
    }

    @Test
    fun inv23_promptInfo_includesDeviceCredentialBit() {
        for (copy in AuthPromptCopy.values()) {
            val allowed = buildPromptInfo(context, copy).allowedAuthenticators
            assertEquals("copy $copy", DEVICE_CREDENTIAL, allowed and DEVICE_CREDENTIAL)
        }
    }

    @Test
    fun inv23_bothCopiesOfTheAuthenticatorConstant_areEqual() {
        assertEquals(APP_LOCK_ALLOWED_AUTHENTICATORS, PROMPT_ALLOWED_AUTHENTICATORS)
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, PROMPT_ALLOWED_AUTHENTICATORS)
        assertEquals(0x800F, PROMPT_ALLOWED_AUTHENTICATORS)
    }

    @Test
    fun inv23_uiConstant_hasNoBiometricWeakBit() {
        val weakOnlyBits = BIOMETRIC_WEAK and BIOMETRIC_STRONG.inv()
        assertEquals(0, PROMPT_ALLOWED_AUTHENTICATORS and weakOnlyBits)
        assertEquals(BIOMETRIC_STRONG, PROMPT_ALLOWED_AUTHENTICATORS and BIOMETRIC_WEAK)
        assertEquals(0x800F, PROMPT_ALLOWED_AUTHENTICATORS)
    }

    // ---- resources: all five ids in values, values-es, values-el ----------------------------

    @Test
    fun inv53_defaultLocale_allFiveStringsAreNonBlankAndPlaceholderFree() {
        assertEquals(5, promptStringIds.size)
        for (id in promptStringIds) {
            assertNoPlaceholder(text(id), "default id=$id")
        }
    }

    @Test
    fun inv53_spanish_allFiveStringsExistAndDifferFromDefault() {
        assertLocalized("es")
    }

    @Test
    fun inv53_greek_allFiveStringsExistAndDifferFromDefault() {
        assertLocalized("el")
    }

    @Test
    fun inv53_spanishAndGreek_titlesStayDistinctPerCopy() {
        for (tag in listOf("es", "el")) {
            val ctx = localized(tag)
            val titles = AuthPromptCopy.values().map { text(promptTitleRes(it), ctx) }
            assertEquals("locale $tag", titles.size, titles.toSet().size)
        }
    }

    @Test
    fun ae114_disable_titleIsLocalizedInSpanishAndGreek() {
        val defaultTitle = text(R.string.applock_prompt_title_disable)
        for (tag in listOf("es", "el")) {
            val localizedTitle = text(promptTitleRes(AuthPromptCopy.DISABLE), localized(tag))
            assertNotEquals("locale $tag", defaultTitle, localizedTitle)
        }
    }

    private fun assertLocalized(tag: String) {
        val ctx = localized(tag)
        for (id in promptStringIds) {
            val value = text(id, ctx)
            assertNoPlaceholder(value, "locale $tag id=$id")
            // Differing from the default text proves the locale file supplied it, not the fallback.
            assertNotEquals("locale $tag id=$id fell back to default", text(id), value)
        }
    }

    private fun assertNoPlaceholder(value: String, label: String) {
        assertTrue("$label is blank", value.isNotBlank())
        assertFalse("$label has a % placeholder", value.contains('%'))
        assertFalse("$label has a {} placeholder", value.contains('{') || value.contains('}'))
    }
}
