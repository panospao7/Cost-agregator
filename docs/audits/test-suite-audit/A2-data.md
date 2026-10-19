# A2 — Kotlin unit tests under `app/src/test/java/com/yourname/expensetracker/data/**` (read-only audit)

Auditor: scout (Sonnet), 2026-10-16. Read-only; no build/test run. Persisted by orchestrator.

## 0. Method and confidence
- Scope: 172 test files (+ helper `MigrationTestInstrumentation.kt`). `@Test` grep = 1,700 occurrences; runtime TSV has 1,755 rows for `data.*` (parameterized expansion).
- Read in full / relevant parts: ~35 files (ExpenseEntity, CategoryRepository, BudgetRepository, NotificationProcessingPipeline clusters, barrier & backup-journal, privacy). For the rest: listed every test name and read selected bodies to confirm; rows marked (names) are name-based plus partial read.
- Unopened bodies (assertion depth not judged): most AI Cloud*/OnDevice* service tests, `WarrantyTrackerRepositoryTest`, `MultiCurrency*`, `ReceiptRepository*`, `ReviewQueueRepositoryTest`, `EmailReceiptIngestionServiceTest`, DAO CRUD tests.
- Rows touching money / privacy / lifecycle-atomicity / backup-restore / migration need a strict-reviewer pass before acting.

## 1. Summary
| Metric | Estimate |
|---|---|
| Files reviewed | 172 |
| Tests reviewed | ~1,700 |
| Non-KEEP files | ~75 |
| Removable (DELETE-CANDIDATE) | ~195 tests, ~3,400 LOC |
| Mergeable (table-driven / duplicates) | ~170 tests, ~1,500 LOC (range 150–190) |
| Total reduction | ~365 tests (~21% of `data/`), ~4,900 LOC |

- ~145 of the deletable tests are already `@Ignore`d (128 in 7 "StressTest" classes, 17 in `SecureKeyStorageTest`): no runtime saved, but removes dead weight from count and ignored-test budget.
- Slow wins: `P7BugFixesTest.backup_bundle_closes_stream_on_exception` 21.0 s (`repeat(50)` extractions on a garbled file); real retry backoff in Cloud*/Geocoding/Overpass ~25 s (`CloudReceiptAssistServiceTest` 11.0 s, `CloudQueryInterpretationServiceTest` 4.2+2.8 s, `GeocodingRetryHttpSemanticsTest` 3.4+3.3 s); `BackupEncryptionServiceTest` ~0.8 s/test from real 600k-iteration PBKDF2 (14 tests ≈ 11 s). ~45–55 s fixable.
- **Vacuous tests worse than missing (false assurance on atomicity/privacy):** `GroupTransactionCoordinatorTest` atomicity tests assert only inside `if (groupId > 0)`; `PrivacySettingsRepositoryImplCorruptionTest` never touches the real repository; `AppOperationalStateTest` defines its own state mapping; `ExpenseDaoBoundaryConsistencyTest` compares local longs; `DomainTransactionRunnerTest` tests a fake it defines.

## 2. Non-KEEP files

