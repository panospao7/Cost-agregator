# Batch 12 — data-email, data-location, data-privacy

Scope: Segments 4 (receipt/email ingestion data layer), 12 (startup & background runtime — location/retention workers), 18 (backup/export privacy), 19 (location enrichment data layer) · Files: 22 · LOC: 3,763
Reviewer notes: Static review only. Ledger families touching this batch: F-20 (MerchantKeyBackfillWorkerTest normalization drift), F-21 (misc singles incl. EmailReceiptParser date/tz + Geocoding). Prior stale audit (`generated/test-batches/batch-001.md`) re-verified per file; two verdicts overturned (see findings). Worker tests here stub `WorkerExecutionGuard.runGuardedWithContext` with a hand-rolled re-implementation of guard classification (LocationBackfillWorkerTest.kt:54-78, MerchantKeyBackfillWorkerTest.kt:44-69, DataRetentionWorkerTest.kt:85-96) — real-guard drift will NOT be caught by these files; extract a shared fixture that wraps the real guard. RawContentSanitizer itself has no test file in this batch; RawStorageMode enforcement lives in `contracts/PrivacyStorageContractTest`, `domain/privacy/*`, `golden/PrivacyDoNotStoreTest` (other batches) — this batch covers the fail-closed *settings defaults*, retention purge, and export-redaction sides.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 12 | 7 | 0 | 2 | 1 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/data/email/EmailReceiptIngestionServiceTest.kt | 960 | 24 | 0 | MOCKED | EmailReceiptIngestionService | STRENGTHEN | P1 | #2; DedupeKeyProducerConsistencyTest; PrivacyBehavioralRegressionTest | FRAGILE; fingerprint-discrimination tests excellent; several mock-echo mapping tests |
| 2 | test/…/data/email/EmailReceiptIngestionServiceTransactionTest.kt | 137 | 1 | 0 | MOCKED | EmailReceiptIngestionService | KEEP | P1 | #1 | Legal-path pin (delegate-all-to-coordinator); stale prior verdict |
| 3 | test/…/data/email/provider/AmazonReceiptParserTest.kt | 41 | 1 | 0 | PURE | AmazonReceiptParser | STRENGTHEN | P1 | #1,#2 (mocked) | Real localized €12,34 + FR date; only 1 case |
| 4 | test/…/data/email/provider/AppleReceiptParserTest.kt | 205 | 11 | 0 | PURE | AppleReceiptParser | KEEP | P1 | #1,#2 (mocked) | Double-escape regression + sender gating; exemplary |
| 5 | test/…/data/email/provider/EmailReceiptParserTest.kt | 61 | 3 | 0 | PURE | BaseEmailParser | REWRITE | P1 | — | F-21: UTC-zone expected vs systemDefault production parse |
| 6 | test/…/data/email/provider/UberReceiptParserTest.kt | 127 | 6 | 0 | PURE | UberReceiptParser | REWRITE | P1 | — | F-21 tz family; mojibake bodies; assertions contradict names |
| 7 | test/…/data/location/AndroidForegroundLocationProviderTest.kt | 16 | 1 | 0 | PURE | AndroidForegroundLocationProvider | DELETE | P4 | — | Tautology re-confirmed (asserts pair equals itself) |
| 8 | test/…/data/location/CompositeGeocodingServiceStressTest.kt | 174 | 8 | 1 | MOCKED | CompositeGeocodingService | STRENGTHEN | P1 | #9 | @Ignore confirmed (line 26); not stress — dead unique coverage |
| 9 | test/…/data/location/CompositeGeocodingServiceTest.kt | 52 | 1 | 0 | MOCKED | CompositeGeocodingService | KEEP | P2 | #8 | Fallback-cascade; complementary to #8 |
| 10 | test/…/data/location/GeocodingCancellationTest.kt | 151 | 2 | 0 | MOCKED | executeCancellable, PhotonGeocodingService | KEEP | P1 | CancellationSafetyArchitectureGuardTest | Real OkHttp cancellation; latch flake risk minor |
| 11 | test/…/data/location/GeocodingRetryHttpSemanticsTest.kt | 127 | 5 | 0 | MOCKED | Photon/Nominatim/Geoapify/GooglePlaces | KEEP | P1 | — | Real interceptor 429/503 retry; typed GeocodingError |
| 12 | test/…/data/location/LocationBackfillWorkerTest.kt | 220 | 6 | 0 | ROBOLECTRIC | LocationBackfillWorker | STRENGTHEN | P1 | WorkerGuardStaticVerificationTest; SourceScanningArchitectureGuardTest | Guard stub duplicates real guard semantics; FRAGILE |
| 13 | test/…/data/location/MerchantKeyBackfillWorkerTest.kt | 169 | 5 | 0 | ROBOLECTRIC | MerchantKeyBackfillWorker | STRENGTHEN | P1 | WorkerSpecSchedulerTest; WorkerIdempotencyTest | F-20 (`still_broken` vs `stillbroken`); guard-stub duplication |
| 14 | test/…/data/location/NominatimGeocodingServiceLocaleTest.kt | 59 | 1 | 0 | MOCKED | NominatimGeocodingService | KEEP | P1 | — | Greek-locale dot-decimal URL guard; locale restored in finally |
| 15 | test/…/data/location/OverpassNearbyServiceTest.kt | 115 | 2 | 0 | MOCKED | OverpassNearbyService | KEEP | P2 | HaversineConsistencyTest | 429 retry + Greek name ranking, real JSON |
| 16 | test/…/data/location/internal/LogSanitizerTest.kt | 39 | 3 | 0 | PURE | anonymizeForLog (LogSanitizer) | KEEP | P1 | — | No raw leak, stable, distinct outputs |
| 17 | test/…/data/privacy/BackupEncryptionServiceTest.kt | 78 | 5 | 0 | PURE | BackupEncryptionService | KEEP | P0 | CostbackupBundleLimitsTest; DatabaseBackupRepositoryImplTest | AES-256-GCM fail-closed: bad tag, tamper, random salt/IV |
| 18 | test/…/data/privacy/DataRetentionWorkerTest.kt | 395 | 16 | 0 | ROBOLECTRIC | DataRetentionWorker | STRENGTHEN | P0 | RetentionTargetPurgeTest; RawStoragePolicyAuditTest; DbGuardPolicyFixtureTest | Strong sanitized-diagnostics; 1 SRCTEXT test; guard stub |
| 19 | test/…/data/privacy/ExportAnonymizerTest.kt | 161 | 4 | 0 | ROBOLECTRIC | ExportAnonymizer | KEEP | P0 | DatabaseBackupRepositoryImplTest | Real SQLite redaction across all PII tables; hashes preserved |
| 20 | test/…/data/privacy/PrivacySettingsRepositoryImplCorruptionTest.kt | 188 | 9 | 0 | ROBOLECTRIC | PrivacySettingsRepositoryImpl, PrivacySettings | STRENGTHEN | P1 | P8PrivacyFixesTest; PrivacySettingsLoadStateTest | Fail-closed defaults asserted; several constant-only tests |
| 21 | test/…/data/privacy/PrivacySettingsRepositoryImplWorkerGatingTest.kt | 153 | 6 | 0 | ROBOLECTRIC | PrivacySettingsRepositoryImpl | KEEP | P1 | WorkerSpecSchedulerTest | Over-cancel + no-reschedule regression guards; data_retention never gated |
| 22 | test/…/data/privacy/RetentionTargetPurgeTest.kt | 135 | 2 | 0 | ROOM | RetentionModule targets | KEEP | P0 | DataRetentionWorkerTest | Real Room + real production targets; nulls raw payload, keeps recent |

