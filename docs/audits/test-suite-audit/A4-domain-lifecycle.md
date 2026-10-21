# A4 — Kotlin unit tests: domain lifecycle / privacy / workers / parsers

Auditor: scout (Sonnet), 2026-10-16. Read-only; no build/test run. Persisted by orchestrator. Paths relative to `app/src/test/java/com/yourname/expensetracker/domain/`.

## 0. Method and confidence
- Scope: 19 packages, 2,139 TSV rows. Runtime small (~25 tests > 0.3 s) → count/LOC is the cost.
- "(read)" rows: ~30 files opened (BitmapConcurrency, NotificationPrivacyHardening, PrivacyBehavioralRegression 1–165, P8PrivacyFixes, P9RemainingWorkerFixes, PrivacyCapabilityHandlingPolicy(+Production), WorkerRunLogger samples, WorkerRestoreRegression 286–460, FileWorkerTerminalDiagnosticSink 170–585, WorkerIdempotency, WorkerGuardVerifier, ServiceDiagnostics, NotificationIntakeEnqueueFailure, PostCommitActionBatch, DiagnosticSideEffectEventWriter, BankApiIntegration 274–330, SpendingHeatmapEngineStress, CurrencyConversion 340–482, CurrencyConverterStress, Categorization/ContextualInference stress heads, TransferDirectionDetector, ReceiptParser(+OcrPatterns) heads, RawStorageEndToEnd head, RawStoragePolicyAudit, PrivacyGuard 1–135, ai/model, 3 empty `@Ignore` shells).
- "(names)": TransactionLifecycleCoordinator*, TransactionSideEffectPlanner, Receipt*Planner*, ReceiptMatchLifecycleService, RecurringRuleLifecycleCoordinator, other Bank*, Notification*Coordinator*, ai/usecase (unopened), ai/policy, groups, export, location (except heatmap), Revolut/GoogleWallet/Sms parsers, remaining privacy, WorkerLeaseRegistry, WorkerBarrierIntegration, WorkerExecutionGuard bodies.
- No per-worker copy-paste under `domain/workers` (shared infra only; per-worker tests are in app-level `workers/`).
- No direct domain/privacy vs data/privacy duplicates, **but the real settings-corruption → FAIL_CLOSED path appears unproven in both places.**
- Older pruning lists exist: `docs/testing/generated/TEST_PRUNING_CANDIDATES.md`, `docs/testing/testing findings new/dead-and-marginal-tests.md`.
- All privacy/worker/money/lifecycle rows → reviewer-strict.

## 1. Summary
| Area | Files | Tests | Est. removable | Est. mergeable | Main drivers |
|---|---|---|---|---|---|
| receipt | 32 | 354 | ~15 | ~50 | BitmapConcurrency 13; parser tables |
| privacy | 27 | 307 | ~40 | ~45 | 3 local-simulation files; raw-storage matrix |
| workers | 13 | 250 | ~15 | ~50 | guard/logger outcome matrices |
| ai | ~33 | 225 | 0 | ~12 | model validation; AiPolicy truth tables |
| parser | 11 | 179 | ~4 | ~55 | TransferDirectionDetector; non-stress "Stress" |
| categorization | 8 | 144 | ~10 | ~45 | loops-in-one-test, no-crash |
| transaction | 5 | 112 | 0 | ~25 | update matrix, side-effect planner |
| currency | 6 | 88 | ~8 | ~6 | constants, delegation, dup drift |
| location | 6 | 75 | ~3 | ~14 | heatmap |
| bank | 6 | 72 | ~2 | ~4 | |
| groups | 8 | 69 | 0 | ~7 | |
| export | 6 | 68 | 0 | ~14 | CSV/IIF sanitizer duplicates |
| notification | 6 | 62 | ~3 | 0 | ignored file |
| recurring | 4 (+1 shell) | 52 | 0 | 0 | |
| sideeffect | 6 | 50 | 0 | ~10 | |
| reminder/debug/naturallanguage/receiptmatching | 6 | ~45 | 0 | ~7 | |
| **Total** | **~190** | **~2,140** | **~100 (~2,300 LOC)** | **~280 (~3,000 LOC)** | |

Net ~2,140 → ~1,760 (−18%), ~5,000 LOC. 3 empty `@Ignore` shell files deletable.

## 2. Non-KEEP files