### 2a. DELETE-CANDIDATE
| File | Evidence | Action | Saved | Risk |
|---|---|---|---|---|
| `database/entity/ExpenseEntityStressTest.kt` | Class `@Ignore` :17; all 42 dead. :25–112, :344–402, :533–552 duplicate `DedupeKeyTest`; :118–218 duplicate `EffectiveAmountConsistencyTest`/`MoneyContractTest`/`GoldenMasterVerificationTest`; :225–337 read back ctor args; :419/:429 no assertions | Delete after moving 2 unique cases into `DedupeKeyTest` (locale-ROOT invariant under de/el; 1.255→"1.26" rounding :394) | 42 (net ~39) | low; update `TEST_IGNORE_CLASSIFICATION.md` row |
| `repository/NotificationProcessingPipelineStressTest.kt` | `@Ignore` :9; tests a local simulator (`runPipeline`, `makeRoutingDecision` :508–540); :53 asserts `result != null \|\| result == null` | Delete | 28 | low |
| `repository/NotificationRepositoryStressTest.kt` | `@Ignore`; "returns flow"/"calls DAO" (:87–290) | Delete | 21 | low |
| `repository/ExpenseRepositoryStressTest.kt` | `@Ignore`; 13 smoke tests subsumed by `ExpenseRepositoryTest`/`...TruncationTest`/`...MerchantKeyBackfillTest` | Delete | 13 | low |
| `repository/ReceiptRepositoryStressTest.kt` | `@Ignore`, Robolectric, 12 mock-only tests; covered by `ReceiptRepositoryBatchDuplicateTest`, `...StatementDuplicateTest`, `ReceiptItemCategorizationMoneyPersistenceTest` | Delete (confirm no unique OCR-fallback assertion) | 12 | low-med |
| `repository/ReviewQueueRepositoryStressTest.kt` | `@Ignore`; :110–119 "returns flow"/`assertNotNull` | Delete | 8 | low |
| `repository/CategoryRepositoryStressTest.kt` | `@Ignore`; "add many categories"/"learn many merchants" no assertions | Delete | 4 | low |
| `security/SecureKeyStorageTest.kt` | `@Ignore("AndroidKeyStore not available...")` :37; mocks `EncryptedSharedPreferences`; 14 flagged tautological | Delete (optionally one fake-backed contract test) | 17 | low |
| `database/dao/ExpenseDaoBoundaryConsistencyTest.kt` | 14/15 compare local `Long`s (:124, :192, :287) or `listOf(...).isNotEmpty()` (:66, :100, :395); only :451 real | Delete; move :451 to `TimePeriodUtils` test; add real half-open boundary test vs in-memory DAO | 14 | low |
| `database/DomainTransactionRunnerTest.kt` (partial) | 6 "fake runner" tests (:139–247) exercise in-file fake (:115); :98 `assertNotNull(runner)` | Delete 7; keep `TransactionContext` (:33–69) and `CancellationSafe` (:74–93) as one table | 7 | low |
| `backup/AppOperationalStateTest.kt` | `modeToState` (:22) is test-local; :79 asserts stub; :85 reads back mutated flow | Delete; one table test vs real `RestoreMaintenanceMode.operationalStateFlow` if not covered | 7 | low-med |
| `backup/MaintenanceSafeDiagnosticSinkTest.kt` (4/5) | :28/:40/:63 no-throw only; :74 verifies mock vs itself | Delete 4, keep at most :48 | 4 | low |
| `store/ExpenseStoreTest.kt` | 2 relaxed-mockk "delegates_to_dao" (:25, :32) | Delete; note in `TEST_FAILURE_LEDGER.md` F-09/F-14 | 2 | low |
| `location/AndroidForegroundLocationProviderTest.kt` | :10 asserts a pair equals itself | Delete | 1 | low |
| `ai/provider/HybridReceiptItemCategorizationServiceTest.kt` | Subsumed by `HybridRouterIntegrationTest.receiptItemCategorizationUsesOnlyTheSelectedRoute` | Delete | 1 | low |
| `repository/RecommendationRepositoryTest.kt` (partial) | Pure delegation :89, :100, :113, :125, :299, :341, :354, :366; `saveAll` tests (:136–282) real | Delete ~8 delegation tests | 8 | low |
| `repository/AiArtifactRepositoryImplTest.kt` (partial) | 7 tautological delegation tests (:97–215); keep :171 null-expiry, :227 source-scan | Delete/merge | 6 | low |
| `currency/ExchangeRateStoreAdapterTest.kt` (partial) | :36, :59, :82, :115 delegation; barrier tests :127–190 real | Delete 3–4; table barrier tests | 3 | low |
| `database/entity/MileageTrackingValidationTest.kt` | Construct + assert field | Delete | 1 | low |
| `ai/provider/internal/CloudRetryPolicyTest.kt` :13 | Re-asserts constants | Delete :13 | 1 | low |
| `backup/CostbackupBundleLimitsTest.kt` :313 | `default limits are sane` | Delete | 1 | low |