## Findings (noteworthy files only)

### test/…/data/email/provider/EmailReceiptParserTest.kt — REWRITE (F-21)
- Pure tests of `BaseEmailParser` (cleanHtml, localized amount, non-English month) — real value.
- Expected-date helper `expectedUtcMillis` pins `ZoneId.of("UTC")` (lines 43-48) while sibling parser tests use `ZoneId.systemDefault()`; if production `parseLocalizedDate` anchors to system-default zone, this fails on any non-UTC machine — consistent with measured F-21 "EmailReceiptParser date/tz" failure.
- Fix: use `ZoneId.systemDefault()` (or inject a fixed clock/timezone into the parser) and re-run.

### test/…/data/email/provider/UberReceiptParserTest.kt — REWRITE (F-21 adjacent)
- Helper named `utcMillis` builds UTC-midnight expectations, but its own comment (line 114) says production `parseUberDate` uses system default timezone → TZ-dependent assertions at lines 30, 47, 81.
- Bodies embed mojibake euro (`â‚¬`, lines 38/57 — file has BOM + encoding damage). Test at line 34-48 is named "handles localized ride total and labeled date" yet asserts amount `4.0` for input `12,34` and date `0L` (parse fallback) — assertions contradict the test's stated intent; lines 84-97 and 99-112 assert `null` for what look like parseable inputs.
- Action: runtime triage, fix encoding to real UTF-8, align TZ basis, and re-express intents (these currently pin encoding-corruption behavior).

