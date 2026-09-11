# Batch 20 — domain/parser · domain/price · domain/privacy · domain/provenance

Scope: Segment 3 (Notification Capture, Parsing & Review — parsers), Segment 34 (Price Protection), Segment 28 (Security & API Key Management — RawStorageMode/RawContentSanitizer/CloudPayloadPolicy/PrivacyDecision), Segment 29/28 (RetentionRegistry), Segment 9-adjacent provenance backfill worker · Files: 35 · LOC: 7,624 · Tests: 462 · @Ignore: 0

Reviewer notes: Privacy is strict-mode P0 per AGENTS.md. Cross-batch picture for RawStorageMode/DO_NOT_STORE: enforcement logic lives in the four `*PersistencePayload.build` factories + `RawContentSanitizer` + `RawPersistencePolicyResolver` (domain/privacy, this batch, heavily tested); write-path persistence is covered by golden/PrivacyDoNotStoreTest (batch 01, flagged weak), contracts/PrivacyStorageContractTest (batch 06, static scan), data/privacy/PrivacySettingsRepositoryImplCorruptionTest (data batch), and ReceiptLifecycleCoordinatorTest (receipt batch). Batch 12's claim "no RawContentSanitizer-named test exists anywhere" is true only by file *name* — direct RawContentSanitizer behavior tests exist in this batch (P8PrivacyFixesTest, PR5PrivacyContractTest, RawPersistencePolicyTest, PrivacyGuardTest G5 guard). Zero @Ignore in this batch. Parser tests are Segment 3 legal-path-clean (pure domain, no DAO bypass). SourceLinkBackfillWorkerTest follows worker rules (barrier, CancellationException rethrow). No file in this batch appears in TEST_FAILURE_LEDGER families F-01…F-21. A `consistency/CrossParserConsistencyTest.kt` covers parser merchant-key drift — complementary, not duplicate.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 20 | 7 | 6 | 1 | 1 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/domain/parser/AppParserRegistryRoutingTest.kt | 110 | 6 | 0 | MOCKED | AppParserRegistry, RevolutParser, GreekBankParser, SmsParser, GoogleWalletParser, GenericTransactionParser | KEEP | P1 | #2; e2e NotificationExpenseDashboard* | routing + fallback + no-match; real parsers |
| 2 | test/…/domain/parser/AppParserRegistryTest.kt | 150 | 8 | 0 | MOCKED | AppParserRegistry (+ all parsers) | MERGE | P1 | #1 (DUP) | merge OTP + confidence-provenance tests into #1 |
| 3 | test/…/domain/parser/GenericTransactionParserStressTest.kt | 105 | 11 | 0 | PURE | GenericTransactionParser | KEEP | P1 | #4 complementary | not stress; real normalizer/cleaner; overturn NIGHTLY |
| 4 | test/…/domain/parser/GenericTransactionParserTest.kt | 296 | 21 | 0 | MOCKED | GenericTransactionParser, TransferDirectionDetector | KEEP | P1 | #3 complementary | amounts, bounds, spam rejection |
| 5 | test/…/domain/parser/GoogleWalletParserTest.kt | 274 | 18 | 0 | MOCKED | GoogleWalletParser | KEEP | P1 | e2e pipeline tests | INR + corrupted €→E regression; P2P vs purchase |
| 6 | test/…/domain/parser/GreekBankParserStressTest.kt | 107 | 10 | 0 | PURE | GreekBankParser | KEEP | P1 | #7 complementary | not stress; decimal comma; overturn NIGHTLY |
| 7 | test/…/domain/parser/GreekBankParserTest.kt | 139 | 10 | 0 | MOCKED | GreekBankParser | KEEP | P1 | #6 complementary | Greek comma, single decimal, package list |
| 8 | test/…/domain/parser/NBGReproTest.kt | 54 | 1 | 0 | PURE | GreekBankParser, MerchantCleaner | KEEP | P2 | #7 | live-bug regression guard; has println |
| 9 | test/…/domain/parser/RevolutParserTest.kt | 352 | 25 | 0 | MOCKED | RevolutParser | KEEP | P0 | e2e pipeline tests | money P0: grouped US/EU amounts, ATM, currencies |
| 10 | test/…/domain/parser/SmsParserTest.kt | 235 | 17 | 0 | MOCKED | SmsParser | KEEP | P1 | #1, #2 | grouped amounts, ambiguous direction nulls |
| 11 | test/…/domain/parser/TransferDirectionDetectorTest.kt | 446 | 52 | 0 | PURE | TransferDirectionDetector | KEEP | P1 | consistency/CrossParserConsistencyTest | exhaustive EN/GR; 90%-bucket test can mask 1 miss |
| 12 | test/…/domain/price/PriceProtectionTrackerTest.kt | 335 | 19 | 0 | MOCKED | PriceProtectionTracker | STRENGTHEN | P2 | PriceProtectionViewModelTest | 3 vacuous if-empty asserts; real clock; 1 verify-only |
| 13 | test/…/domain/privacy/BackupPrivacyGateOwnershipTest.kt | 51 | 3 | 0 | MOCKED | BackupPrivacyGate | KEEP | P0 | ExportPrivacyPolicyTest | gate-ownership: no conflicting verdicts |
| 14 | test/…/domain/privacy/BankPrivacyHardeningTest.kt | 200 | 12 | 0 | PURE | BankTransactionPersistencePayload, BankTokenCipher, RawPersistencePolicyResolver | KEEP | P0 | #30, RawStorageEndToEndTest | fail-closed bank payloads + token invariants |
| 15 | test/…/domain/privacy/CloudAuditProviderProvenanceTest.kt | 196 | 10 | 0 | MOCKED | PrivacyAuditContext, SafePrivacyMetadata, CompositePrivacyGate | KEEP | P1 | #16 | audit provenance; composite missing-handler fails closed |
| 16 | test/…/domain/privacy/CloudPayloadPolicyTest.kt | 154 | 11 | 0 | MOCKED | DefaultCloudPayloadPolicy, EffectiveCloudAiPolicyResolver | KEEP | P0 | #17 (DUP) | survivor of pair; redaction matrix |
| 17 | test/…/domain/privacy/CloudProviderPreparedPayloadTest.kt | 129 | 8 | 0 | MOCKED | DefaultCloudPayloadPolicy | MERGE | P1 | #16 | dup + CWD-dependent script-existence test |
| 18 | test/…/domain/privacy/EmailRawStorageEnforcementTest.kt | 191 | 10 | 0 | PURE | EmailReceiptPersistencePayload | STRENGTHEN | P0 | #29, #19, #31 | 3 tautologies (ifBlank/correlation locals); rest real |
| 19 | test/…/domain/privacy/ExportPrivacyPolicyTest.kt | 141 | 12 | 0 | MOCKED | ExportPrivacyGate, ExportPrivacyPolicy | KEEP | P0 | golden PrivacyGateEnforcementGoldenTest | fail-closed export matrix; debug-gated raw |
| 20 | test/…/domain/privacy/NotificationPrivacyHardeningTest.kt | 187 | 10 | 0 | PURE | (none — test-owned simulations) | DELETE | P4 | scenarios/PrivacyGateContractTest | all tests assert test's own if/when replicas |
| 21 | test/…/domain/privacy/P8PrivacyFixesTest.kt | 409 | 8 | 0 | PURE | RawContentSanitizer, EffectiveCloudAiPolicy, DefaultCloudPayloadRedactor | STRENGTHEN | P0 | #22, #26 | P8-001/006 test own fake/loop; rest real |
| 22 | test/…/domain/privacy/PR5PrivacyContractTest.kt | 324 | 15 | 0 | MOCKED | RawContentSanitizer, BankApiIntegration, RawPersistencePolicyResolver, EffectiveCloudAiPolicyResolver | KEEP | P0 | #34 (DUP), #21 | BankApiIntegration ctor mock-heavy FRAGILE |
| 23 | test/…/domain/privacy/PrivacyBehavioralRegressionTest.kt | 269 | 16 | 0 | PURE | PrivacySettings, DefaultCloudPayloadPolicy | REWRITE | P1 | #27, #16 | half tautologies ("1==1"), half real value |
| 24 | test/…/domain/privacy/PrivacyCapabilityHandlingPolicyProductionTest.kt | 139 | 9 | 0 | MOCKED | PrivacyCapabilityHandlingPolicy, CompositePrivacyGate | STRENGTHEN | P1 | #25 (DUP) | exhaustiveness guard; 3 self-referential tests |
| 25 | test/…/domain/privacy/PrivacyCapabilityHandlingPolicyTest.kt | 84 | 3 | 0 | PURE | (test-owned shadow policyMap) | MERGE | P2 | #24 | shadows production policy in test map |
| 26 | test/…/domain/privacy/PrivacyGuardTest.kt | 383 | 12 | 0 | SRCTEXT | RawContentSanitizer (source), CompositePrivacyGate (source), PrivacySettings | STRENGTHEN | P1 | scripts/verify_privacy_boundaries.py | deliberate static guard; G5/G8 silently skip if file missing |
| 27 | test/…/domain/privacy/PrivacySettingsLoadStateTest.kt | 231 | 21 | 0 | PURE | PrivacySettings.FAIL_CLOSED_DEFAULTS, PrivacySettingsLoadState | STRENGTHEN | P0 | #23; data PrivacySettingsRepositoryImplCorruptionTest | fake's corruption mapping tested, not prod impl |
| 28 | test/…/domain/privacy/RawPersistencePolicyTest.kt | 248 | 20 | 0 | PURE | RawPersistencePolicyResolver, DefaultSensitiveHashingService, RawContentSanitizer, SafePrivacyMetadata | KEEP | P0 | #34 | mode×source matrix; HMAC determinism |
| 29 | test/…/domain/privacy/RawStorageEndToEndTest.kt | 311 | 18 | 0 | PURE | Notification/Receipt/Email/Bank PersistencePayload | KEEP | P0 | #30, #31, #18 | sentinel-based; survivor of raw-storage merge |
| 30 | test/…/domain/privacy/RawStoragePolicyAuditTest.kt | 257 | 13 | 0 | PURE | same payload builders + RawPersistencePolicyResolver | MERGE | P1 | #29 (DUP) | keep resolver-matrix test; retention test tautological |
| 31 | test/…/domain/privacy/ReceiptOcrEmailStorageHardeningTest.kt | 244 | 12 | 0 | PURE | ReceiptPersistencePayload, EmailReceiptPersistencePayload | STRENGTHEN | P0 | #29, #18 | unique reviewSnippet semantics; overlap real |
| 32 | test/…/domain/privacy/RetentionRegistryTest.kt | 203 | 10 | 0 | PURE | RetentionRegistry, RetentionTarget | MERGE | P2 | #29 | ~85% tautology; registry built from checked list |
| 33 | test/…/domain/privacy/SafePrivacyMetadataValueSafetyTest.kt | 210 | 21 | 0 | PURE | SafePrivacyMetadata | KEEP | P0 | #28, #26 | value-level leak prevention (JWT/IBAN/path) |
| 34 | test/…/domain/privacy/UPR5CompletionTest.kt | 274 | 7 | 0 | PURE | EffectiveCloudAiPolicyResolver, RawPersistencePolicyResolver, PrivacySettings | MERGE | P1 | #22 (DUP), #21 | keep allowParsedMerchant + cross-source asserts |
| 35 | test/…/domain/provenance/SourceLinkBackfillWorkerTest.kt | 186 | 3 | 0 | MOCKED | SourceLinkBackfillWorker, DatabaseWriteBarrier | KEEP | P0 | (none) | barrier per expense, CE rethrow, error counting |