### 2b. MERGE (table-driven / duplicate scenarios)
| File | Evidence | Proposed action | Saved | Risk |
|---|---|---|---|---|
| `repository/NotificationProcessingPipelineReliabilityTest.kt` | :1066/:1080 verbatim copies of `OversizedAmountTest` :54/:69 | Delete copies; fold Oversized/SourceLink/Atomicity/Reliability into `NotificationProcessingPipelineTest` sections | 2 | low |
| `repository/NotificationProcessingPipelineOversizedAmountTest.kt` | 5 cases (:54–130) differ only in input/expected | One table | 3 | low |
| `repository/NotificationProcessingPipelineAtomicityTest.kt` | :247/:259/:289 same contract, different fixtures | Table; absorb `...SourceLinkTest` | 2 | med (lifecycle) |
| `repository/BudgetRepositoryStressTest.kt` | Misnamed validation smoke (:122–318), no assertions :256/:274; large-history (:336–508) duplicates `...TruncationTest`/`BudgetRolloverTest` | Fold validation into `BudgetRepositoryValidationTest` table; keep one large-history case; delete :256, :274, :285, :303 | 12 | med (money) |
| `repository/BudgetRepositoryTruncationTest.kt` | WARNING/CRITICAL :281/:306; 3 PURCHASE-filter tests (:348–425) share fixture | Table + `BudgetRepositoryAggregateTest` | 3 | med |
| `repository/BudgetRepositoryHistoricalStatusTest.kt` | 9 `addBudget rejects/accepts` (:219–292) | Table | 6 | low |
| `repository/ExpenseRepositoryTruncationTest.kt` | Cap trio ×3 methods (:87–206) | One forEach per method | 5 | low |
| `repository/ReviewQueueRepositoryNestedRollbackTest.kt` | 3 entrypoints × 3 scenarios = 9 near-identical (:637–818) | Table `entrypoint × scenario`; keep :563/:592 | 6 | med (atomicity — parameterize, keep all cases) |
| `repository/MerchantNormalizationRepositoryTest.kt` | 5 `linkAliasToCanonical maps code N` (:27–67) | Table | 4 | low |
| `repository/RecurringExpenseRepositoryTest.kt` | 3 identical calculator tests | Table | 2 | low |
| `repository/SavingsSweepAcceptanceCoordinatorTest.kt` | 7 `rg05a1_*` (:506–604) differ only in setup | Table | 4 | med (money) |
| `repository/DatabaseBackupRepositoryImplTest.kt` | 2,879 lines/78 tests/6 concerns; pairs :189/:194, :199/:207, :250/:276; timestamp trio :2634/:2661/:2726 | Split into ExportGate/ImportPipeline/AssetRestore/FailureMatrix/DatabaseStats/TimeProvider; table pairs; **keep source-scan :2798 (pinned)** | 7 | med-high (restore) — move, don't reduce |
| `backup/P7BugFixesTest.kt` | 1,681 lines/37 tests, bug-number name; MaintenanceMode persistence (:297–1195), CostbackupBundle IO (:1243–1500), `countRows` (:1505+); overlaps `RestoreMaintenanceModeConfirmationFaultTest`, `RestoreAbortWorkerResumeDeferredTest`, `DatabaseBarrierTest`; :1317 21 s and doesn't assert stream closing | Split into `RestoreMaintenanceModePersistenceTest` + `CostbackupBundleExtractFailureTest`; loop 50→~3 or verify `close()` on mocked stream | 6 | med (backup) |
| `backup/DatabaseBarrierTest.kt` | 5 single-mode tests (:47–86) subsumed by table :115; export/normal reads duplicate `ExportReadBarrierTest`; Robolectric only needed for :89 | Merge `ExportReadBarrierTest` in as `(mode, policy)` table; isolate :89 | 10 | med (PINNED) |
| `backup/ExportReadBarrierTest.kt` | :36/:49/:93 overlap | Into `DatabaseBarrierTest` | 4 | PINNED |
| `backup/RestoreJournalTimeProviderTest.kt` | :39/:47/:64/:79 same shape | Table; absorb into `RestoreJournalTest` with snapshot/atomicity round-trips | 3 | low |
| `backup/BackupVerifier{Manifest,RequiredSemanticQuery,SavingsLedger}Test.kt` | One subject, 3 files; heavy fixtures (:122–137, 1.2–2.2 s each) | One `BackupVerifierTest`, shared fixture | 1 | low |
| `backup/CostbackupBundleLimitsTest.kt` | `createdAt` fallback (:196/:215/:233/:252) same rule twice | Keep 2 | 2 | low |
| `database/GroupTransactionCoordinatorTest.kt` | :389/:524 assert only inside `if (groupId > 0)`, no fault injected; :414 not concurrent; :512 no assertion (2.5 s); RG04A-1 (:989/:1016) duplicate `GroupsRepositoryImplTest` :533/:581 | Delete :389/:524, add real fault-injecting rollback test; fold :477 into :296; give :512 an assertion; table RG04A-1 once. **:255 rollback stays** | 5 | med (lifecycle) |
| `database/MigrationRegistrationTest.kt`, `Migration148to149Test.kt`, `DatabaseMigrationProofTest.kt` | Chain coverage asserted 3× (Registration :60, :75; Proof :101); `schema_version_is_151` brittle; `Migration148to149Test` also holds 149→150 | Dedupe chain assertions into `DatabaseMigrationProofTest`; rename; replace constant test with `@Database(version)` check | 2 | high (migration; `MigrationRegistrationTest` PINNED; keep every per-version test) |
| `database/dao/BankConnectionDaoTest.kt` | 6 single-field `disconnect clears X` (:87–142) subsumed by :153 | Fold into :153 | 6 | low-med |
| `privacy/RetentionPerimeterTargetTest.kt` + `RetentionTargetPurgeTest.kt` | Cutoff/idempotent cases duplicate DAO tests (`OperationRunDaoPurgeTest`, `ReceiptEventDaoTest`, `TransactionEventDaoTest`, `PrivacyAuditDaoTest`) | Keep target-level wiring tests; share fixture | 6 | med (privacy; each target keeps ≥1 cutoff test) |
| `privacy/DataRetentionWorkerTest.kt` | 5 `*_retention_disabled` (:461–511); 4 diagnostics (:420/:526/:545/:561); cancellation dup :351/:441 | Table + one diagnostics test | 5 | med |
| `privacy/KeystoreInstallationSecretHashingTest.kt` | PII corpus (:242–335) unrelated to hasher; 4 fail-closed variants; :353 class-exists | Move corpus to `CloudPayloadRedactorTest`; table fail-closed; delete :353 | 3 | med |
| `privacy/BackupEncryptionServiceTest.kt` | 2 round trips (:21/:32); 3 corruption cases; each real 600k PBKDF2 | Merge; shared derived key or test iteration count; keep KDF pin :96 | 3 | med |
| `database/entity/CategoryTest.kt` | 5 single-assert `assertThrows` (:18–41) | Table | 4 | low |
| `database/converter/ConvertersTest.kt` | :11 ⊂ :18; :26/:31 one table | 4→2 | 2 | low |
| `database/model/ExpenseWithCategoryFormatted{Amount,Time}Test.kt` | 6 prefix-by-type + 3 amount cases | Table | 4 | low |
| 8 DAO tests (Investment, EmailReceipt, RawNotification, RecurringOccurrence, PrivacyAudit, ReceiptEvent, TransactionEvent, BackgroundJobRun) | insert / query-by-id / all-fields / empty repeated | One insert+read-back per entity; keep ordering/boundary/idempotency/unique-index | ~14 | low |
| `ai/provider/HybridServiceDelegationTest.kt` | Route tests overlap `HybridRouterIntegrationTest`; `usedImageInput` ×3 | Fold into one `HybridRoutingTest` with `HybridDedupeJudgeServiceTest`, `SmartReceiptAssistServiceTest` | 5 | low-med (keep DISABLED → no cloud) |
| `ai/provider/CloudQueryPreparedPayloadTest.kt` | Same contract as parameterized `CloudProviderTransportPayloadTest` (:229–259) | Add Query as an `Entry` | 5 | med (privacy) |
| 8 `OnDevice*`/`Cloud*ServiceTest` files | Copy-pasted trio: bounded UNKNOWN_ERROR w/o sentinel leak, CancellationException propagation, parseResponse fences/invalid/non-finite | Shared parameterized `AiServiceErrorContractTest`; keep unique parse cases | ~14 | med (sentinel proof must hold per service) |
| `ai/provider/OnDeviceCategorizationAssistServiceTest.kt` | 8 `parseResponse returns null when X` + 3 prompt `includes` | Table | 8 | low |
| Cloud* retry tests | 500/429/408 differ only in status; real backoff | Table + injected no-op sleeper | 5 | low |
| `ai/worker/DailyBriefingWorkerTest.kt` | 7 `timeout_after_delivery_*` (:566–757); 5 reschedule (:307–372); :193 ⊂ :161 | Table/sections | 6 | med (worker) |
| Email provider parsers (Amazon/Apple/Uber + base) | Same cases repeated per provider | Shared `EmailReceiptParserContractTest`; keep provider-specific cases | ~8 | med (PINNED to LEGAL_PATHS) |
| `email/EmailReceiptIngestionServiceTest.kt` | blank/whitespace/unknown messageId (:270–322); 3 fingerprint tests | Table | 4 | PINNED; keep all fingerprint scenarios |
| `location/GeocodingRetryHttpSemanticsTest.kt` | 4 identical "RateLimited after three 429" (0.9–3.4 s each) | Table, no real backoff | 3 | low |
| `location/CompositeGeocodingServiceStressTest.kt` | **REVIVE**: `@Ignore` stale; 8 real routing/dedupe/ranking/cancel tests; main test file has 1 test | Merge into `CompositeGeocodingServiceTest`, drop `@Ignore` | +7 live | low |
| `location/MerchantKeyBackfillWorkerTest.kt` / `ExpenseRepositoryMerchantKeyBackfillTest.kt` | CAS overlap (worker :162/:177 vs repo :89/:136) | Keep repo CAS tests; worker keeps retry/progress | 2 | med |
| `location/internal/LogSanitizerTest.kt` | 3 trivial hash props | One test | 2 | low |
| `AiChatRepositoryImplTest`, `SubscriptionManagementRepositoryTest`, `GroupsRepositoryImplTest` | Delegation-only checks | Add outcome assertions | 2 | low |

