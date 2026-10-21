# B4-K5 map + decisions (scout 2026-10-20 on HEAD 945afffc; approved by user "Approve your recommendations"; T3 → reviewer-strict + privacy-security-guardian)

M = app/src/main/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker.

## Facts (scout)
- M/data/privacy/BackupEncryptionService.kt: `@Singleton class BackupEncryptionService @Inject constructor()` (:36-37); companion `internal const val ITERATION_COUNT = 600_000` (:51), KEY_LENGTH_BITS 256, SALT 16, IV 12; one PBKDF2 site `deriveKey` (:170-175, PBEKeySpec at :172) used by encrypt(ByteArray) :67, encrypt(File, OutputStream) :88, decryptStream :135, decrypt(ByteArray) :163.
- Envelope = salt16 + iv12 + ciphertext+tag; outer COSTBACKUP1 header has magic + 2-byte format version only. **No iteration count stored anywhere; decrypt cannot read one** → no downgrade path as long as decrypt uses the instance field only. No legacy branch (FRESH-P7-007, pre-existing).
- No Hilt module/@Provides; constructor-injected into ExportDataRepository :23, DatabaseBackupRepositoryImpl :86/:118. CostbackupBundle.kt :294/:339/:411 use `BackupEncryptionService()` as default params (production count).
- androidTest: no reference. No guard/script pin on the constant (dao-approved-files.txt:58 lists the filename only).
- Plan names CostbackupBundleLimitsTest and P7BugFixesTest no longer exist (P7 → CostbackupBundleExtractFailureTest). BackupRestoreContractTest / BackupRestoreIntegrityE2ETest do not touch the service → no change.

## Decisions
1. Production seam: `internal constructor(iterations: Int)` (primary, `require(iterations > 0)`, stored `private val iterations`), plus `@Inject constructor() : this(ITERATION_COUNT)`. `deriveKey` uses `iterations`. Keep `ITERATION_COUNT` internal const and the :46-49 warning comment. No other production change. `internal val iterationCount` read-only accessor allowed for the pin test (or reflect-free equivalent).
2. New tests in T/data/privacy/BackupEncryptionServiceTest.kt:
   - production instance (`BackupEncryptionService()`) uses 600_000;
   - ciphertext from a low-iteration instance does NOT decrypt under the default instance (and vice versa) → proves the count is never inferred from data;
   - source scan: no `BackupEncryptionService(` call with arguments anywhere under app/src/main (seam unreachable from production).
   - Keep the :96-103 constant pin and the wrong-password test (:43) on the production instance.
3. Switch to a shared low-iteration instance (single test constant, e.g. 1_000) only where every encrypt/decrypt in the test uses the same instance and no checked-in fixture is decrypted:
   - BackupEncryptionServiceTest round-trip/corruption/uniqueness/layout/streaming tests;
   - CostbackupBundleExtractFailureTest :105 spyk (garbage input);
   - CostbackupBundleTest: only if `encryptionService =` can be threaded through every create/extract/encrypt call in a test; otherwise leave that test on production;
   - BackupVerifierSavingsLedgerTest :219/:178: only if create and verifier share the instance;
   - DatabaseBackupRepositoryImplTest real instances (:152, :177, :366, :407, :435, :464, :3218) via `createRepository(encryptionService = …)`, per-test single instance.
   Never touch a test whose purpose is the production KDF.
4. Expected counts: BackupEncryptionServiceTest +3; others unchanged.

## Validation (one batch with the B1-5 fix pass)
targeted-unit-test: data.privacy.BackupEncryptionServiceTest, data.backup.CostbackupBundleTest, data.backup.CostbackupBundleExtractFailureTest, data.backup.BackupVerifierSavingsLedgerTest, data.repository.DatabaseBackupRepositoryImplTest (+ B1-5 fix-pass classes); registered-guard di_release. Compare class durations vs the last full-suite run.

## Then B6-23 (needs its own short scout after K5 lands)