## Findings (noteworthy files only)

### test/…/domain/privacy/NotificationPrivacyHardeningTest.kt — DELETE (P4)
- Every test builds a local simulation of the intended production flow (local `mutableMapOf`, `if (!privacyDenied) cache[key]=now`, `var extrasRead`) and asserts on that simulation; production `NotificationCaptureService` is never touched (e.g. lines 17-34, 63-79, 128-137).
- Lines 166-186 test `proceedsToCapture()`, a private helper defined inside the test file — pure tautology that mimics `PrivacyDecision.blocksExecution()`.
- Gives false confidence that PRIV-441-05/06/07 behaviors are regression-protected; they are not.
- Action: delete; real decision-mapping coverage should live in a direct PrivacyDecision unit test (see Area gaps). scenarios/PrivacyGateContractTest partially covers the real gate.

### test/…/domain/privacy/RetentionRegistryTest.kt — MERGE (survivor: RawStorageEndToEndTest) + tautology warning
- `retention_registry_contains_all_sensitive_targets` (lines 22-45) constructs `RetentionRegistry(targets)` FROM the required list, then asserts the registry contains it — cannot fail. Does not check the production Hilt multibinding (di/RetentionModule.kt, 10 targets).
- `notification_do_not_store_no_raw_text_in_real_rows` (lines 91-112) and `ocr_…` re-implement the RawStorageMode→stored-value `when` inside the test and assert on that local copy.
- `retention_worker_purges_*` (lines 161-202) assert test fakes return what the fakes return.
- Only `email_metadata_only_no_raw_values_in_real_rows` (129-158) exercises production code — and duplicates RawStorageEndToEndTest. Gap: no real "production RetentionRegistry covers all sensitive targets" exhaustiveness test exists.

