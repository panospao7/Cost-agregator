# A6 — Cross-cutting Kotlin suites + androidTest

Auditor: scout (Sonnet), 2026-10-16. Read-only; nothing run. Persisted by orchestrator.

## 0. Method and confidence
- Counts: scenarios 146/29, golden 44/23 (+GoldenTestBase), e2e 32/16 (+harness), integration 94/11, consistency 104/13, contracts 16/6, verification 67/6, architecture 146/17 (+3 helpers), guard 84/2, guards 4/1, testfixtures 6 helpers. androidTest 391/33 = 248 in-memory DAO, 16 fresh-install parity, 108 migration, 19 other.
- Read in full/relevant part: architecture SourceScanning, CancellationSafety (:95-465), DirectEventDaoInsert (:240-401), WorkerGuardArchitecture, WorkerGuardStaticVerification, Engine5Primitive, RawDao, ExpenseDaoMutationAccess, DeprecatedApi (:1-170), Recurring silent-return sites; all contracts; guard/guards MoneyBoundary (head), GuardSeededViolation, DbGuardPolicyFixture (head); scenarios Csv, BackupRestoreMoneyIntegrity, TransactionLifecycleDbContract, CurrencyRateStaleness, BankSync, RecurringNoDoubleCount, MulticurrencyPartialRate (part); e2e harness, MonthlyTotal, EmptyData, BudgetThresholdAlertE2E, NotificationExpenseDashboardE2E, RecurringPaymentMatchE2E, ReceiptMatchingE2E, NotificationExpenseDashboardPipeline (head); golden GoldenTestBase, HiltGraphSmoke, CsvGolden; integration ExpenseCreationPipeline, CategorizationPipeline (~160 lines), Curr587 (:20-170); consistency FinancialArithmeticPrecision, TimePeriodAnalyticsAlignment; androidTest ExchangeRateDao (device+JVM), DaoStress (head), NestedTransactionSemanticsInstrumented, MerchantKeyBackfillWorker (head), CloudProviderSentinel (head), MigrationContract (head), DatabaseMigrationMatrix (head), DatabaseMigrationTest (:150-250 + assumeTrue sites); Python heads of verify_worker, event_writers, money, privacy, ui_dao, raw_money_aggregates, time_boundaries, deprecation_escalations, cancellation.
- (names): remaining scenarios, verification bodies, most consistency, other golden, Analytics/ReceiptProcessing/BudgetAlert/GroupSettlement pipelines, SharedExpenseFlow, other androidTest DAO files.
- Not verified: full Python parity (spot-checks only — db_access/worker/cancellation/event_writers claims are needs-confirm); androidTest per-test runtimes (none persisted); these Kotlin suites are mostly < 1 s/case except WriteBarrier 97.6 s.

## 1. Summary per suite
| Suite | Files | Tests | Est. removable | Est. mergeable | Headline |
|---|---|---|---|---|---|
| scenarios | 29 | 146 | ~16 | ~29 (+23 relocatable) | Mostly DAO insert-then-read via test seeder; several don't test what their name says |
| golden | 24 | 44 | 3 | ~7 | Real Room; best suite — keep |
| e2e | 17 | 32 | 0 | 5 | 6 `*E2ETest` real Room (genuine); Flow/Pipeline tests use mock DAOs |
| integration | 11 | 94 | ~44 | ~10 | 37 pure-utility `isNotEmpty` checks; Curr587 data-class echoes |
| consistency | 13 | 104 | ~15 | ~15 | merchant-key/amount-parser asserted 5 ways; self-referential time tests |
| contracts | 6 | 16 | 10 | 3 | String-contains scans weaker than Python/behavioural proof |
| verification | 6 | 67 | ~6 | ~25 | engine parity duplicated 3-way |
| architecture | 17 | 146 | ~8 | ~25 | real scanners; time bombs, silent returns, doubled allowlists |
| guard | 2 | 84 | 11 | ~45 (strict) | MoneyBoundary = JVM wrapper over Python-tested script; DbGuardPolicyFixture = YAML-count change-detector |
| guards | 1 | 4 | 4 | — | tests retired `.kts` scripts nothing runs |
| **Kotlin total** | | ~737 | **~117** | **~150–175** | |
| androidTest | 33 | 391 | 0 | 0 | ~248 DAO tests portable to JVM; 9 migration tests permanently skipped; migration/parity/nested-tx/org.json/PDF/ML genuinely device-only |

