package com.yourname.expensetracker.data.privacy

import com.yourname.expensetracker.testfixtures.backup.TEST_PBKDF2_ITERATIONS
import com.yourname.expensetracker.testfixtures.backup.lowIterationBackupEncryptionService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Tests for [BackupEncryptionService].
 *
 * Validates AES-256-GCM encrypt/decrypt roundtrip and integrity checks.
 */
class BackupEncryptionServiceTest {

    /**
     * Low-iteration instance (test-only seam) for tests that encrypt and decrypt through the
     * same instance and do not depend on the production KDF cost. The production iteration
     * count is pinned separately below.
     */
    private val service = lowIterationBackupEncryptionService()

    /** The real production instance (600,000 PBKDF2 iterations). */
    private val productionService = BackupEncryptionService()

    @Test
    fun `encrypt then decrypt returns original data`() {
        val original = "Hello, ExpenseTracker Backup!".toByteArray(StandardCharsets.UTF_8)
        val password = "my-secure-p@ssword-123"

        val encrypted = service.encrypt(original, password)
        val decrypted = service.decrypt(encrypted, password)

        assertArrayEquals("Roundtrip should restore original data", original, decrypted)
    }

    @Test
    fun `encrypt then decrypt with binary data`() {
        val original = ByteArray(256) { it.toByte() }
        val password = "binary-test-password"

        val encrypted = service.encrypt(original, password)
        val decrypted = service.decrypt(encrypted, password)

        assertArrayEquals("Binary roundtrip should restore original data", original, decrypted)
    }

    @Test
    fun `decrypt with wrong password throws`() {
        val original = "Secret data".toByteArray(StandardCharsets.UTF_8)

        val encrypted = productionService.encrypt(original, "correct-password")

        assertThrows("Wrong password should throw AEADBadTagException", javax.crypto.AEADBadTagException::class.java) {
            productionService.decrypt(encrypted, "wrong-password")
        }
    }

    @Test
    fun `encrypt produces different output each time due to random salt and iv`() {
        val data = "Deterministic input".toByteArray(StandardCharsets.UTF_8)
        val password = "same-password"

        val encrypted1 = service.encrypt(data, password)
        val encrypted2 = service.encrypt(data, password)

        // Salt (16) + IV (12) differ, so first 28 bytes must differ
        val saltIv1 = encrypted1.copyOfRange(0, 28)
        val saltIv2 = encrypted2.copyOfRange(0, 28)
        assertFalse("Salts+IVs should be different (randomised)", saltIv1 contentEquals saltIv2)
    }

    @Test
    fun `decrypt with corrupted ciphertext throws`() {
        val original = "Important data".toByteArray(StandardCharsets.UTF_8)
        val password = "strong-password"

        val encrypted = service.encrypt(original, password)
        // Corrupt the ciphertext (byte after salt+IV)
        encrypted[28] = (encrypted[28].toInt() xor 0xFF).toByte()

        assertThrows("Corrupted ciphertext should throw", javax.crypto.AEADBadTagException::class.java) {
            service.decrypt(encrypted, password)
        }
    }

    // ── RP-03A: legacy-envelope compatibility pins ────────────────
    // These tests pin the CURRENT (legacy) envelope layout — salt(16) + IV(12) +
    // AES-256-GCM ciphertext/tag, PBKDF2-HMAC-SHA256 — so the future versioned-KDF
    // envelope batch cannot silently break legacy reads. Most of them run on the
    // low-iteration seam instance and therefore do NOT exercise the 600k count; the
    // 600k pins live in `legacy envelope KDF parameters are pinned` (constant pin) and
    // `production instance uses 600000 PBKDF2 iterations` (instance pin).

    @Test
    fun `legacy envelope layout is salt16 iv12 then ciphertext with embedded tag`() {
        val original = ByteArray(1000) { (it % 251).toByte() }
        val encrypted = service.encrypt(original, "layout-pin-password")

        // salt (16) + IV (12) + ciphertext (plaintext + 16-byte GCM tag)
        assertEquals(16 + 12 + original.size + 16, encrypted.size)
    }

    @Test
    fun `legacy envelope KDF parameters are pinned`() {
        // Direct constant pins so an accidental KDF change (iterations/salt/IV/
        // key length) fails loudly here before it ships.
        assertEquals(600_000, BackupEncryptionService.ITERATION_COUNT)
        assertEquals(16, BackupEncryptionService.SALT_LENGTH_BYTES)
        assertEquals(12, BackupEncryptionService.IV_LENGTH_BYTES)
        assertEquals(256, BackupEncryptionService.KEY_LENGTH_BITS)
    }