### test/…/domain/privacy/PrivacyBehavioralRegressionTest.kt — REWRITE (P1)
- Tautologies: `email_ingestion_does_not_dispatch_transaction_side_effects_twice` asserts `1 == 1` (line 50, "Verified by code inspection"); `email_message_id_hash_never_falls_back_to_plaintext` asserts local simulated values; `retention_ai_artifacts/chat_messages` assert test-owned fakes; notification cache tests simulate logic locally.
- Real value worth keeping: FAIL_CLOSED_DEFAULTS pinning (lines 90-124, distinct from first-run defaults), and `prepareReceiptAssist` MIME allowlist suppression (lines 217-268 — real policy behavior).
- Action: split — move real tests into PrivacySettingsLoadStateTest/CloudPayloadPolicyTest, delete the rest.

### test/…/domain/privacy/RawStoragePolicyAuditTest.kt — MERGE (survivor: RawStorageEndToEndTest)
- ~70% of payload-builder cases duplicate RawStorageEndToEndTest with weaker (non-sentinel) inputs.
- Tautologies: `retention_registry_covers_all_sensitive_targets` (lines 211-226) asserts `requiredTargets.contains(target)` — a set contains itself — plus a `TAG == "DataRetentionWorker"` string check; `cloud_payloads_are_always_prepared_through_policy` (192-206) asserts fields of a payload the test just constructed.
- Unique value: `raw_storage_mode_matrix_is_covered_for_all_sources` (231-256) — resolver matrix over all RawSourceType values; move it to the survivor before deleting.