## 2. Non-KEEP table

### scenarios (all Robolectric sdk 28)
| File | Bucket | Evidence | Action | Saved | Risk |
|---|---|---|---|---|---|
| `scenarios/CsvExportImportRoundtripTest` (3) | DELETE | No CSV code called; seeder insert + `getExpensesBetween` (:54-160) | Delete (golden CSV test covers rule) | 3 | low |
| `scenarios/BackupRestoreMoneyIntegrityScenarioTest` (3) | DELETE + MERGE | No backup/restore. T1 (:56-69) migration registration (⊂ `MigrationRegistrationTest`, python migration_matrix); T2 (:75-99) `assertNotNull` on non-null accessors; T3 DAO round trip | Delete T2/T3; fold T1 | 3 | **med — PINNED evidence-map.md:20 (update first)** |
| `scenarios/TransactionLifecycleDbContractTest` (4) | DELETE | KDoc says it tests the seeder (:18-28); "verify deduplication" asserts no dedup (:97-147). Real contract: `TransactionLifecycleCoordinatorDbContractTest` | Delete (already in prior delete-candidates doc) | 4 | low |
| `scenarios/CurrencyRateStalenessScenarioTest` (4) | DELETE 2 + MERGE 2 | T1 asserts a description string; T3 constant arithmetic; T2/T4 mock-store converter | Drop T1/T3; move T2/T4 to `domain/currency` | 2 | low |
| `scenarios/MulticurrencyPartialRateScenarioTest` (6) | DELETE 4, STRENGTHEN 1 | T1/2/5/6 test `MoneyAmount`/helper (dup of `MoneyAggregateConversionScenarioTest` :464); **T4 (:175-220) pins a raw mixed-currency sum 95.50 as "dashboard total" — pins forbidden behaviour** | Delete 4; delete or invert T4 | 5 | low |
| scenarios BankSync, LocationMap, EmailReceipt, InvestmentPortfolio, GroupSettlementLifecycle, SharedExpenseGroup, ReceiptPreOcrDedupe, RecurringNoDoubleCount, ReceiptLifecycleDbContract | MERGE (DAO CRUD) | insert + getById; "DAO inserts blindly", "Coordinator integration is deferred" (ReceiptLifecycle:32); RecurringNoDoubleCount :122-179 is a DAO filter | Fold into per-DAO DaoTests | ~29 (+9 Robolectric DB startups) | med — `ReceiptLifecycleDbContractTest` evidence-map.md:17 seed; keep event-log/link-orphan cases |
| `scenarios/BackupRestoreContractTest` (23) | RELOCATE | Pure unit tests of RestoreMaintenanceMode/Journal/Bundle/Verifier; brittle `reports 59 table names` (:483) | Move to `data/backup/`; assert table set vs source of truth | 0 | PINNED (ledger 1138-1266) |