### 2a. STRENGTHEN (fail-closed / atomic / cancellation assertions that cannot fail) — most important
| File | Evidence | Action | Risk |
|---|---|---|---|
| `privacy/NotificationPrivacyHardeningTest.kt` (all 10) | Builds local vars/ifs and asserts them: :17–58 local `mutableMapOf` cache; :63–125 local `extrasRead`; :128 assigns true then asserts true; :172–186 test-local `when`. Claims PRIV-441-05/06/07 acceptance | Replace with 3–4 tests driving real `NotificationCaptureGate`/`NotificationCaptureService` (check `service/NotificationCaptureService*Test` first); delete the 10 | high value; reviewer-strict |
| `privacy/PrivacyBehavioralRegressionTest.kt` | Tautologies :29, :45, :54, :78; :129/:141/:151 re-implement `isPackageBlockedFast` (symbol not in `src/main`); :90/:104/:115 copy `FAIL_CLOSED_DEFAULTS`. Real: :61, :165, :180, :193, :217, :244 | Delete 7, merge 3→1; add real corruption→`CorruptedFailClosed` repo test; fix `PRIVACY_UI_ARCHITECTURE.md:104` (inaccurate) | high value |
| `workers/WorkerRestoreRegressionTest.kt` :417 | `acquired+blocked == 2`, `activeLeaseCount in 0..2` true by construction; `runTest` sequential | Deterministic race (latch), assert real invariant | med |
| `workers/WorkerRunLoggerTest.kt` :207 | `async` under single-threaded `runTest`; passes without the Mutex | Real concurrency with start latch, or drop (rely on :224/:237) — needs-confirm | med |
| `workers/FileWorkerTerminalDiagnosticSinkTest.kt` :281 | `setReadOnly()` on a dir is a no-op on Windows → no failure exercised; :478/:546 no-throw only | Inject failing append | med |
| `privacy/PrivacyGuardTest.kt` :95/:114 | `if (!exists()) return` → silent pass if cwd changes | Fail instead | low |
| `bank/BankApiIntegrationTest.kt` :291 | Name "triggers pending review"; only asserts idempotency hash | Assert review path or rename | med |
| `privacy/RawStoragePolicyAuditTest.kt` :192/:220 | Asserts own data-class fields; `else -> Unit` | Delete (see 2b) | |
| `debug/ServiceDiagnosticsTest.kt` :136 | Ignores return of `await(30s)` | Assert true | low |

### 2b. DELETE-CANDIDATE
| File | Evidence | Action | Saved | Risk / PINNED |
|---|---|---|---|---|
| `receipt/BitmapConcurrencyTest.kt` (13) | Exercises `kotlinx.coroutines.sync.Mutex` only, no production class; real delays (~4.9 s) | Delete (needs instrumented test if bitmap serialization matters) | 13 | low; only plan text cites it |
| `receipt/lifecycle/ReceiptLifecycleBugFixesTest.kt`, `ReceiptLifecycleHardeningTest.kt`, `recurring/RecurringLifecycleFixesTest.kt` | Empty `@Ignore` classes, 0 tests | Delete 3 files | 0 | low |
| `notification/capture/NotificationIntakeEnqueueFailureTest.kt` | Class `@Ignore` (hangs); subsumed by `...RoomTest`; unique: :207 retry row surfaces after `nextAttemptAt` | Move :207 to Room test; delete | 3 (+1 moved) | PINNED: ledger RG17B-1 (`release-1.0-verification-ledger.md:492`), remediation R13 — update docs |
| `privacy/PrivacyCapabilityHandlingPolicyTest.kt` | Test-local `policyMap`; subsumed by `...ProductionTest.production_privacy_capability_policy_covers_all_enum_values` | Delete; re-cite docs | 3 | PINNED doc: `SENSITIVE_DIAGNOSTICS_POLICY.md:106`, `TEST_INDICATED_PRODUCTION_BUGS.md:134` |
| `privacy/P8PrivacyFixesTest.kt` | :29/:67 fake repo (tests lambda counter, not Mutex); :153 own try/catch loop. Real: :100, :199, :241, :275, :326 | Delete 3 (confirm "purge continues" covered by `RetentionRegistryTest`/`DataRetentionWorkerTest`) | 3 | med |
| `workers/P9RemainingWorkerFixesTest.kt` | :263/:269 `maxOf(1L, 60_000L)`; :52/:63/:36 constant pins; 6 idempotent tests (:84–193) copy `WorkerRunLoggerTest.duplicate_*` | Delete 2; fold 6 into one table; keep :200 | 2 + 7−1 | low |
| `workers/WorkerIdempotencyTest.kt` :22 | Reads `DEFAULTS` twice and compares | Delete | 1 | low |
| `workers/WorkerRestoreRegressionTest.kt` :289–365 | Mocks barrier to throw and asserts it throws; "eight workers" stale (10) | Delete 4 (real coverage `WorkerBarrierIntegrationTest` :130–207, :224) | 4 | PINNED class — trim only |
| `currency/CurrencyConversionTest.kt` :340–447 | literal list, constant, `coVerify` delegation, negatives dup `CurrencyConverterEdgeCaseTest` :95/:115 | Delete 5 | 5 | low |
| `currency/CurrencyConverterStressTest.kt` | 500× loops; drift dup of EdgeCase :135 | Delete | 2 | low |
| `sideeffect/PostCommitActionBatchTest.kt` | Tests data-class `copy`; subset tests | Merge to 3 | 4 | low |
| `bank/BankApiIntegrationTest.kt` :278/:315 | Constant pin; tautology on constants | Delete 2 | 2 | low |
| `receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt` :1363 | Tests its own setup stub | Delete 1 | 1 | low |
| `privacy/RawStoragePolicyAuditTest.kt` (12) | Duplicates `RawStorageEndToEndTest` + `RawPersistencePolicyConsolidationTest` matrix; 2 vacuous | Delete after confirming each case exists elsewhere (move email :147) | 12 | med/high (privacy); reviewer-strict |
| `parser/TransferDirectionDetectorTest.kt` :386–445 | "90% accuracy" tolerates failures; 3 collection-size tests; 1 mislabeled | Delete 4, rename 1 | 4 | low |
| `categorization/CategorizationEngineStressTest.kt` | 12 no-crash `assertNotNull`; :117/:275 assert nothing definite; :489/:586 perf/fuzz | Delete 5, collapse 12 into one table | 7 | low |
| `location/SpendingHeatmapEngineStressTest.kt` :367–540 | Wall-clock asserts; `Math.random()` inputs; weak :360 | Delete 3 timing; fixed seeds | 3 | low (flaky today) |
| `categorization/ContextualInferenceEngineStressTest.kt` :618/:634 | Perf budgets | Delete 2 | 2 | low |