### test/…/domain/privacy/P8PrivacyFixesTest.kt — STRENGTHEN (P0)
- Contains the strongest direct RawContentSanitizer coverage in the repo: `null_and_empty_distinguished_in_ocr_sanitizer` (lines 275-321) — null-vs-empty per RawStorageMode across all four modes. This partially answers batch 12's "no RawContentSanitizer test" flag.
- `gate_checks_specific_capability` (100-148) — EffectiveCloudAiPolicy.requireAllowed() fail-closed including unknown capability — high value.
- Weak: NEW-P8-001 tests (29-95) exercise the test-owned fake's `synchronized(this)`, not production PrivacySettingsRepositoryImpl; NEW-P8-006 (153-194) tests the test's own try/catch loop, not RetentionRegistry/purge logic.

### test/…/domain/privacy/PrivacyGuardTest.kt — STRENGTHEN (SRCTEXT, deliberate)
- Intentional source-scanning privacy guard (G4/G4b/G4c/G4d/G5): balanced-brace parsing for allow-all gates (lines 66-81), whitespace-robust emptySet detection (189), fail-loud when scan root missing (142, 181, 232 — good fix of a silent-green hazard).
- Residual hazard: `no_hashCode_in_RawContentSanitizer` (95), `privacy_guard_fails_on_messageId_hashCode` (270) and `ExportPrivacyPolicy_encrypted_disabled_does_not_allow_raw_export` (295) still `if (!file.exists()) return` — silently green if the file moves. G8's regex `content.contains("encryptedBackupEnabled.*Allowed.*raw", ignoreCase=true)` can never match multi-line code — vacuous.
- Duplicates `scripts/verify_privacy_boundaries.py` (Segment 39) intent; long-term migrate there, keep this as fast JVM-level guard.