### golden / e2e
| File | Bucket | Evidence | Action | Saved | Risk |
|---|---|---|---|---|---|
| `golden/HiltGraphSmokeTest` (3) | DELETE | `assertNotNull` on constructed objects (:28-96); can't catch Hilt graph failure; own KDoc "NOT golden tests" | Replace with real Hilt graph test or delete | 3 | low (golden.* CI job scope) |
| `golden/CsvExportImportRoundtripGoldenTest` (1) | STRENGTHEN | Builds Room but only calls pure `CsvCellSanitizer`; no import | Drop base class, keep golden JSON | ~0.3 s | low; PINNED evidence-map.md:23 |
| `golden/RecurringBillPaymentMatchTest`, `RecurringPlannedActualNoDoubleCountGoldenTest`, `e2e/RecurringPaymentMatchE2ETest` | MERGE (names) | Same rule × 3 real-Room tests | Reviewer picks pinned-number ones | ~3 | med (lifecycle money) |
| e2e MonthlyTotal/DailyAverage/CategoryBreakdown/DateBoundary/EmptyData Flow (1 each) | MERGE | Mocked DAOs in harness (FlowPipelineTestHarness.kt:89-120); MonthlyTotalFlowTest:41/:47 asserts a value equals itself | One "pipeline wiring" parity class | 4 files | low |
| `e2e/NotificationExpenseDashboardPipelineTest` (3) | STRENGTHEN | 43 mockk uses; real-Room `NotificationExpenseDashboardE2ETest` covers path | Relabel as mock pipeline test | — | low |

### integration / consistency / verification
| File | Bucket | Evidence | Action | Saved | Risk |
|---|---|---|---|---|---|
| `integration/ExpenseCreationPipelineIntegrationTest` (16) | DELETE | Utilities only; `isNotEmpty` asserts (:38, :217, :226); wall-clock (:175-191) | Delete after moving 3–4 meaningful cases into util unit tests | 16 | low-med |
| `integration/CategorizationPipelineIntegrationTest` (21) | DELETE | Same utilities/inputs; admits documented bug then asserts `isNotEmpty` (:33) | As above | ~17 | low |
| `integration/Curr587BehavioralTest` (14) | DELETE ~10 | Data-class echoes (:24-124); "no XXX in production source" scans nothing (:128-139); dup `CurrencyNormalizationPost9a6Test` :80 | Keep 3 StaleRatePolicy (move to core/money) | ~11 | low |
| `consistency/TimePeriodAnalyticsAlignmentTest` (6) | DELETE | "AnalyticsViewModel uses same logic" never references AnalyticsViewModel; `currentTimeMillis()` | Delete | 6 | low |
| consistency MerchantKeyConsistency/CrossConsumer/SharedUtility/CrossParser/DuplicateLogic | MERGE | MerchantKeyGenerator properties asserted ≥5 places | One contract class; keep `DedupeKeyProducerConsistencyTest`, `ConstantsConsistencyTest` | ~15 | low |
| verification CrossSource/GoldenMaster/CrossGroupIntegration + e2e flows | MERGE (names) | Same parity assertion 3 places; GoldenMaster self-dups (:469/:792, :491/:696, :329/:627, :352/:615) | One parity matrix; keep numeric oracles | ~6 + ~25 | med |
| verification Carbon/Lifestyle/SharedExpense | RELOCATE | Domain unit tests under verification/ | Relocate | — | — |

### contracts
| File | Bucket | Evidence | Action | Saved | Risk |
|---|---|---|---|---|---|
| `contracts/MoneyContractTest` (1) | DELETE | "Safe" if file contains any `.currency` (:32-47); python G-MONEY-RAW-02 strictly stronger | Delete | 1 | low |
| `contracts/LifecycleBarrierContractTest` (2) | DELETE (needs-confirm) | substring `writeBarrier` | Delete after parity confirmation | 2 | med |
| `contracts/RecurringDeactivateContractTest` (5) | DELETE | substring checks (:38-71); contradicts golden ("cancels" vs "deletes"); ⊂ `golden/RuleDeactivationCleanupTest`, `RecurringArchitectureGuardTest` :415 | Delete | 5 | low |
| `contracts/PrivacyStorageContractTest` (3) | STRENGTHEN | T3 (:62-70) can't meaningfully fail | Delete T3, keep T1/T2 | 1 | med |
| `contracts/CancellationPropagationContractTest` (4) | MERGE + STRENGTHEN | `size>=10` of a constant (:128-134); token evidence; silent `continue` (:107-113) | Delete size test; merge entry points into `CancellationSafetyArchitectureGuardTest` | 1–3 | **PINNED** (ledger :1121/:1494, CHANGELOG.md:10, CI_GUARDRAILS_BASELINE.md:217) |
| `contracts/SideEffectContractTest` (1) | KEEP | "no `dispatchOn*` inside `withTransaction`" — no Python equivalent | Keep | — | **sole proof** |