### 2c. MERGE
| File / cluster | Evidence | Action | Saved | Risk |
|---|---|---|---|---|
| `receipt/ReceiptParserOcrPatternsTest.kt` (59) | Single-input keyword/spacing/date/OCR-garble cases; :90 assertNotNull only | ~4 tables (~24 tests) | 35 | low-med (money parsing) |
| `receipt/ReceiptParserTest.kt` (21) | Decimal ×4, greek normalization ×6 overlap OcrPatterns | Fold; keep :25, :192, :254, :270 | 10 | low |
| `parser/TransferDirectionDetectorTest.kt` (52) | 40 single-input tests | 4 tables | 34 | low |
| `parser/GenericTransactionParserStressTest` + `...Test` | Not stress; overlap | Merge | 5 | low |
| `parser/GreekBankParserStressTest` + `...Test` | Same shape; weak tests | Merge | 4 | low |
| `parser/NBGReproTest.kt` | Bug repro w/ println | Into `GreekBankParserTest` | 1 file | low |
| `categorization/ContextualInferenceEngineStressTest.kt` (37) | Loops-in-one-test, `in listOf(...)` | 3 tables + 2 weekday tests | 18 | low |
| `categorization/SemanticKeywordMatcherStressTest`, `MerchantCanonicalizerStressTest` | Overlap `CategorizationComponentsTest` | Merge | 6 | low |
| `categorization/CategorizationComponentsTest.kt` (40) | Keyword ×6, suffix ×6, greek ×4 | Tables | 10 | low |
| `workers/WorkerExecutionGuardTest.kt` (49) | :781–987 same 6 scenarios × 2 entry points; :1055–1130 checkpoint repeats | Parameterised (outcome × policy × entry point), all 24 combinations stay rows | 14 | PINNED (denylist :19) |
| `workers/WorkerRunLoggerTest.kt` (60) | terminal ×6, Durable ×6, classifyDiagnostic ×10, sanitize ×3, reason ×5 | Tables; row-for-row | 22 | PINNED |
| `workers/FileWorkerTerminalDiagnosticSinkTest.kt` (22) | 5 "does_not_store_*" can't fail (no such fields); timeout pair dup | One JSON key allow-list test (fails on new field); merge timeout | 5 | PINNED — keep allow-list |
| `workers/WorkerLeaseRegistryTest.kt` | reset-stop ×3 | Merge | 3 | med |
| `WorkerRestoreRegressionTest` / `WorkerGuardVerifierTest` | registry-name coverage ×2 | Keep one | 2 | low |
| `transaction/lifecycle/TransactionLifecycleCoordinatorUpdateTest.kt` (36) | 6 ops × (changed, unchanged, missing) | 3 parameterised; keep collisions + CA-P-02-003 | 12 | med (lifecycle) |
| `transaction/lifecycle/TransactionSideEffectPlannerTest.kt` (29) | kind × scope; trust matrix | 2 tables | 12 | low-med |
| `sideeffect/DiagnosticSideEffectEventWriterTest.kt` (15) | skip ×4, failed ×2, cancelled ×2, envelope ×4 | Table + envelope test | 7 | low |
| `sideeffect/SideEffectMetadataFactoryTest.kt` | null ×3 | Table | 3 | low |
| Raw-storage payload files (`ReceiptOcrEmailStorageHardeningTest`, `RawStorageEndToEndTest`, `BankPrivacyHardeningTest` payload, `PR5PrivacyContractTest` sanitize) | source × mode same shape | One sentinel-based `RawStoragePayloadMatrixTest` (~49 → 24) | 20 | high (privacy) |
| `privacy/RawPersistencePolicyTest` vs `SafePrivacyMetadataValueSafetyTest` / Consolidation matrix | duplicates | Delete dups from RawPersistencePolicyTest | 6 | med |
| `PR5PrivacyContractTest` / `UPR5CompletionTest` / `PrivacyGuardTest` | fail-closed defaults ×3; cloud policy ×8 | One defaults test, one policy table | 5 | med |
| `currency/CurrencyConverterGoldenTest` + `CurrencyConversionTest` | same cases | Merge | 2 | low |
| `currency/ConversionSemanticsHardeningTest.kt` (30) | stalePolicy ×6, validDate ×3 | Tables | 5 | med (money) |
| `groups/GroupBalanceCalculatorTest` vs `GroupSettlementBalancePolicyTest` | same 4 rules | Policy test owns; calculator = wiring | 4 | med (money) |
| `groups/SharedExpenseManagerTest` + `GroupUseCasesTest` | same rejections | One owner | 3 | low |
| `export/CsvEscapingTest` (28) + `CsvCellSanitizerNegativeAmountTest` (13) | formula-injection prefixes repeated; escape ×7, IIF ×7 | Sanitizer table + IIF table + format test; every prefix stays a row | 14 | med (security) |
| `location/SpendingHeatmapEngineStressTest.kt` (32) | single-fixture boundary cases | Tables | 10 | low |
| `location/*StressTest` + `*NormalizedTest` | not stress; overlap | Rename/merge | 4 | low |
| `ai/policy/AiPolicyTest.kt` | truth tables ×4, ×3 | Tables | 5 | low |
| `naturallanguage/...DefaultWindowBoundaryTest` | 3 dates | Table | 2 | low |
| `ai/model/*Test.kt` (5 files) + InputBuilder | one-class-per-file validation | 2 files | files −3 | low |
| `debug/ServiceDiagnosticsTest.kt` | single-call counters | Table; fake prefs (drop Robolectric) | 4 | low |
| `bank/BankConnectionLifecycleCoordinatorOutcomeTest.kt` | FeatureUnavailable ×3 | Table | 2 | low |
| `receipt/ReceiptOcrTempNameTest.kt` | overlaps | 4 → 2 | 2 | low |
| `receipt/lifecycle/ReceiptSideEffectPlanner{Claim,EphemeralItems,StructuredData,Warranty,SuggestionRace}Test` | 5 files, one class | 1–2 files, keep every test | files −4 | low |