### 2c. STRENGTHEN
| File | Evidence | Action | Risk |
|---|---|---|---|
| `privacy/PrivacySettingsRepositoryImplCorruptionTest.kt` | Builds its own DataStore + handler (:50/:86/:106/:154) — never exercises `PrivacySettingsRepositoryImpl`; asserts only `FAIL_CLOSED_DEFAULTS` constant 4×; :176 asserts `"CORRUPTED" != null` | Replace with 2 tests writing corrupt bytes into the real repo's DataStore, asserting `CorruptedFailClosed` + defaults | **high value — real corruption path may be unproven** |
| `GroupTransactionCoordinatorTest` :389/:524 | See 2b | Spy DAO with failing member insert | med |
| `P7BugFixesTest` :1317 | Claims stream closed, checks exception types only | `verify { close() }` | low |
| `ExpenseDaoBoundaryConsistencyTest` replacement | See 2a | Real in-memory DAO inclusive/exclusive end | low |
| `CategoryRepositoryTest` :47/:63/:72/:83 | Verify-only | Assert cache invalid on next read | low |
| `MultiCurrency*`, `WarrantyTrackerRepositoryTest` (names) | :109 flagged; confidence boundaries (:641–767) table candidate | Review; table (~4) | low |

### 2d. SLOW (fixable)
| File | Cause | Fix | Saves |
|---|---|---|---|
| `P7BugFixesTest` :1317 | `repeat(50)` with PBKDF2 | Cut loop | ~20 s |
| Cloud*/Geocoding/Overpass retry tests | Real backoff | Injected no-op sleeper / virtual time | ~25 s |
| `BackupEncryptionServiceTest` | 600k PBKDF2 per test | Shared key / test iteration count | ~6–8 s |
| `DatabaseBarrierTest` | Class-level Robolectric for one test | Split | startup |
| `PrivacySettingsRepositoryImplCorruptionTest` | Robolectric for constant checks | Drop Robolectric | startup |
| ~35 DAO classes | Robolectric + per-test in-memory DB | No cheap fix; merging reduces count | n/a |

