package com.yourname.expensetracker.data.privacy

import com.yourname.expensetracker.data.ai.provider.internal.CloudPiiSanitizer
import com.yourname.expensetracker.domain.common.sha256Prefix
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.InstallationSecretHasher
import com.yourname.expensetracker.domain.privacy.versionedPseudonym
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RP-15 (15-C, D13 remediation) — installation-secret HMAC service tests.
 *
 * AndroidKeyStore is not available on the desktop JVM (see the @Ignore'd
 * SecureKeyStorageTest), so the KEY PROVIDER seam is faked with an in-memory
 * HMAC key store. All SERVICE behavior (restart stability, install isolation,
 * invalidation fail-closed, rotation/versioning, legacy artifacts, parity
 * between the two redaction paths, and the amount/currency corpus) is verified
 * through that seam; the production [AndroidKeystoreInstallationSecretKeyProvider]
 * adds only Keystore mechanics on top of the same contract.
 */
class KeystoreInstallationSecretHashingTest {

    // ── Fakes / fixtures ──────────────────────────────────────────────────────

    /** In-memory stand-in for the Android Keystore: one key per (install, version). */
    private class FakeInstallationSecretKeyProvider : InstallationSecretKeyProvider {
        private val keys = mutableMapOf<Int, SecretKey>()
        var invalidated: Boolean = false
        var throwOnAccess: Boolean = false

        override fun getOrCreateSecretKey(version: Int): SecretKey? {
            if (throwOnAccess) throw IllegalStateException("keystore exploded")
            if (invalidated) return null
            return keys.getOrPut(version) {
                KeyGenerator.getInstance("HmacSHA256").apply { init(256) }.generateKey()
            }
        }
    }

    private fun newHasher(provider: InstallationSecretKeyProvider): InstallationSecretHasher =
        KeystoreInstallationSecretHasher(provider)

    private fun newSanitizer(hasher: InstallationSecretHasher) = CloudPiiSanitizer(hasher)

    private fun newRedactor(hasher: InstallationSecretHasher) =
        DefaultCloudPayloadRedactor(CloudPiiSanitizer(hasher), hasher)

    // ── Generation & determinism ──────────────────────────────────────────────

    @Test
    fun `hmac is deterministic within one installation`() {
        val provider = FakeInstallationSecretKeyProvider()
        val hasher = newHasher(provider)
        assertEquals(hasher.hmacPrefix("STARBUCKS"), hasher.hmacPrefix("STARBUCKS"))
        assertEquals(
            hasher.versionedPseudonym("Aldi Süd"),
            hasher.versionedPseudonym("Aldi Süd")
        )
    }

    @Test
    fun `output is a versioned hex prefix`() {
        val hasher = newHasher(FakeInstallationSecretKeyProvider())
        val pseudonym = hasher.versionedPseudonym("merchant-name")
        assertTrue("must carry the version prefix", pseudonym.startsWith("v${hasher.currentVersion}_"))
        val hex = pseudonym.removePrefix("v${hasher.currentVersion}_")
        assertEquals(InstallationSecretHasher.PSEUDONYM_HEX_LENGTH, hex.length)
        assertTrue("must be lowercase hex", hex.all { it.isDigit() || it in 'a'..'f' })
    }

    // ── Restart stability ─────────────────────────────────────────────────────

    @Test
    fun `pseudonym survives a process restart (new service, persisted key)`() {
        val provider = FakeInstallationSecretKeyProvider() // stands in for the persistent keystore
        val beforeRestart = newHasher(provider).versionedPseudonym("REWE Markt GmbH")
        // A restarted process constructs a NEW service instance over the SAME
        // persisted keystore — the pseudonym must be identical.
        val afterRestart = newHasher(provider).versionedPseudonym("REWE Markt GmbH")
        assertEquals(beforeRestart, afterRestart)
    }

    // ── Cross-install / backup-restore unlinkability ──────────────────────────

    @Test
    fun `distinct installations produce unlinkable pseudonyms`() {
        // Two fresh installs each generate their own random secret; a backup/
        // restore to another device also lands in the "fresh install" case
        // because Android Keystore keys are excluded from backups.
        val installA = newHasher(FakeInstallationSecretKeyProvider())
        val installB = newHasher(FakeInstallationSecretKeyProvider())
        assertNotEquals(
            installA.versionedPseudonym("amazon@orders"),
            installB.versionedPseudonym("amazon@orders")
        )
    }

    // ── Invalidation fail-closed ──────────────────────────────────────────────

