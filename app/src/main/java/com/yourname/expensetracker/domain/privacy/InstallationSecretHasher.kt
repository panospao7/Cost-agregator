package com.yourname.expensetracker.domain.privacy

/**
 * RP-15 (15-C, D13 remediation): HMAC over a Keystore-backed installation secret.
 *
 * Replaces public (unsalted) SHA-256 pseudonyms for merchant identity and
 * blank-text fallbacks in the cloud redaction paths. Exactly ONE implementation
 * is bound in production and BOTH redaction entry points
 * ([com.yourname.expensetracker.domain.privacy.CloudPayloadRedactor.redactText]
 * and `redactMerchant`/`CloudPiiSanitizer`) MUST use this same service.
 *
 * ## Properties
 *
 * - **Secret generation** — the secret is a random Android-Keystore-generated
 *   HMAC key (never derived from public constants), created on first use and
 *   never extractable from the keystore.
 * - **Restart stability** — the Keystore key persists across process restarts,
 *   so the same input yields the same HMAC for the lifetime of the install.
 * - **Cross-install unlinkability / backup-restore** — Android Keystore keys are
 *   device-bound and excluded from backups. After a restore on another device
 *   (or a fresh install) a NEW key is generated, so hashes produced before and
 *   after cannot be correlated across installs.
 * - **Invalidation fail-closed** — if the Keystore cannot provide or use the
 *   secret (key invalidated, hardware error), [hmacPrefix] returns `null`.
 *   Callers MUST then emit an identity-free marker (e.g. `merchant_redacted`)
 *   and MUST NOT fall back to a public hash — no public-salt/SHA fallback exists.
 * - **Rotation/versioning** — [currentVersion] selects the active key alias.
 *   Bumping the version rotates to a fresh key; emitted artifacts carry a
 *   `v<version>_` prefix so outputs from different key generations are
 *   distinguishable and old generations can be retired deliberately.
 * - **Legacy artifacts** — pre-remediation artifacts of the form
 *   `merchant_<unsalted-sha-hex>` (and bare `<unsalted-sha-hex>` fallbacks) are
 *   treated as opaque legacy values: they are never regenerated, never matched
 *   against new HMAC outputs, and require no migration because the redaction
 *   pseudonyms are ephemeral per cloud request (never persisted in the app DB).
 */
interface InstallationSecretHasher {
    /** Version of the active installation secret (key-generation counter). */
    val currentVersion: Int

    /**
     * HMAC-SHA-256 over [value] keyed by the installation secret, truncated to
     * [length] lowercase hex chars. Returns `null` when the secret is
     * unavailable or invalidated (fail closed — no public-hash fallback).
     */
    fun hmacPrefix(value: String, length: Int = PSEUDONYM_HEX_LENGTH): String?

    companion object {
        /** Default truncation for identity pseudonyms. */
        const val PSEUDONYM_HEX_LENGTH = 16

        /** Marker emitted instead of a pseudonym when the secret is unavailable. */
        const val UNAVAILABLE_MARKER = "redacted"
    }
}

/**
 * Formats a versioned identity pseudonym (`v<version>_<hex>`), or the
 * identity-free fail-closed marker when [hash] is null. Shared by all 15-C
 * call sites so the format cannot drift between redaction paths.
 */
fun InstallationSecretHasher.versionedPseudonym(value: String): String {
    val hex = hmacPrefix(value) ?: return InstallationSecretHasher.UNAVAILABLE_MARKER
    return "v$currentVersion" + "_" + hex
}