### 2d. SLOW
| File | Evidence | Action |
|---|---|---|
| `receipt/BitmapConcurrencyTest` | real delays ~4.9 s | Delete |
| `workers/FileWorkerTerminalDiagnosticSinkTest` :522/:546 | real 500 ms timeout vs 2 s delay | Inject timeout / virtual time |
| `debug/ServiceDiagnosticsTest` | Robolectric for SharedPreferences | Fake prefs |
| `NotificationIntakeEnqueueFailureRoomTest`, `RetentionRegistryCoverageTest`, `ReceiptDuplicateFingerprintCollisionTest` | Robolectric + real Room (legit SQL) | Keep |
| `CategorizationEngineStressTest` :81 | 10k concurrent categorizations | Delete |

## 3. Consolidation clusters
- **C1 Raw-storage payload matrix:** ~120 → ~55 tests; keep static-guard half of `RawPersistencePolicyConsolidationTest` unchanged.
- **C2 Local-simulation privacy files** (~25 tests asserting their own locals): replace with 4–6 real tests vs `NotificationCaptureGate`, `PrivacySettingsRepositoryImpl` (corruption → fail-closed), retention continue-after-failure.
- **C3 Non-stress "Stress" parser/categorization/location files:** fold into matching `*Test`.
- **C4 Worker-contract tables:** ~95 → ~45, every outcome still a row.
- **C5 Coordinator operation matrices.**
- **C6 Money parsing tables:** ~140 → ~55.
- **C7 Naming:** misleading Stress/Repro/Deep names fixed by merge.