### architecture / guard / guards
| File | Bucket | Evidence | Action | Saved | Risk |
|---|---|---|---|---|---|
| SourceScanning 4 inline negatives (:205-216, :219-232, :235, :251) | DELETE | re-implement `contains` inline; never call production scanner | Delete or route through `scanSource` | 4 | low |
| SourceScanning `negative_fixture_expired_allowlist_entry_fails` (:671-685) | DELETE | asserts 2020-01-01 is before now | Delete | 1 | none |
| SourceScanning `all_coroutine_worker_files_contain_guard_call` (:267) | DELETE | ⊂ `WorkerGuardArchitectureGuardTest` + python G-WORKER-01 | Delete | 1 | low |
| SourceScanning `workers_with_broad_catch_must_rethrow_cancellation` (:545-609) | STRENGTHEN | 5 allowlist entries expired 2026-09-30, never evaluated | Remove dates or enforce | — | low |
| `CancellationSafetyArchitectureGuardTest` (22) | MERGE w/ python | **Expiry theatre**: fixed `GUARD_TODAY_VALID = 2026-09-15` (:230) → never red; 13 raw entries expired 2026-10-01; two hand-synced allowlists (~100 Kotlin + Python YAML) | Real clock (red now) or drop dates; one allowlist long-term | — | med |
| `RecurringArchitectureGuardTest` (22) | STRENGTHEN | **16 silent passes** `if (!x.exists()) return` (:168, :212, :224, :310, :322, :347, :383, :395, :407, :417, :429, :439, :451, :462, :476, :487); source-text change-detectors (:427, :405) | `return` → fail; delete change-detectors already proven by migration/coordinator tests | ~8 | med; PINNED DB_WRITE_OWNERSHIP.md:184 |
| `Engine5PrimitiveGuardTest.noNewDirectWallClockInDomainCore` (:93-111) | DELETE | python G-TIME-01 strictly stronger | Delete; keep PeriodRange (:57) and raw `CurrencyCode(` (:127) rules (unique) | 1 | low |
| `RawDaoArchitectureGuardTest` (1) | DELETE (needs-confirm) | 2 DAOs only; db_ownership_policy covers all | After Python confirmation | 1 | med |
| DirectEventDaoInsert, TransactionContextProvenance, SourceScanning allowlists | STRENGTHEN | time bombs (§3) | Renew before 2026-11-21 | — | — |
| `WriteBarrierArchitectureGuardTest` | SLOW + time bomb | 16 temp exemptions (:143-158) duplicate `config/guards/db_ownership_policy.yml` entries with `barrierMode` (spot-checked WorkerRunLogger :7284, PrivacyAuditLoggerImpl, CsvExpenseImporter) | See §6 | up to 97.6 s | med-high; reviewer-strict |
| `guards/GuardSeededViolationTest` (4) | DELETE | Matches a regex defined in the test (:11-34, :56-80); target `.kts` scripts in `scripts/guards/` referenced by nothing (retired) | Delete; orphaned `.kts` deletion needs user approval | 4 | low; update CI_GUARDRAILS_BASELINE.md:231 |
| `guard/MoneyBoundaryGuardTest` (11) | DELETE | Shells out to `py -3 scripts/verify_money_boundaries.py` (:30-37); `scripts/test_verify_money_boundaries.py` + money leg already cover | Delete after fixture parity check | 11 | low-med |
| `guard/DbGuardPolicyFixtureTest` (73) | MERGE (strict) | Hand-written YAML parser, pinned counts ("65-entry", "429 entries"); re-synced every GR tranche; Python has structural-manifest tests | Map each case to Python, then retire pinned-count cases | up to ~45 | **PINNED** GR00-GR04_validation_checklist.md:99/105; db_ownership_policy.legacy.yml:969/1003 |

