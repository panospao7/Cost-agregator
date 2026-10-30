package com.yourname.expensetracker.data.privacy.applock.pin

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * G26-5 pin (source-scan style; WEAKER than a behavioural test).
 *
 * [AndroidBiometricPinKeyManager] has no seam to inject the KeyStore, and AndroidKeyStore is unavailable on the
 * JVM, so the exception mapping of `cipherForAuthentication` is pinned on the comment-stripped source. The real
 * behaviour is covered by device check G2 (invalidate the key by enrolling a fingerprint, then unlock).
 */
class AndroidBiometricPinKeyManagerTest {

    private val sanitized: String by lazy {
        val relative = "com/yourname/expensetracker/data/privacy/applock/pin/BiometricPinKeyManager.kt"
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        val root = roots.firstOrNull { it.isDirectory } ?: error("production source root not found")
        val file = File(root, relative)
        assertTrue("missing $relative", file.isFile)
        SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    private fun cipherFunctionBody(): String {
        val start = sanitized.indexOf("override fun cipherForAuthentication()")
        assertTrue("cipherForAuthentication not found", start >= 0)
        val end = sanitized.indexOf("override fun verifyAuthenticatedCrypto", start)
        assertTrue("verifyAuthenticatedCrypto not found", end > start)
        return sanitized.substring(start, end)
    }

    private fun catchMapping(body: String, exception: String): String? =
        Regex("""catch\s*\(\s*\w+\s*:\s*$exception\s*\)\s*\{\s*([^}]*?)\s*\}""").find(body)?.groupValues?.get(1)?.trim()

    @Test
    fun g26_5_unrecoverableKeyException_mapsToKeyInvalidated() {
        assertEquals("BiometricKeyResult.KeyInvalidated", catchMapping(cipherFunctionBody(), "UnrecoverableKeyException"))
    }

    @Test
    fun g26_5_keyPermanentlyInvalidatedException_mapsToKeyInvalidated() {
        assertEquals("BiometricKeyResult.KeyInvalidated", catchMapping(cipherFunctionBody(), "KeyPermanentlyInvalidatedException"))
    }

    @Test
    fun g26_5_genericException_stillMapsToKeystoreError_afterTheSpecificCatches() {
        val body = cipherFunctionBody()
        assertEquals("BiometricKeyResult.KeystoreError", catchMapping(body, "Exception"))
        val cancel = body.indexOf("catch (e: CancellationException)")
        val unrecoverable = body.indexOf("catch (e: UnrecoverableKeyException)")
        val invalidated = body.indexOf("catch (e: KeyPermanentlyInvalidatedException)")
        val generic = body.indexOf("catch (e: Exception)")
        assertTrue("catch blocks missing", listOf(cancel, unrecoverable, invalidated, generic).all { it >= 0 })
        assertTrue("CancellationException must be rethrown first", cancel < unrecoverable && cancel < invalidated)
        assertTrue("specific catches must precede the generic one", unrecoverable < generic && invalidated < generic)
        assertTrue("CancellationException must be rethrown", Regex("""CancellationException\s*\)\s*\{\s*throw\s+e""").containsMatchIn(body))
    }

    @Test
    fun g26_5_selfCheck_mappingScannerFailsOnAWrongMapping() {
        val planted = SourceTextSanitizer.stripCommentsAndStringBodies(
            """
            } catch (e: UnrecoverableKeyException) {
                BiometricKeyResult.KeystoreError
            }
            """.trimIndent()
        )
        assertEquals("BiometricKeyResult.KeystoreError", catchMapping(planted, "UnrecoverableKeyException"))
        assertTrue(catchMapping(planted, "UnrecoverableKeyException") != "BiometricKeyResult.KeyInvalidated")
    }
}
