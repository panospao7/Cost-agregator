package com.yourname.expensetracker.testfixtures.backup

import com.yourname.expensetracker.data.privacy.BackupEncryptionService

/**
 * Test-only PBKDF2 iteration count for [BackupEncryptionService].
 *
 * The production count (600,000) makes every derivation slow. Tests that encrypt and
 * decrypt through ONE shared instance and decrypt no checked-in production-count fixture
 * may use [lowIterationBackupEncryptionService] instead. Never decrypt production-count
 * ciphertext with this instance (and vice versa): the count is not stored in the envelope.
 */
const val TEST_PBKDF2_ITERATIONS = 1_000

/**
 * A [BackupEncryptionService] using [TEST_PBKDF2_ITERATIONS] via the internal test seam.
 *
 * Test-only. Never use it for a test that creates a bundle with this instance and restores it
 * through `DatabaseBackupRepositoryImpl.restoreCostBackup`: that restore path always decrypts
 * with a fresh production-count instance, so such a test would fail with a misleading
 * wrong-password error (or pass a wrong-password test vacuously).
 */
fun lowIterationBackupEncryptionService(): BackupEncryptionService =
    BackupEncryptionService(TEST_PBKDF2_ITERATIONS)