    @Test
    fun `invalidated secret fails closed - no hash, identity-free marker`() {
        val provider = FakeInstallationSecretKeyProvider()
        val hasher = newHasher(provider)
        val before = hasher.versionedPseudonym("IKEA")

        provider.invalidated = true // keystore can no longer supply the key
        assertNull(hasher.hmacPrefix("IKEA"))
        assertEquals(
            "must degrade to the identity-free marker",
            "merchant_${InstallationSecretHasher.UNAVAILABLE_MARKER}",
            "merchant_${hasher.versionedPseudonym("IKEA")}"
        )
        assertNotEquals("must NOT keep serving the stale pseudonym", before, hasher.versionedPseudonym("IKEA"))
    }

    @Test
    fun `keystore exception fails closed instead of falling back to a public hash`() {
        val provider = FakeInstallationSecretKeyProvider().apply { throwOnAccess = true }
        val hasher = newHasher(provider)
        assertNull(hasher.hmacPrefix("LIDL"))
        assertEquals(
            InstallationSecretHasher.UNAVAILABLE_MARKER,
            hasher.versionedPseudonym("LIDL")
        )
    }

    @Test
    fun `fail_closed path never emits a public sha`() {
        val provider = FakeInstallationSecretKeyProvider().apply { invalidated = true }
        val hasher = newHasher(provider)
        val pseudonym = hasher.versionedPseudonym("TESCO")
        val publicSha = "TESCO".sha256Prefix()
        assertFalse(pseudonym.contains(publicSha))
        assertEquals(InstallationSecretHasher.UNAVAILABLE_MARKER, pseudonym)
    }

    // ── Rotation / versioning ─────────────────────────────────────────────────