    @Test
    fun `legacy envelope vector roundtrips through the streaming decrypt path`() {
        // CostbackupBundle.extract reads bundles through decryptStream — a legacy
        // vector must survive that exact path, not just the byte-array decrypt. Runs on the
        // low-iteration seam instance (layout/path coverage only); the 600k count is pinned
        // by the constant-pin and production-instance tests.
        val original = ByteArray(4096) { (it % 199).toByte() }
        val password = "legacy-vector-password"

        val encrypted = service.encrypt(original, password)
        val decrypted = service.decryptStream(
            java.io.ByteArrayInputStream(encrypted),
            password
        ).use { it.readBytes() }

        assertArrayEquals("legacy vector must roundtrip via decryptStream", original, decrypted)
    }

    @Test
    fun `legacy envelope rejects input shorter than salt plus iv`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.decrypt(ByteArray(27), "any-password")
        }
    }

    @Test
    fun `legacy envelope decryptStream rejects truncated salt`() {
        assertThrows(IllegalStateException::class.java) {
            service.decryptStream(java.io.ByteArrayInputStream(ByteArray(15)), "any-password")
        }
    }

    @Test
    fun `legacy envelope decryptStream rejects truncated iv`() {
        // Salt present (16 bytes) but fewer than 12 IV bytes.
        assertThrows(IllegalStateException::class.java) {
            service.decryptStream(java.io.ByteArrayInputStream(ByteArray(20)), "any-password")
        }
    }

    @Test
    fun `legacy envelope rejects salt iv only with no ciphertext or tag`() {
        val encrypted = service.encrypt("payload".toByteArray(), "pin-password")
        val headOnly = encrypted.copyOfRange(0, 28)

        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            service.decrypt(headOnly, "pin-password")
        }
    }

    @Test
    fun `legacy envelope rejects a truncated ciphertext tail`() {
        val encrypted = service.encrypt("truncation payload".toByteArray(), "pin-password")
        val truncated = encrypted.copyOfRange(0, encrypted.size - 5)

        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            service.decrypt(truncated, "pin-password")
        }
    }

    @Test
    fun `legacy envelope streaming path fails on wrong password`() {
        val encrypted = service.encrypt("wrong password payload".toByteArray(), "correct-password")
        val stream = service.decryptStream(java.io.ByteArrayInputStream(encrypted), "wrong-password")

        // The GCM tag is verified when the stream is consumed/finalised; the
        // failure surfaces either directly or wrapped in an IOException.
        val thrown = runCatching { stream.use { it.readBytes() } }.exceptionOrNull()
        val isBadTag = thrown is javax.crypto.AEADBadTagException ||
            (thrown as? java.io.IOException)?.cause is javax.crypto.AEADBadTagException
        assertTrue(
            "expected AEADBadTagException (possibly IOException-wrapped), got: $thrown",
            isBadTag
        )
    }

    // ── B4-K5: iteration-count seam contracts ─────────────────────
    // The low-iteration constructor is a test-only seam. These tests prove the production
    // instance keeps the pinned count, that the count is never inferred from ciphertext (so
    // there is no downgrade path), and that production code can never reach the seam.

    @Test
    fun `production instance uses 600000 PBKDF2 iterations`() {
        assertEquals(600_000, BackupEncryptionService().iterationCount)
        assertEquals(BackupEncryptionService.ITERATION_COUNT, BackupEncryptionService().iterationCount)

        // Control: the seam instance used by the fast tests really is lower.
        assertEquals(TEST_PBKDF2_ITERATIONS, service.iterationCount)
        assertTrue(
            "the low-iteration test count must stay below the production count",
            TEST_PBKDF2_ITERATIONS < BackupEncryptionService.ITERATION_COUNT
        )
    }

    @Test
    fun `iteration count is never inferred from ciphertext`() {
        val plaintext = "iteration count provenance".toByteArray(StandardCharsets.UTF_8)
        val password = "same-password-for-both-counts"

        val lowCiphertext = service.encrypt(plaintext, password)
        val productionCiphertext = productionService.encrypt(plaintext, password)

        // Controls: each envelope is valid for an instance with ITS OWN iteration count
        // (a second low instance proves the count, not instance identity, is what matters).
        assertArrayEquals(
            plaintext,
            lowIterationBackupEncryptionService().decrypt(lowCiphertext, password)
        )
        assertArrayEquals(plaintext, productionService.decrypt(productionCiphertext, password))

        // Boundary: same password, different iteration count -> authentication failure in
        // BOTH directions, so decrypt never adapts to a count carried by the data.
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            productionService.decrypt(lowCiphertext, password)
        }
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            service.decrypt(productionCiphertext, password)
        }

        // Streaming API, low -> production direction: the streaming decrypt path must not
        // adapt to the count either. Same failure shape as the streaming wrong-password test
        // (AEADBadTagException, possibly IOException-wrapped, surfaced on consumption).
        // Costs one extra production derivation (the productionService.decryptStream call).
        val plaintextFile = java.io.File.createTempFile("iteration-count-provenance", ".bin")
        try {
            plaintextFile.writeBytes(plaintext)
            val streamed = java.io.ByteArrayOutputStream()
            service.encrypt(plaintextFile, streamed, password)

            val stream = productionService.decryptStream(
                java.io.ByteArrayInputStream(streamed.toByteArray()),
                password
            )
            val thrown = runCatching { stream.use { it.readBytes() } }.exceptionOrNull()
            val isBadTag = thrown is javax.crypto.AEADBadTagException ||
                (thrown as? java.io.IOException)?.cause is javax.crypto.AEADBadTagException
            assertTrue(
                "expected AEADBadTagException (possibly IOException-wrapped) for streaming " +
                    "cross-count decrypt, got: $thrown",
                isBadTag
            )
        } finally {
            plaintextFile.delete()
        }
    }

    // A constructor call WITH an argument (BackupEncryptionService(<something>)) is the seam;
    // production must only use the no-arg constructor. `\s` spans newlines, so a call whose
    // argument starts on a following line is still caught.
    private val seamArgumentCall = Regex("""BackupEncryptionService\s*\(\s*[^)\s]""")
    private val seamNoArgCall = Regex("""BackupEncryptionService\s*\(\s*\)""")

    // Constructor references bypass the call-shaped regexes above:
    // Kotlin `::BackupEncryptionService`, Java `BackupEncryptionService::new`.
    private val seamKotlinConstructorRef = Regex("""::\s*BackupEncryptionService\b""")
    private val seamJavaConstructorRef = Regex("""\bBackupEncryptionService\s*::\s*new\b""")

    @Test
    fun `seam detection regexes match argument calls and constructor references only`() {
        // Self-test: the production scan is only meaningful if these patterns really match.
        assertTrue(seamArgumentCall.containsMatchIn("BackupEncryptionService(\n    iterations = 1\n)"))
        assertTrue(seamArgumentCall.containsMatchIn("BackupEncryptionService(1_000)"))
        assertFalse(seamArgumentCall.containsMatchIn("BackupEncryptionService()"))
        assertTrue(seamNoArgCall.containsMatchIn("BackupEncryptionService()"))

        assertTrue(seamKotlinConstructorRef.containsMatchIn("val f = ::BackupEncryptionService"))
        assertFalse(seamKotlinConstructorRef.containsMatchIn("BackupEncryptionService::class.java"))
        assertTrue(seamJavaConstructorRef.containsMatchIn("Function<Integer, BackupEncryptionService> f = BackupEncryptionService::new;"))
        assertFalse(seamJavaConstructorRef.containsMatchIn("BackupEncryptionService::class.java"))
    }

    @Test
    fun `production code never uses the iteration seam`() {
        var filesVisited = 0
        var noArgOccurrences = 0
        val offenders = mutableListOf<String>()
        val scannedSourceSets = mutableListOf<String>()
        resolveProductionSourceSetDirs().forEach { sourceSetDir ->
            scannedSourceSets += sourceSetDir.name
            sourceSetDir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    filesVisited++
                    val text = file.readText()
                    noArgOccurrences += seamNoArgCall.findAll(text).count()
                    listOf(seamArgumentCall, seamKotlinConstructorRef, seamJavaConstructorRef).forEach { regex ->
                        regex.findAll(text).forEach { match ->
                            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                            offenders += "${file.path}:$line"
                        }
                    }
                }
        }

        assertTrue("scan must include the main source set (scanned $scannedSourceSets)", "main" in scannedSourceSets)
        assertTrue("scan must visit production .kt/.java files (visited $filesVisited)", filesVisited > 0)
        assertTrue(
            "scan must see at least one no-arg BackupEncryptionService() (non-vacuous), saw $noArgOccurrences",
            noArgOccurrences > 0
        )
        assertTrue(
            "production code must not call the BackupEncryptionService iteration seam " +
                "(constructor call with arguments or constructor reference); offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `iterations must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { BackupEncryptionService(0) }
        assertThrows(IllegalArgumentException::class.java) { BackupEncryptionService(-1) }
        // Boundary control: the smallest positive count is accepted.
        assertEquals(1, BackupEncryptionService(1).iterationCount)
    }

    /**
     * Every source set directory under app/src that can ship in a production build: main,
     * debug, release and any flavor dirs. Excludes the test source sets (`test`,
     * `androidTest` and their variant forms such as `testDebug`).
     */
    private fun resolveProductionSourceSetDirs(): List<java.io.File> {
        val marker = "main/java/com/yourname/expensetracker/data/privacy/BackupEncryptionService.kt"
        val userDir = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            java.io.File("src"),
            java.io.File("app/src"),
            java.io.File(userDir, "src"),
            java.io.File(userDir, "app/src")
        )
        val srcRoot = candidates.firstOrNull { java.io.File(it, marker).isFile }
            ?: error("Could not locate production source root. user.dir=$userDir")
        val testSourceSet = Regex("""^(test|androidTest)([A-Z].*)?$""")
        return srcRoot.listFiles { dir -> dir.isDirectory && !testSourceSet.matches(dir.name) }
            .orEmpty()
            .sortedBy { it.name }
    }
}