## 4. PINNED and sole-proof
PINNED: `WorkerExecutionGuardTest` (denylist :19, evidence-map :18); `TransactionLifecycleCoordinatorTest` (denylist :9 — do not touch); `WorkerRunLoggerTest`, `WorkerBarrierIntegrationTest` (evidence-map, SENSITIVE_DIAGNOSTICS_POLICY:111); `FileWorkerTerminalDiagnosticSinkTest` (:111); `WorkerRestoreRegressionTest`, `ReceiptPartialOcrPipelineTest`, `BankStatementSourceBoundaryTest` (ledger, remediation plan 299/317); `CompositePrivacyGateTest` (ledger:236; sole proof gate rethrows cancellation without auditing); `PrivacyCapabilityHandlingPolicyTest` (re-cite Production test); `UPR5CompletionTest`; `PrivacyBehavioralRegressionTest`, `PR5PrivacyContractTest` (PRIVACY_UI_ARCHITECTURE.md:104–105); `PrivacyGuardTest` (raw-storage-policy.md:135); `RawPersistencePolicyConsolidationTest` static guards; `NotificationIntakeEnqueueFailureTest` (RG17B-1, R13). Not grepped individually: `docs/ci/db-mediation/*.yml`, `WORKER_BASELINE_INVENTORY.md` — re-grep final list before deleting.

Sole proofs — keep:
- Cancellation: `ReceiptAssetStoreCancellationTest`; `AssetCleanupCoordinatorTest.cancellation from asset delete propagates`; `ReceiptLifecycleCoordinatorTest` cancellation; `ReceiptLinkServiceNestedFailureTest.standaloneLink_portThrowsCancellation...`; `ReceiptMatchLifecycleServiceTest` event cancellation; `PostCommitActionRunnerExtensionsTest.cancellation_exception_rethrows`; `TransactionSideEffectFailureEventWriterTest` :102; `WorkerLeaseRegistryTest.cancelling drain propagates...`; `WorkerExecutionGuardTest` :471/:1034/:1157; `BankApiIntegrationTest` :493; `RecurringLifecycleCoordinatorTest.cancellationExceptionPropagatesFromReconciliation`.
- Atomic writes: `ReceiptLinkServiceColumnScopeTest`, `ReceiptLinkServiceNestedFailureTest`, `RecurringRuleLifecycleCoordinatorTest` rollback tests, `BankConnectionLifecycleCoordinatorOutcomeTest`, `TransactionLifecycleCoordinator*`, `CategoryAssignmentServiceBarrierTest`.
- Duplicate detection: `ReceiptDuplicateFingerprintCollisionTest`, `ReceiptDuplicateDetectorTest`.
- Real privacy fail-closed: `PrivacyCapabilityHandlingPolicyProductionTest` (composite gate unhandled capability; cloud provider secondary ctor), `PrivacyCapabilityPolicyTest` (controlled code; bounded registry), `RawPersistencePolicyConsolidationTest.forSource_*`, `BackupPrivacyGateOwnershipTest`, `PrivacyBehavioralRegressionTest` :165–244.
- `WorkerBarrierIntegrationTest` (real-mode versions).
- PRIV-441-05/06/07 must end up proven by a real test before `NotificationPrivacyHardeningTest` is deleted.

## 5. Recommended order
1. Mechanical zero-risk deletes (~20 files/tests).
2. Privacy honesty pass (reviewer-strict): real gate/service/repo tests replacing local simulations; together with A2 §2c corruption rewrite.
3. Worker tables (reviewer-strict): C4.
4. Parser/categorization/receipt tables (C3, C6) — largest source reduction.
5. Raw-storage matrix (C1) — reviewer-strict, last.
6. Coordinator/side-effect matrices, CSV merge, groups merge, renames, NotificationIntakeEnqueueFailureTest removal, SLOW fixes.
7. targeted-unit-test via validation-runner per batch.