## 3. Kotlin scanner vs Python guard overlap
| Kotlin test | Python guard | Verdict |
|---|---|---|
| WriteBarrierArchitectureGuardTest | db_access (`barrier_proof.py`, `barrierMode`) | weaker (not proven); exemptions duplicated |
| WorkerGuardArchitectureGuardTest + WorkerEntryPointProof | verify_worker_boundaries rule 1 | **equal** (two implementations) |
| SourceScanning guard-call / DAO-in-worker | G-WORKER-01 rules 1-2 | weaker (substring) |
| SourceScanning BroadcastReceiver DAO | — | **unique** |
| SourceScanning workId/runAttemptCount, requiresNotificationPermission, requiredCapabilities, blockedPolicy RETRY, DataRetention capabilities, ReceiptMatching permission | — | **unique (sole proof)** |
| SourceScanning `schema_version_matches_latest_json_file` | verify_migration_matrix.py | probably equal (needs-confirm) |
| WorkerGuardStaticVerificationTest | — | unique (brittle hard-coded 10) |
| CancellationSafetyArchitectureGuardTest | G-CANCEL-01/02/03 | weaker for rules; unique for sanitizer evidence forms + `launch` rule |
| CancellationPropagationContractTest | G-CANCEL-01 | weaker |
| DirectEventDaoInsertGuardTest | verify_event_writers rules 6-11 | unique for 7 extra DAOs until db_access coverage confirmed |
| TransactionContextProvenanceGuardTest | — | unique |
| RawDaoArchitectureGuardTest | db_access policy | weaker (needs-confirm) |
| ExpenseDaoMutationAccessTest | db_access `RestrictedExpenseDaoMutation` | partial |
| Engine5 wall-clock | G-TIME-01 | weaker |
| Engine5 PeriodRange import, raw `CurrencyCode(` | — | unique |
| DeprecatedApiArchitectureGuardTest | — (deprecation guard checks changelog) | unique |
| BankPrivacyMode / BackupRestore / BudgetAutopilot architecture guards, GsonR8KeepRulesContractTest | — | unique |
| RecurringArchitectureGuardTest | partly db_access | mixed |
| MoneyContractTest | G-MONEY-RAW-02, currency_guardrails.ps1 | weaker |
| LifecycleBarrierContractTest | db_access barrier proof | weaker |
| SideEffectContractTest | — | unique |
| MoneyBoundaryGuardTest | verify_money_boundaries + its pytest | equal (duplicate runner) |
| DbGuardPolicyFixtureTest | test_verify_db_access*, known_good_state, gradle_db_guard_contract | partial duplicate |

