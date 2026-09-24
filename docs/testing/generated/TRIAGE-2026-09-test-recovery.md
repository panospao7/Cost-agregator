# TRIAGE — 2026-09 test recovery (rp-25-wip)

Rows follow the pack format: `testClass.method | observed | suspected mechanism | class`
(class ∈ harness | stale | REAL-BUG-CANDIDATE). Rows here are recorded, NOT resolved —
resolution happens through the campaign pipeline (verification + wave planning) or an
explicit human decision. No assertion was changed to achieve any pass in this lane.

## From Phase A compile-repair execution (2026-09-24)

| Test | Observed | Suspected mechanism | Class |
|---|---|---|---|
| KeystoreInstallationSecretHashingTest.`corpus_amount_and_currency_strings_are_not_falsely_redacted_as_phone` | `"12345.67"` in "Payment of 12345.67 at a shop" gets redacted to `[REDACTED_PHONE]` | privacy redactor phone-number heuristic over-matches numeric amounts — real over-redaction of financial text in cloud payloads | REAL-BUG-CANDIDATE (privacy/redaction; relates to E-01/CL-17 surfaces' data quality) |
| KeystoreInstallationSecretHashingTest.`real phone numbers are still redacted` | FAILED (assertion) | same heuristic family as row above — under/over-match regression in phone detection | REAL-BUG-CANDIDATE (same root cause as row 1) |
| KeystoreInstallationSecretHashingTest.`keystore_provider_class_exists_with_expected_contract` | expected `class InstallationSecretKeyProvider`, was `interface` | test asserts implementation SHAPE (class vs interface) that legitimately changed; assertion update requires a human "update the contract" decision, not a silent coder edit | STALE (decision needed) |
| BackupRestoreViewModelPrivacyDenialTest.`backup privacy denial converges on typed blocked state without message matching` | repository throws `PrivacyDeniedException(ENCRYPTED_BACKUP)`; VM sets reasonCode `PRIVACY_GATE_FAILURE`, test expects `ENCRYPTED_BACKUP_DISABLED` | denial reason-code contract mismatch on the backup path — either the VM lost the specific denial mapping (regression) or the contract was deliberately coarsened (test stale). Surface = CL-18's own files; decision belongs to privacy-security-guardian | REAL-BUG-CANDIDATE (CL-18 surface) |
| BackupRestoreViewModelPrivacyDenialTest.`restore privacy denial converges on typed blocked state` | same shape as row above on the restore path (`privacyBlocked` null where typed state expected) | same denial-contract mismatch family | REAL-BUG-CANDIDATE (CL-18 surface) |
| SpendingMapViewModelPrivacyDenialTest — 4 tests (`gate denial…`, `permission race…`, `permitted fetch…`, `fail-closed…`) | `IllegalStateException: gpsPrivacyBlocked was never set` from the test's await helper | location privacy-denial paths never set the typed blocked state the tests (and presumably UI) expect — privacy UX state gap | REAL-BUG-CANDIDATE (privacy/I-05 surface) |
| ReviewViewModelPrivacyDenialTest — 3 tests | `NoSuchElementException: Expected at least one element` at `ReviewViewModel.kt:249` (`flow.first()` on an empty flow) | flow under denial never emits — needs reading whether producer or consumer is wrong; could be harness (missing stub) or production gap | REAL-BUG-CANDIDATE (unclassified) |

## Harness repairs applied in this lane (test-side only, intent preserved)

| File | Repair | Effect |
|---|---|---|
| BackupRestoreViewModelPrivacyDenialTest | stub `context.cacheDir` → real tmpdir (relaxed mock returned a mock File with null path → NPE in `createTempFile`) | permitted-restore test stops crashing before its assertions |
| BackupRestoreViewModelPrivacyDenialTest | stub `contentResolver.openInputStream(any())` → real empty stream (relaxed mock stream's `read()` never returns −1 → infinite staging copy = 40-min test-JVM hang) | whole class now completes; hang eliminated |

Both stubs previously MASKED failures as crashes/hangs; the permitted-restore test now executes
its real assertions. Security-exception test's own throwing stub overrides the setup default.

## Phase A execution record (all via validation-runner, rp-25-wip)

| Run | Filter | Result |
|---|---|---|
| vr-20260924-182230 | probe `*RestoreJournalDurabilityTest` | FAIL → 9 compile errors (the expected 5 files) |
| vr-20260924-183616 | probe after repairs | **PASS** — test sources compile at HEAD (first time) |
| vr-20260924-184837 | `*PrivacyDenialTest` | FAIL: 12 failed / 8 passed (pre-existing behavioral) |
| vr-20260924-185304 | `*KeystoreInstallationSecretHashingTest` | FAIL: 3 failed / 9 passed (repaired tests PASS) |
| vr-20260924-185436 | `*LegacyDataConsistencyCheckerTest` | **PASS 5/5** |
| vr-20260924-185627 | `*ReceiptLifecycleCoordinatorTest` | **PASS 28/28** |
| vr-20260924-185958 / 191718 | `*BackupRestoreViewModelPrivacyDenialTest` | TIMEOUT ×2 (pre-stub hang — see harness table) |
| vr-20260924-193441 | `*BackupRestoreViewModelPrivacyDenialTest` after stream stub | FAIL: 2 failed (denial-contract, triaged) / 5 passed — hang eliminated |

Verdict summary: **5 files compile-repaired, 0 assertions changed, 0 tests deleted/ignored.**
Phase A goal (compile + execute) met. Remaining failures are triaged signal, not noise.