### test/…/domain/privacy/PrivacySettingsLoadStateTest.kt — STRENGTHEN (P0)
- Excellent fail-closed contract pinning: FAIL_CLOSED_DEFAULTS = DO_NOT_STORE on all raw modes + capture/cloud/location off + redactBeforeCloud on (lines 27-76); first-run vs corruption defaults distinct (81-90).
- Weakness: the "repository contract via fake" section (124-189) tests `FakePrivacySettingsRepository` (defined at 194-231) whose corruption→fail-closed mapping is implemented in the fake itself. Production DataStore corruption is covered by data/privacy/PrivacySettingsRepositoryImplCorruptionTest (other batch) — cross-reference, don't duplicate.
- `privacy_viewmodel_shows_corruption_warning_for_corruption` (177-182) never touches a ViewModel despite the name.

### test/…/domain/parser/AppParserRegistryTest.kt — MERGE into AppParserRegistryRoutingTest (confirms prior audit)
- Prior audit (generated batch-003 #71) said merge-and-delete; re-verified against current source: both files construct the identical registry with identical mocks; 6-7 of 8 cases re-test routing outcomes already covered.
- Unique before delete: OTP rejection at registry level (107-116) and grouped-amount confidence-provenance checks asserting 0.95/0.85 from the specific parser rather than generic 0.60 (119-149) — move both into RoutingTest.

### Prior-audit overturns (2026-05 audit vs current source)
- GenericTransactionParserStressTest / GreekBankParserStressTest: prior audit listed both under "Move to Nightly" ("bulk parser stress") and the batch task carried NIGHTLY forward. OVERTURNED: each is 10-11 fast functional tests, no loops, no sleeps, no perf measurement, no @Ignore. They are valuable precisely because they use REAL CurrencyNormalizer/MerchantCleaner/TransferDirectionDetector while the base tests mock those (catches normalizer/cleaner drift). Keep in PR CI; consider renaming `…RealCollaboratorsTest`. The Greek comma / EU-format / US-format grouped-amount money cases (GreekBankParserStressTest:55-70, RevolutParserTest:238-351) are P0 money-correctness guards.
- PriceProtectionTrackerTest: prior audit "KEEP P3, mostly mock verification" → upgraded to STRENGTHEN P2: 3 tests pass vacuously when the list is empty (`if (items.isNotEmpty())` at 124-129, 165-171, 264-268) and `getPriceProtectedItems filters recent receipts` (33-45) is verify-only.

### test/…/domain/provenance/SourceLinkBackfillWorkerTest.kt — KEEP (P0, worker strict area)
- Correctly verifies AGENTS.md worker invariants: write barrier checked per expense (not once at start, lines 95-133), CancellationException rethrown not swallowed (138-159), non-CE errors caught and counted with loop continuation (164-185). Asserts both interaction counts and result counters — not verify-only.

## Area gaps (what is NOT tested in this area)

- **PrivacyBlocked / toPrivacyBlocked: ZERO coverage.** `grep -rl "PrivacyBlocked" app/src/test` returns nothing. The sealed interface standardizing typed denial states for UI (Segment 28, PrivacyBlockedCard consumer) has no test anywhere. P0 gap for the denial→typed-reason mapping.
- **PrivacyDecision has no direct exhaustive unit test** despite "VERY DANGEROUS" status (39+ callers of blocksExecution(), ENGINE_INTERACTION_MAP:238). `blocksExecution()`/`reason()` (PrivacyDecision.kt:17,28) are only exercised indirectly through 21 consumer test files (gates/workers/AI/location). The only "exhaustive mapping" test (NotificationPrivacyHardeningTest:166-186) tests a test-owned replica. A 4-variant × blocksExecution/reason direct test is missing.
- **Production retention-target registration untested:** no test asserts the DI-assembled RetentionRegistry (10 Hilt targets) includes the 9 required sensitive targets; both tests that appear to (RawStoragePolicyAuditTest:211, RetentionRegistryTest:22) are tautologies. Same-style exhaustiveness exists for capabilities (PrivacyCapabilityHandlingPolicyProductionTest) — replicate it for retention targets.
- **DO_NOT_STORE persistence is unit-tested at the payload-factory boundary only in this batch.** The full pipeline (raw text in → parse → DO_NOT_STORE row with null fields, golden scenario from MASTER_TESTING_STRATEGY:128-135) depends on golden/PrivacyDoNotStoreTest, which batch 01 found weak; cross-batch follow-up needed. Suggest one DB-backed (ROOM-style) test through NotificationProcessingPipeline.
- **Parser fixture/golden inputs absent:** all parser tests use inline strings; no recorded-notification fixture corpus (real NBG/Alpha/Eurobank/Revolut samples) drives them. Real-world regressions (NBGReproTest is the lone captured bug) would benefit from a fixtures directory + parameterized golden-parser test.
- **Parser edge cases not covered:** GoogleWalletParser INR grouped/comma amounts; SmsParser null-text-with-title; bigText/subText channels exercised only in TransferDirectionDetectorTest; ambiguous Greek "Χ" prefix only via detector. Currency extraction returns Double with 0.01 delta — fine at parse boundary, but ensure downstream conversion to MoneyAmount is covered by Segment 16 tests (out of batch).
- **PriceProtectionTracker** logic is largely simulated/demo (fixed 8% electronics drop asserted at line 141); no test that stale receipts, zero-price items, or timezone-shifted parsedDate are excluded; timeProvider is stubbed with real System.currentTimeMillis (mild flake risk at midnight boundaries).

## Rollup

- Verdicts: KEEP 20 · STRENGTHEN 7 · MERGE 6 · REWRITE 1 · DELETE 1 · NIGHTLY 0 · UNKNOWN 0 (35 files, 462 tests, 0 @Ignore)
- P0 count: 14 (9 KEEP, 4 STRENGTHEN, 1 MERGE-source... precisely: RevolutParserTest, BackupPrivacyGateOwnershipTest, BankPrivacyHardeningTest, CloudPayloadPolicyTest, EmailRawStorageEnforcementTest, ExportPrivacyPolicyTest, P8PrivacyFixesTest, PR5PrivacyContractTest, PrivacySettingsLoadStateTest, RawPersistencePolicyTest, RawStorageEndToEndTest, ReceiptOcrEmailStorageHardeningTest, SafePrivacyMetadataValueSafetyTest, SourceLinkBackfillWorkerTest)
- DUP pairs found: 6 primary (AppParserRegistryTest↔AppParserRegistryRoutingTest; CloudProviderPreparedPayloadTest↔CloudPayloadPolicyTest; PrivacyCapabilityHandlingPolicyTest↔PrivacyCapabilityHandlingPolicyProductionTest; RawStoragePolicyAuditTest↔RawStorageEndToEndTest; RetentionRegistryTest↔RawStorageEndToEndTest; UPR5CompletionTest↔PR5PrivacyContractTest) + 2 partial (ReceiptOcrEmailStorageHardeningTest↔RawStorageEndToEndTest; BankPrivacyHardeningTest↔RawStoragePolicyAuditTest bank rows)
- P4 (negative value): NotificationPrivacyHardeningTest (only file)
- FRAGILE count: 4 (PR5PrivacyContractTest BankApiIntegration ctor coupling; CloudProviderPreparedPayloadTest CWD-dependent script check; PriceProtectionTrackerTest real clock; AppParserRegistryTest/RoutingTest shared 7-arg registry construction)
- Prior-audit overturns: 2 (parser "stress" tests are fast unit tests — not NIGHTLY; PriceProtectionTrackerTest upgraded KEEP P3 → STRENGTHEN P2). Confirmed: AppParserRegistryTest merge.
- Ledger: no batch file in families F-01…F-21.