## 3. Consolidation clusters
| Production class | Current test files | Proposed |
|---|---|---|
| `NotificationProcessingPipeline` | Atomicity, Reliability, OversizedAmount, SourceLink, Stress (ignored) | One `NotificationProcessingPipelineTest` (signals/atomicity/reliability); delete Stress |
| `BudgetRepository` | Stress, Truncation, HistoricalStatus, Rollover, Diagnostics, SuggestionsBatch | `BudgetRepositoryValidationTest`, `BudgetRepositoryStatusTest`, `BudgetRolloverTest`; Diagnostics/SuggestionsBatch stay |
| `ExpenseRepository` | Test, Truncation, Stress (ignored), MerchantKeyBackfill | Drop Stress; Truncation → section; keep backfill CAS file |
| `ReviewQueueRepository` | Test, NestedRollback, Stress (ignored) | Drop Stress; table NestedRollback |
| Notification/Receipt/Category repos | ignored Stress files | Delete |
| `DatabaseBackupRepositoryImpl` | 2,879-line test + RestoreAbort/AssetRestoreAtomicity/ReceiptAssetRepeatRestore | 6 files by concern; source-scan test stays |
| `RestoreJournal` | Durability, ImporterFailure, TimeProvider, AssetSnapshot, AssetRestoreAtomicity | `RestoreJournalTest`, `RestoreJournalDurabilityTest`, `RestoreJournalImporterTest` |
| `RestoreMaintenanceMode` / barriers | P7 (persistence half), DatabaseBarrier, ExportReadBarrier, AppOperationalState, ConfirmationFault, MaintenanceOperationRunner, sink tests | Persistence, DatabaseBarrier (table), ConfirmationFault, OperationRunner, DataStore sink |
| `BackupVerifier` | 3 files | `BackupVerifierTest` |
| `CostbackupBundle` | Limits, P7 (IO half) | `CostbackupBundleTest` |
| `Expense.generateDedupeKey` | DedupeKeyTest, ExpenseEntityStressTest (ignored) | `DedupeKeyTest` + 2 migrated cases |
| Retention targets | Purge, Perimeter, Worker, CheckpointStore + DAO purge parts | Target-level matrix; DAO files keep SQL semantics |
| AI routing | Router integration, ServiceDelegation, ReceiptItemCategorization, DedupeJudge, SmartReceiptAssist | `HybridRoutingTest` |
| AI service error contract | OnDevice/Cloud *Service tests | Parameterized `AiServiceErrorContractTest` + unique parse tests |
| Cloud transport/payload | TransportPayload, QueryPreparedPayload, ReceiptAssistAudit | `CloudProviderTransportPayloadTest` + Query |
| Email parsers | Base + Amazon/Apple/Uber | Contract test + provider files (PINNED) |
| `CompositeGeocodingService` | Test, Stress (ignored) | One un-ignored test |
| Geocoding retry | RetryHttpSemantics, Overpass, Nominatim | Retry table in one file |
| Migrations | Registration, 148to149, MigrationProof, SavingsSweepMonthMigration | Keep 4, dedupe chain checks, rename |
| Savings | Acceptance, ContributionHistory, SweepPlanDao, MonthMigration | Keep; table `rg05a1` |