### Silent-pass scanners
- RecurringArchitectureGuardTest: 16 sites.
- CancellationPropagationContractTest: `continue` at :107-113.
- SourceScanning — no non-empty assertion on filtered set: :292, :367, :392, :438, :463.
- contracts/* use bare `File("src/main/java/...")` relative to cwd.

### Hard-coded expiry dates
| Where | Dates | Enforced by | Goes red |
|---|---|---|---|
| WriteBarrierArchitectureGuardTest.kt:143-158 | 2026-10-31 (16 UNASSIGNED) | :635 `now()` | **2026-11-01** |
| DirectEventDaoInsertGuardTest.kt:70-232 | 2026-11-20 (7 LEGACY_REPOSITORY), 2026-12-31 (~25) | :369 `now()` | **2026-11-21**, 2027-01-01 |
| DirectEventDaoInsertGuardTest :390-400 | rolling 75 days | `now()+75d` | by design |
| SourceScanningArchitectureGuardTest.kt:52-92 | 2026-12-31 (6) | :498-505 | 2027-01-01 |
| TransactionContextProvenanceGuardTest.kt:34-64 | 2026-12-31 (6) | :172-176 | 2027-01-01 |
| SourceScanning :555-587 | 2026-09-30 (5, past) | not enforced | never (dead) |
| CancellationSafetyArchitectureGuardTest.kt:76-214 | 2026-12-31 (~100), 2026-10-01 (13, past) | fixed clock (:230-232) | never (decorative) |

## 4. androidTest classification
| Class (tests) | Class | Evidence | Action |
|---|---|---|---|
| DatabaseMigrationTest (76) | DEVICE-ONLY, keep; **9 permanently skipped** | `assumeTrue(hasSchema(1\|6..10) && hasSchema(51\|34))` at :211, :504, :527, :552, :567, :590, :621, :649, :852 — no snapshots below v33; KDoc :219 says "114 (current)" vs schema 151 | **User decision:** convert to assertTrue (will fail) or delete as dead; report as skipped |
| DatabaseMigrationMatrixTest (10), Migration148to149ContractTest (2), MigrationContractTest (20) | DEVICE-ONLY, keep | real MigrationTestHelper | Review pre-baseline (<145) contracts vs DatabaseSchemaPolicy |
| FreshInstallBatch8ParityTest (11), FreshInstallIndexParityTest (5) | DEVICE-ONLY, keep | schema parity | Keep |
| DedupeKeyUniquenessRegressionTest (3) | PORTABLE | in-memory unique index | Move to JVM |
| ExchangeRateDaoTest (4), RecommendationDaoTest (4) | **DUPLICATE** | JVM twins with same cases | Delete one copy |
| 18 DAO classes (ExpenseDao 40, ComplexQuery 18, BudgetDao 18, GroupMember 16, SavingsGoal 16, UserCorrection 16, MerchantNormalization 15, AiArtifact 14, MerchantLocation 14, PendingReview 8, RecurringExpense 7, ScannedReceipt 7, Category 6, AiChatMessage 5, AiChatSession 5, ExpenseGroup 4, MerchantCategory 4, Warranty 4) | PORTABLE | `inMemoryBuilder` + DAO calls | Move to src/test; keep small device smoke set |
| DaoStressTest (20) | SLOW + FLAKY | wall-clock thresholds, 10k inserts, `delay` concurrency | Correctness asserts only; keep concurrency semantics |
| NestedTransactionSemanticsInstrumentedTest (3) | DEVICE-ONLY, keep | framework SQLite twin | Keep |
| CloudProviderAndroidOrgJsonSentinelInstrumentedTest (10) | DEVICE-ONLY, keep | Android org.json | Keep |
| MerchantKeyBackfillWorkerTest (3) | DUPLICATE, mock-only | JVM twin exists | Delete if JVM twin covers same cases |
| AccountingExportPdfRepositoryInstrumentedTest (2) | DEVICE-ONLY, keep | PdfDocument | Keep |
| OnDeviceReceiptAssistServiceImageInstrumentedTest (1) | DEVICE-ONLY, keep | image/ML | Keep |

Device-time saving if DAO set moves to JVM: unknown (no per-class timings); rough guess ¼–⅓ of 16 min, low confidence.

## 5. PINNED and sole-proof
Citations (no hits in FG gate doc, AGENTS.md, scripts/, denylist, known-hanging; TEST_FAILURE_LEDGER only BackupRestoreArchitectureGuardTest):
- evidence-map.md:17/20/23 — ReceiptLifecycleDbContractTest, BackupRestoreMoneyIntegrityScenarioTest, BackupRestoreContractTest, CsvExportImportRoundtripGoldenTest (soft "seeds").
- release-1.0 verification ledger :1121, :1138-1266, :1494 — CancellationPropagationContractTest, BackupRestoreContractTest, P7BugFixesTest, AppStartupCoordinatorRecoveryTest.
- docs/backup-restore-barrier-contract.md:130/136 — BackupRestoreArchitectureGuardTest. DB_WRITE_OWNERSHIP.md:184 — RecurringArchitectureGuardTest. GR00-GR04 checklist + legacy policy YAML — DbGuardPolicyFixtureTest. CANCELLATION_POLICY.md, CANCELLATION_ATOMICITY_BASELINE.md — CancellationSafetyArchitectureGuardTest. TRANSACTIONAL_EVENT_POLICY.md:490 — TransactionContextProvenanceGuardTest.
- CI_GUARDRAILS_BASELINE.md:215-232 — 18 Kotlin guard classes (stale numbers).
- GOLDEN_TESTS_CI_GATE.md:11/32 — CI runs the whole `golden.*` package (moving tests changes scope).
- Prior pruning lists already name several candidates.

Never remove (sole proof):
- Money: FinancialArithmeticPrecisionTest; golden Multicurrency*, StaleRateCurrencyConversion; DedupeKeyProducerConsistencyTest; ConstantsConsistencyTest.
- Lifecycle: TransactionLifecycleCoordinatorDbContractTest, TransactionLifecycleFullContractGoldenTest, TransactionTargetedUpdateSideEffectsTest, SideEffectContractTest, ConcurrentOccurrenceClaimTest, RuleDeactivationCleanupTest, ReceiptMatchingNoDoubleCountGoldenTest.
- Backup/restore: BackupRestoreArchitectureGuardTest, BackupRestoreRoundtripGoldenTest, RestoreBlocksAllWritesTest, WorkerRestoreBarrierIdempotencyGoldenTest, BackupRestoreContractTest (relocate only).
- Privacy: PrivacyDoNotStoreTest, PrivacyGateEnforcementGoldenTest, BankPrivacyModeArchitectureGuardTest, PrivacyStorageContractTest T1-T2, SourceScanning privacy-capability tests.
- Workers: WorkerGuardStaticVerificationTest, WorkerGuardArchitectureGuardTest + WorkerEntryPointProof(+Test), SourceScanning notification-permission/capabilities/blockedPolicy/workId.
- Migration: all androidTest migration, fresh-install parity, NestedTransactionSemantics, org.json sentinel.

## 6. Recommended order
1. **Calendar items** (owner decisions): WriteBarrier exemptions (red 2026-11-01); DirectEventDaoInsert LEGACY (red 2026-11-21); 2027-01-01 set; CancellationSafety real clock vs drop dates.
2. **Zero-risk deletions** (needs approval): GuardSeededViolation, HiltGraphSmoke, Curr587 echoes, ExpenseCreation/CategorizationPipeline integration, TimePeriodAnalyticsAlignment, SourceScanning inline negatives + 2020 test, CancellationPropagation size test, CurrencyRateStaleness T1/T3, MulticurrencyPartialRate helper tests, scenarios TransactionLifecycleDbContract + CsvExportImportRoundtrip; update CI_GUARDRAILS_BASELINE.md, evidence-map.md.
3. **Silent-pass fixes** (no deletion).
4. **Subsumed but guard-adjacent** (reviewer-strict parity first): Engine5 wall-clock, RawDao, MoneyContract, LifecycleBarrierContract, RecurringDeactivateContract, MoneyBoundaryGuard, WriteBarrier (+A1 pre-filter), DbGuardPolicyFixture shrink, CancellationSafety allowlist unification.
5. **Merges**: merchant-key family, GoldenMaster dups, parity matrix, e2e flows, recurring trio, scenario DAO-CRUD → DaoTests, BackupRestoreContract relocation.
6. **androidTest**: delete ExchangeRate/Recommendation device copies; decide 9 skipped migrations; fix DaoStress timing; move portable DAO classes to JVM; confirm MerchantKeyBackfillWorker twin; re-measure connected-tests.
7. reviewer-strict for step 4 and anything touching workers/barrier/backup/privacy; validate targeted-unit-test then static-guards.