    @Test
    fun `rotation to a new key version changes outputs and the version prefix`() {
        val provider = FakeInstallationSecretKeyProvider()
        val v1 = newHasher(provider)
        val v1Pseudonym = v1.versionedPseudonym("MEDIA MARKT")
        assertTrue(v1Pseudonym.startsWith("v1_"))

        // Rotate: the active version moves to 2 (a release would bump
        // KeystoreInstallationSecretHasher.CURRENT_VERSION; simulated here by
        // resolving through the same provider with a different version).
        val v2Hasher = object : InstallationSecretHasher by v1 {
            override val currentVersion: Int get() = 2
            override fun hmacPrefix(value: String, length: Int): String? {
                val key = provider.getOrCreateSecretKey(2) ?: return null
                return javax.crypto.Mac.getInstance("HmacSHA256").apply { init(key) }
                    .doFinal(value.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                    .take(length)
            }
        }
        val v2Pseudonym = v2Hasher.versionedPseudonym("MEDIA MARKT")
        assertTrue("rotated output must carry the new version prefix", v2Pseudonym.startsWith("v2_"))
        assertNotEquals("rotation must break correlation with old outputs", v1Pseudonym, v2Pseudonym)
        // Old version artifacts remain verifiable under their own version key.
        assertEquals(v1Pseudonym, v1.versionedPseudonym("MEDIA MARKT"))
    }

    @Test
    fun `fail_closed hasher emits markers only`() {
        assertEquals(0, FailClosedInstallationSecretHasher.currentVersion)
        assertNull(FailClosedInstallationSecretHasher.hmacPrefix("anything"))
        assertEquals("redacted", FailClosedInstallationSecretHasher.versionedPseudonym("anything"))
    }

    // ── Parity: redactText and redactMerchant use the SAME service ────────────

    @Test
    fun `redactMerchant and redactText merchant-line hashing produce the same pseudonym`() {
        val hasher = newHasher(FakeInstallationSecretKeyProvider())
        val redactor = newRedactor(hasher)

        val merchantField = redactor.redactMerchant("STARBUCKS COFFEE")
        val briefing = redactor.redactText(
            "Spent 12,50 at STARBUCKS COFFEE today",
            CloudPayloadPurpose.DASHBOARD_BRIEFING
        )

        val merchantPseudonym = requireNotNull(merchantField.value) { "redacted merchant field must carry a pseudonym" } // "merchant_v1_<hex>"
        assertTrue("merchant pseudonym must be versioned", merchantPseudonym.startsWith("merchant_v1_"))
        assertTrue(
            "briefing text must contain the SAME pseudonym (parity)",
            briefing.text.contains(merchantPseudonym)
        )
        assertTrue(merchantField.wasRedacted)
    }

    @Test
    fun `redactMerchant never emits an unsalted sha pseudonym`() {
        val redactor = newRedactor(newHasher(FakeInstallationSecretKeyProvider()))
        val field = redactor.redactMerchant("amazon")
        val pseudonym = requireNotNull(field.value) { "redacted merchant field must carry a pseudonym" }
        assertFalse(pseudonym.contains("amazon".sha256Prefix()))
        assertTrue(pseudonym.startsWith("merchant_v"))
    }

    @Test
    fun `blank_text fallback uses the installation-secret pseudonym`() {
        val hasher = newHasher(FakeInstallationSecretKeyProvider())
        val sanitizer = newSanitizer(hasher)
        val sanitized = sanitizer.sanitizeText(
            raw = "john.doe@example.com", // fully redacted → blank → fallback path
            maxChars = 2000,
            fallbackPrefix = "text"
        )
        assertTrue(sanitized.startsWith("text_v1_"))
        assertFalse(sanitized.contains("john.doe@example.com".sha256Prefix()))
    }

    // ── Legacy artifact treatment ─────────────────────────────────────────────

    @Test
    fun `legacy unsalted artifacts are structurally distinct and never regenerated`() {
        val hasher = newHasher(FakeInstallationSecretKeyProvider())
        val sanitizer = newSanitizer(hasher)
        val legacy = "merchant_${"STARBUCKS".sha256Prefix()}" // pre-remediation artifact
        val modern = sanitizer.sanitizeMerchant("STARBUCKS", shouldRedact = true)
        assertNotEquals(legacy, modern)
        assertTrue("legacy artifacts have no version marker", !legacy.startsWith("merchant_v"))
        assertTrue("modern artifacts carry a version marker", modern.startsWith("merchant_v"))
    }

    // ── Phone floor: currency/amount corpus must not falsely redact ───────────

    @Test
    fun `corpus_amount_and_currency_strings_are_not_falsely_redacted_as_phone`() {
        val sanitizer = newSanitizer(newHasher(FakeInstallationSecretKeyProvider()))
        val amounts = listOf(
            "4.99", "12,50", "12.50", "1.234,56", "1,234.56",
            "1234.56", "12 345,67", "12345.67", "€19,99", "19.99€",
            "\$1,299.00", "EUR 45.90", "45.90 EUR", "GBP 129", "129 GBP",
            "3 x 9,99", "total 89,90", "(-12.34)", "0.01", "999,999.99"
        )
        for (amount in amounts) {
            val sanitized = sanitizer.sanitizeText(
                raw = "Payment of $amount at a shop",
                maxChars = 3000,
                fallbackPrefix = "text"
            )
            assertFalse(
                "amount '$amount' must not be falsely redacted as a phone: $sanitized",
                sanitized.contains("[REDACTED_PHONE]")
            )
        }
    }

    @Test
    fun `corpus_plain_short_numbers_and_ids_are_not_redacted_as_phone`() {
        val sanitizer = newSanitizer(newHasher(FakeInstallationSecretKeyProvider()))
        val harmless = listOf(
            "order 4711", "invoice #2024-001", "card *1234", "pos 08/15",
            "table 7", "100 pcs", "qty 12", "vat 19%", "5-star"
        )
        for (value in harmless) {
            val sanitized = sanitizer.sanitizeText(value, maxChars = 2000, fallbackPrefix = "text")
            assertFalse("'$value' must not be redacted: $sanitized", sanitized.contains("[REDACTED_PHONE]"))
        }
    }

    @Test
    fun `real phone numbers are still redacted`() {
        val sanitizer = newSanitizer(newHasher(FakeInstallationSecretKeyProvider()))
        for (phone in listOf("+49 170 1234567", "+491701234567", "0170 123 45 67", "170-1234-567890")) {
            val sanitized = sanitizer.sanitizeText("call $phone now", maxChars = 2000, fallbackPrefix = "text")
            assertTrue("phone '$phone' must be redacted: $sanitized", sanitized.contains("[REDACTED_PHONE]"))
        }
    }

    // ── Keystore key-provider wiring sanity (JVM-safe assertions) ──────────────

    @Test
    fun `service exposes version and bounded truncation`() {
        val hasher = newHasher(FakeInstallationSecretKeyProvider())
        assertEquals(KeystoreInstallationSecretHasher.CURRENT_VERSION, hasher.currentVersion)
        assertEquals(64, hasher.hmacPrefix("x", length = 64)!!.length)
        assertEquals(1, hasher.hmacPrefix("x", length = 1)!!.length)
        // Out-of-range lengths are clamped into the 1..64 window (never null,
        // never longer than a full digest).
        assertEquals(1, hasher.hmacPrefix("x", length = 0)!!.length)
        assertEquals(64, hasher.hmacPrefix("x", length = 65)!!.length)
    }

    @Test
    fun `keystore_provider_class_exists_with_expected_contract`() {
        // The production provider is Keystore-backed and cannot run on the JVM;
        // assert the wiring type contract instead of instantiating it.
        assertEquals(
            InstallationSecretKeyProvider::class,
            AndroidKeystoreInstallationSecretKeyProvider().javaClass.interfaces.single()
        )
        assertNotNull(KeyStore.getDefaultType())
    }
}