## 4. PINNED (keep unless the reference moves)
- `config/validation/known-hanging-tests.json` HANG-003: `data.backup.ExportReadBarrierTest` (+ `TEST_FAILURE_LEDGER.md` F-16/F-14 for DatabaseBarrier/ExportReadBarrier). review_by 2026-10-31.
- `docs/architecture/ARCHITECTURE.md` :134, :1886–1887: `ExchangeRateDaoTest`, `ExpenseRepositoryTest`, `FinancialWeatherRepositoryTest`.
- `docs/architecture/LEGAL_PATHS.md` :277–280: Email parser tests + `EmailReceiptIngestionServiceTest`, `EmailReceiptIngestionServiceTransactionTest`.
- `docs/ci/CI_GUARDRAILS_BASELINE.md` :251: `MigrationRegistrationTest`.
- `docs/time/time-boundary-guard.md` :250, `docs/development/TIME_SEMANTICS.md` :291: source test at `DatabaseBackupRepositoryImplTest` :2798.
- `docs/testing/TEST_IGNORE_CLASSIFICATION.md`: the `data/` StressTest classes (doc-only; `verify_ignored_test_budget.py` fails only on increases, so deletions are safe; nothing in `.github/` or `scripts/` references `*StressTest*`).
- `TEST_FAILURE_LEDGER.md` F-09/F-14: `ExpenseStoreTest` (historical).
- Not pinned by AGENTS.md, FINAL_CI_GUARD_ACCEPTANCE_GATE.md, `config/guards/*` or `release_block_denylist.yml`.
- Sole-proof tests to keep: NestedRollback cases; `GroupTransactionCoordinatorTest` :255; `BackupEncryptionServiceTest` KDF pin + wrong-password; fail-closed hasher proofs; per-version migration tests; `BankConnectionDaoTest` :153; each retention target's cutoff test.

## 5. Recommended order
1. Delete ignored StressTest files, `SecureKeyStorageTest`, `ExpenseDaoBoundaryConsistencyTest`, `AppOperationalStateTest`, `DomainTransactionRunnerTest` fakes, `ExpenseStoreTest`, `AndroidForegroundLocationProviderTest` (~190 tests, ~3,400 LOC) after moving the 3 unique cases.
2. Slow fixes (P7 loop, retry sleeper, KDF): ~45–55 s.
3. Fix vacuous privacy/atomicity tests.
4. Table-driven merges.
5. Splits/renames last.
6. Money/privacy/backup/migration rows → reviewer-strict before editing.