### test/…/data/location/CompositeGeocodingServiceStressTest.kt — STRENGTHEN (prior verdict overturned)
- Class-level `@Ignore("Stress test: may hang in CI, run manually")` re-verified at line 26 — all 8 tests dead in CI.
- Content is NOT stress: all-MockK, no sleeps/network (dedup of near-identical coords line 104, min-result-window 10 line 130, qualifier ranking line 116, free-vs-paid provider routing lines 72/89, cancellation line 151). These behaviors are tested nowhere else in CI (#9 only covers fallback cascade).
- Recommend: un-ignore into PR CI after fixing the `runBlocking` + `CancellationException` test (line 151); rename or genuinely stress it. Prior MOVE_TO_NIGHTLY verdict overturned — nightly-only would leave dedup/ranking regressions uncaught for 24h for no perf reason.

### test/…/data/email/EmailReceiptIngestionServiceTest.kt — STRENGTHEN (FRAGILE)
- High value: content-fingerprint discrimination tests (order number lines 422/824, currency line 476, sender domain line 762, equality line 530), confidence threading into `EmailReceiptData` line 578, NeedsReview honesty lines 614-704, residual routing pin line 894.
- FRAGILE: injects parsers via reflection into private fields (lines 47-49, 955-959); fingerprint tests assert on positional coordinator args (`secondArg()`); `ingestion_mutex_is_bounded_semaphore` (line 741) reflects on a private field and its only assertion (`availablePermits >= 3`) cannot fail for a fresh semaphore — implementation-pinning, near-zero value.
- Low value remainder: several tests merely echo the mocked coordinator result back (e.g. lines 151-203 Duplicate-mock → Duplicate asserted) — mapping tautologies per MASTER_TESTING_STRATEGY.

### test/…/data/email/EmailReceiptIngestionServiceTransactionTest.kt — KEEP, prior verdict stale
- Prior audit described "Robolectric + Room in-memory; proves rollback; table count = 0" — FALSE today. Current file is pure MockK: asserts ParseError surfacing on coordinator error (line 114) and exactly-once delegation (line 121).
- Still valuable as the legal-path pin (service runs no transaction of its own; all mutation via `ReceiptLifecycleCoordinator` per LEGAL_PATHS), but the DB-level rollback proof now exists only in coordinator tests (other batch) — noted as a coverage dependency, not a dup.

### test/…/data/location/MerchantKeyBackfillWorkerTest.kt — STRENGTHEN (F-20)
- Measured failures: expected `"still_broken"` but production normalized `"stillbroken"` (ledger F-20; assertions at lines 142/156). Either an intentional normalization change (update test) or a real regression (fix prod) — needs one deliberate decision, not drift.
- Good: idempotent skip, partial-progress retry preserves row 1's update (lines 127-144), zero-count diagnostics.
- FRAGILE: `@Before` re-implements `WorkerExecutionGuard` precedence inside the mock (lines 44-69) — real-guard changes won't fail here.

### test/…/data/privacy/DataRetentionWorkerTest.kt — STRENGTHEN (P0 area)
- Privacy-critical and mostly right: transient→retry vs permanent→success-with-FAILED_FINAL diagnostic (lines 188/206), CancellationException rethrown (line 244), sanitized diagnostics (no raw message line 329, controlled reason code line 348, exception class only line 364), and the AGENTS.md rule "cleanup must not be gated by the retention capability it enforces" (line 314).
- One SRCTEXT anti-pattern: `data_retention_worker_has_no_legacy_raw_purge_helpers` reads production source via relative path `File("src/main/...")` (lines 382-394) — brittle CWD dependence; migrate to the architecture-guard suite or delete.
- Same guard-stub duplication as #12/#13 (lines 85-96), here even less faithful (no transient classification).

### test/…/data/privacy/PrivacySettingsRepositoryImplCorruptionTest.kt — STRENGTHEN
- Real value: actual garbage-byte DataStore corruption → CORRUPTED sentinel (lines 44-59, 148-173) and `FAIL_CLOSED_DEFAULTS` asserting DO_NOT_STORE for notification/OCR/email/bank-statement modes + capture/cloud-AI off (lines 62-79) — the fail-closed contract this batch owns.
- Weakness: 4 of 9 tests never touch production code (DataStore library behavior, `"CORRUPTED" != null` tautology at lines 176-186); the `CorruptedFailClosed` check (line 123) omits `rawBankStatementStorageMode` though the other test asserts it — close that gap.

## Area gaps (what is NOT tested in this area)

- No test exercises `EmailReceiptIngestionService` write-barrier interaction: `writeBarrier` is injected and relaxed everywhere; no test asserts ingestion is blocked during restore mode.
- RawStorageMode STORE_REDACTED / STORE_METADATA_ONLY persistence behavior is not covered in data/email at all — enforcement lives in `contracts/PrivacyStorageContractTest`, `domain/privacy/RawStorageEndToEndTest`, `golden/PrivacyDoNotStoreTest` (verify those batches cover all three modes fail-closed; no `RawContentSanitizer`-named test file exists anywhere under app/src/test).
- Amazon/Uber parser tests are thin (1 and 6 cases) vs Apple's 11; no negative/anomalous-amount parsing tests (zero, negative, thousand-group edge) for money extraction from email — money-parsing risk.
- LocationBackfillWorker: no test for batch >1 page (pagination loop) or BackoffPolicy; attempt budget only checked via `incrementBackfillAttempts` not-called.
- Overlap note (engine-vs-data split with batch 19): data/location covers HTTP transport (retry/cancel/locale), worker orchestration, and CompositeGeocodingService merging; `LocationResolver.resolve` is only ever mocked here — real resolver logic is batch 19's `domain/location`. No true DUP pairs found across the split; keep the boundary.
- ExportAnonymizer: no test that sanitized export is itself restorable/openable by the app's Room schema (redaction vs integrity interplay).

## Rollup

- Verdicts: KEEP 12, STRENGTHEN 7, MERGE 0, REWRITE 2, DELETE 1, NIGHTLY 0, UNKNOWN 0.
- P0 count: 4 (#17 BackupEncryption, #18 DataRetentionWorker, #19 ExportAnonymizer, #22 RetentionTargetPurge).
- DUP pairs: none full; partial overlaps — #8/#9 (CompositeGeocodingService, complementary → consider merging #9 into un-ignored #8), #1/#2 (service behavior vs delegation contract, complementary), #21/WorkerSpecSchedulerTest.
- FRAGILE count: 5 (#1, #2 reflection injection; #12, #13, #18 guard-semantics duplication).
- Ledger families cited: F-20 (#13), F-21 (#5, #6, Geocoding static-sound).
- P4 (negative value): #7 AndroidForegroundLocationProviderTest.
- Prior-audit overturns: #2 (stale "Room rollback" description → mock contract test, P0→P1), #8 (MOVE_TO_NIGHTLY → STRENGTHEN/un-ignore), #7 (DELETE P4 re-confirmed).
