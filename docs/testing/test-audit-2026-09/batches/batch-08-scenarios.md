# Batch 08 — scenarios

Scope: `test/java/com/yourname/expensetracker/scenarios/` — multi-pipeline "scenario" tests across Segments 3, 4, 7, 8, 9, 10, 14, 15, 16, 17, 18, 19, 24/25, 29, 38 · Files: 31 · LOC: 8436 (174 @Test, 0 @Ignore)

Reviewer notes:
- **Seeding split.** 23 files build real in-memory Room DBs via `AppDatabaseTestFactory`; 9 of those use the canonical `testfixtures/scenario/ScenarioSeeder` path (BackupRestoreMoneyIntegrity, CsvExportImport, MixedCurrencyCore, MoneyAggregateConversion, MulticurrencyPartialRate, NotificationPipeline, RecurringNoDoubleCount, TransactionLifecycleDb, TransactionTargeted[test 4]); 4 also use `ScenarioAssertions`. 8 files hand-roll setup (BackupRestoreContractTest uses Robolectric context only; GroupLifecycle×2, Investment/Tax golden, Money/Currency/Privacy files are JVM-level). **In 4 files the seeding is decorative** — data is seeded but never read into the asserted aggregates (MixedCurrencyCore, MoneyAggregateConversion, MulticurrencyPartialRate tests 1/2/5/6, and TransactionLifecycleDb which tests the seeder itself).
- **Ledger:** `TEST_FAILURE_LEDGER.md:20` says the scenarios package was never measured ("ui/scenarios/golden/e2e … NOT yet run") — no F-family ID is confirmed here. Adjacent risks: F-02 (lifecycle coroutine harness, shared with #29/#31) and F-05 (TaxEstimator assertion drift, #28).
- **Prior-audit re-verification (2026-05 `TEST_PRUNING_CANDIDATES.md` / `batch-004.md`):** PrivacyGateEnforcementScenarioTest (prior DELETE P4) and SpeechInputGatewayLifecycleTest (prior DELETE P4) are **confirmed deleted** from the package. Four verdicts overturned below (MapMarker MOVE→DELETE; BackupRestoreMoneyIntegrity KEEP P0→STRENGTHEN P2; MixedCurrencyCore KEEP→MERGE; MoneyAggregateConversion KEEP P0→REWRITE). GroupLifecycleScenarioTest grew 14→33 tests since the prior audit and absorbed prior `GroupGoldenScenarioTest`.
- **Systemic FRAGILE pattern:** 6 files hand-wire `TransactionLifecycleCoordinator`'s 14-arg constructor with relaxed MockKs (GroupLifecycle×2 duplicating ~50 LOC of wiring each, InvestmentGolden, TaxGolden, TransactionLifecycleCoordinatorDbContract, TransactionTargetedUpdate). Any coordinator signature change breaks all six at once — the "refactors break ~100 tests" pain. A shared fixture (like `GoldenTestBase` in batch 01) is the fix. Additionally `DatabaseWriteBarrier` is relaxed-mocked in all 6, so restore-barrier gating on these legal paths is never exercised (covered only by barrier-focused files in other batches).
- **Money-domain misplacement confirmed:** MoneyAggregateBuilderTest, MoneyAggregateConversionScenarioTest, MulticurrencyPartialRateScenarioTest are pure `domain/core/money/` tests (no pipeline, DB decorative or absent). `domain/core/money/` already hosts MoneyAggregateBuilderRestrictionTest etc., so MOVE targets exist. MapMarkerConversionCurrencyTest re-verified: **no marker production code exists** (`grep ExpenseMapMarker|MapMarker app/src/main` = 0 hits) — the tested logic is defined inside the test file, so prior MOVE verdict is overturned to DELETE.
- Legal paths: #29/#31 drive the real `TransactionLifecycleCoordinator`, #10/#9 the real `GroupLifecycleCoordinator`, #13 the real `InvestmentTracker` — these are the batch's most valuable files. Receipt (#24) and recurring (#26) lifecycle scenarios explicitly bypass their coordinators (documented in KDocs), mirroring the golden-batch finding that only DB contracts, not legal paths, are proven there.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 10 | 9 | 8 | 3 | 1 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/scenarios/BackupRestoreContractTest.kt | 557 | 23 | 0 | ROBOLECTRIC | RestoreMaintenanceMode, RestoreJournal, CostbackupBundle, BackupVerifier | KEEP | P0 | data/backup/P7BugFixesTest, CostbackupBundleLimitsTest, BackupVerifierManifestTest, golden #20/#24 | Real mode machine + typed exceptions; unique vs mocked goldens |
| 2 | test/…/scenarios/BackupRestoreMoneyIntegrityScenarioTest.kt | 175 | 3 | 0 | ROOM | AppDatabase.ALL_MIGRATIONS, DAOs, ScenarioSeeder | STRENGTHEN | P2 | data/database/MigrationRegistrationTest (F-15) | No backup/restore despite name; test 2 assertNotNull-only |
| 3 | test/…/scenarios/BankSyncScenarioTest.kt | 190 | 3 | 0 | ROOM | BankConnectionDao, ExpenseDao.isDuplicate | STRENGTHEN | P2 | golden/BankSyncFailureRecoveryGoldenTest | DAO-only; no sync pipeline; isDuplicate window real |
| 4 | test/…/scenarios/CsvExportImportRoundtripTest.kt | 210 | 3 | 0 | ROOM | ExpenseDao date-range queries | STRENGTHEN | P2 | golden/CsvExportImportRoundtripGoldenTest | No CSV anywhere (misnomer); range boundaries real |
| 5 | test/…/scenarios/CurrencyRateStalenessScenarioTest.kt | 145 | 4 | 0 | MOCKED | CurrencyConverter 24h staleness | STRENGTHEN | P1 | golden/StaleRateCurrencyConversionGoldenTest, domain/currency tests | Tests 1/3 near-tautology; tests 2/4 real staleness+fallback |
| 6 | test/…/scenarios/DatabaseIntegrityTest.kt | 187 | 3 | 0 | ROOM | DatabaseIntegrityScanner | KEEP | P1 | — (only scanner test) | Seeds real violation; dedupeKey unique-index IGNORE proven |
| 7 | test/…/scenarios/EmailReceiptPipelineScenarioTest.kt | 212 | 3 | 0 | ROOM | EmailReceiptSourceDao dedupe | STRENGTHEN | P2 | data/email/EmailReceiptParserTest | insertOrIgnore dedupe real; no pipeline exercised |
| 8 | test/…/scenarios/ExpenseDaoAggregateFilterTest.kt | 188 | 5 | 0 | ROOM | ExpenseDao aggregate SQL | KEEP | P1 | domain/tax BusinessExpenseRepository tests | Real filter semantics: business-only, not-mine, null-key |
| 9 | test/…/scenarios/GroupLifecycleContractTest.kt | 272 | 6 | 0 | ROOM+MOCKED | GroupLifecycleCoordinator | MERGE | P1 | #10 (DUP 4/6 tests) | Survivor #10; carry removeMember-open-balance test |
| 10 | test/…/scenarios/GroupLifecycleScenarioTest.kt | 749 | 33 | 0 | ROOM+MOCKED | GroupLifecycleCoordinator (all 7 methods) | KEEP | P0 | #9 (DUP), GroupTransactionCoordinatorTest | Real legal path; NaN/Inf settlement guards; balance mocked |
| 11 | test/…/scenarios/GroupSettlementLifecycleScenarioTest.kt | 218 | 3 | 0 | ROOM | group/settlement DAOs | MERGE | P2 | #10, #27, golden GroupSettlementBudgetOffset | Pure insert-readback; coordinator-level dup |
| 12 | test/…/scenarios/HeatmapNormalizesCurrencyTest.kt | 234 | 3 | 0 | ROOM | AnalyticsCurrencyNormalizer + CurrencyConverter | KEEP | P1 | contracts/MoneyContractTest, consistency tests | Fake store good; relaxed timeProvider "fresh rates" by luck |
| 13 | test/…/scenarios/InvestmentGoldenScenarioTest.kt | 233 | 3 | 0 | ROOM+MOCKED | InvestmentTracker | KEEP | P1 | domain/investment/InvestmentTrackerTest, #14 | addHolding atomic; exact bucket math; not golden-locked |
| 14 | test/…/scenarios/InvestmentPortfolioScenarioTest.kt | 218 | 3 | 0 | ROOM | Investment/InvestmentValue DAOs | MERGE | P2 | #13 | Totals assertNotNull-only; range-exclusivity worth keeping |
| 15 | test/…/scenarios/LocationMapScenarioTest.kt | 177 | 3 | 0 | ROOM | MerchantLocationDao | KEEP | P2 | — (only MerchantLocationDao test) | upsert hitCount increment + area scoping contracts |
| 16 | test/…/scenarios/MapMarkerConversionCurrencyTest.kt | 185 | 3 | 0 | PURE | (test-local helper only) | DELETE | P4 | — | Tests its own if/else; zero production marker code exists |
| 17 | test/…/scenarios/MixedCurrencyCoreFinancialScenarioTest.kt | 245 | 3 | 0 | ROOM | MoneyAggregate factories | MERGE | P2 | #18, #5 (test 2 DUP), domain/core/money | Seeding decorative, never read into aggregate |
| 18 | test/…/scenarios/MoneyAggregateBuilderTest.kt | 291 | 8 | 0 | MOCKED | MoneyAggregateBuilder | KEEP | P0 | MoneyAggregateBuilderRestrictionTest (sibling pkg) | Solid behavior tests; MOVE to domain/core/money/ |
| 19 | test/…/scenarios/MoneyAggregateConversionScenarioTest.kt | 535 | 6 | 0 | ROOM | MoneyAggregate, MoneyAmount | REWRITE | P2 | #20 (throw test DUP), #18 | Self-built aggregates asserted; only test 5 is real |
| 20 | test/…/scenarios/MulticurrencyPartialRateScenarioTest.kt | 299 | 6 | 0 | PURE/ROOM | MoneyAmount helpers, ScenarioSeeder | MERGE | P2 | #19 (DUP throw test), domain/core/money | No conversion attempted; tests 3/4 assert the seeder |
| 21 | test/…/scenarios/NotificationPipelineScenarioTest.kt | 304 | 5 | 0 | MOCKED+ROOM | GreekBankParser + ScenarioSeeder | STRENGTHEN | P1 | CrossParserConsistencyTest, GreekBankParserStressTest, golden #13 | Real parser on real Greek text; "pipeline" never runs |
| 22 | test/…/scenarios/PrivacyCloudLocationDeniedScenarioTest.kt | 114 | 3 | 0 | MOCKED | CloudAi/Location/CompositePrivacyGate | MERGE | P1 | #23 (DUP test 3 verbatim), golden PrivacyGateEnforcement | Survivor #23; carry unique GPS-denied test |
| 23 | test/…/scenarios/PrivacyGateContractTest.kt | 294 | 10 | 0 | MOCKED/PURE | privacy gates, DefaultRedactionSanitizer, PrivacySettings | KEEP | P0 | #22, contracts/PrivacyStorageContractTest | Fail-closed defaults + sanitizer determinism locked |
| 24 | test/…/scenarios/ReceiptLifecycleDbContractTest.kt | 306 | 4 | 0 | ROOM | receipt tables DAO contract | STRENGTHEN | P1 | golden ReceiptMatchingNoDoubleCount, #25 | Coordinator bypass is documented; no unique-index test |
| 25 | test/…/scenarios/ReceiptPreOcrDedupeScenarioTest.kt | 132 | 2 | 0 | ROOM | ScannedReceiptDao.getByImageHash | MERGE | P2 | #24 | Thin lookup checks; fold into #24 |
| 26 | test/…/scenarios/RecurringNoDoubleCountScenarioTest.kt | 345 | 5 | 0 | ROOM+seeder | RecurringOccurrence/Reminder DAOs | REWRITE | P1 | golden #18/#19 (batch 01) | Headline test tautological (separate tables can't collide) |
| 27 | test/…/scenarios/SharedExpenseGroupScenarioTest.kt | 371 | 4 | 0 | ROOM | group DAOs, Expense.effectiveAmount | MERGE | P2 | #10, #11, e2e/SharedExpenseFlowTest | Share math computed in test (tautology); keep effAmount check |
| 28 | test/…/scenarios/TaxGoldenScenarioTest.kt | 194 | 2 | 0 | MOCKED+ROOM | TaxEstimator, BusinessExpenseRepository | STRENGTHEN | P1 | domain/tax/TaxEstimatorTest (F-05) | Test 1 weak non-empty asserts; test 2 real DB mileage math |
| 29 | test/…/scenarios/TransactionLifecycleCoordinatorDbContractTest.kt | 287 | 4 | 0 | ROOM | TransactionLifecycleCoordinator | KEEP | P0 | domain/transaction lifecycle tests (F-02), golden #23 | Real legal path: create/dedupe/update/delete + events |
| 30 | test/…/scenarios/TransactionLifecycleDbContractTest.kt | 268 | 4 | 0 | ROOM+seeder | ScenarioSeeder, ScenarioAssertions | REWRITE | P2 | fixture users package-wide | Name lies: tests the seeder, not the lifecycle |
| 31 | test/…/scenarios/TransactionTargetedUpdateSideEffectsTest.kt | 301 | 4 | 0 | ROOM+coVerify | TransactionLifecycleCoordinator targeted updates | STRENGTHEN | P0 | #29 | Key regeneration + recurring unlink/link verified; test 4 dups 1 |

## Findings (noteworthy files only)

### test/…/scenarios/MapMarkerConversionCurrencyTest.kt — DELETE (P4), prior MOVE overturned
- Defines `ExpenseMapMarker` and `createMarker()` inside the test file (MapMarkerConversionCurrencyTest.kt:32-80) and asserts on that helper's own if/else branches; the only production type touched is the `ConversionResult` data class it constructs itself (:125).
- Re-verified 2026-09: `grep -r "ExpenseMapMarker|MapMarker" app/src/main` returns zero hits — there is no production marker-conversion code for a MOVE target. This is a design-document-as-test, zero regression protection.
- Action: DELETE. If marker currency display is ever implemented, write the test against the real code.

### test/…/scenarios/RecurringNoDoubleCountScenarioTest.kt — REWRITE (P1)
- The headline test 2 (:123-179) inserts a PLANNED `RecurringOccurrence` and one expense, then asserts the expense total is 12.99 "not 25.98". Occurrences live in a different table and are never summed by `expenseDao` totals, so the assertion cannot fail by construction — the tautology pattern MASTER_TESTING_STRATEGY.md forbids.
- The real double-count vector (repositories/aggregates counting planned+actual, or the claim/fulfill path) is never invoked; golden RecurringPlannedActualNoDoubleCountGoldenTest (batch 01 #19, KEEP P0) covers the meaningful invariant. Tests 1/3/4/5 are plain DAO roundtrips.
- Action: rewrite test 2 to drive `TransactionLifecycleCoordinator` → `RecurringLifecycleCoordinator.linkExpenseToOccurrence` + claim/fulfill and assert converted totals; or merge the DAO roundtrips into the golden files and delete.

### test/…/scenarios/MoneyAggregateConversionScenarioTest.kt + MulticurrencyPartialRateScenarioTest.kt + MixedCurrencyCoreFinancialScenarioTest.kt — decorative-seeding trio
- All three seed categories/expenses/warranties/subscriptions/investments via `ScenarioSeeder`, then hand-construct `MoneyAggregate.partial(...)`/`singleCurrency(...)` with the expected numbers and assert on their own inputs (e.g. MoneyAggregateConversionScenarioTest.kt:207-263 — KDoc admits "construction pattern is tested directly"; MixedCurrencyCoreFinancialScenarioTest.kt:92-113 identical pattern). The DB is never read into the aggregate; prior-audit KEEP verdicts (P0/P1) were too generous.
- Real unique value buried inside: the cross-currency `MoneyAmount.plus` throw test (MoneyAggregateConversionScenarioTest.kt:460-474, duplicated at MulticurrencyPartialRateScenarioTest.kt:64-77) is the ONLY coverage of the MoneyAmount.kt:54 invariant, and the `money()/.eur/.usd/.gbp` helper tests are the only coverage of those fixtures.
- Action: move `MoneyAmount` operator/helper tests into a new `domain/core/money/MoneyAmountTest`; move `MoneyAggregate` factory-semantics assertions next to MoneyAggregateBuilderRestrictionTest; drop the decorative seeding or rewrite to run `MultiCurrencyRepository` over the seeded DB.

### test/…/scenarios/GroupLifecycleScenarioTest.kt (KEEP P0) + GroupLifecycleContractTest.kt (MERGE P1)
- #10 drives the real `GroupLifecycleCoordinator` legal path over a real DB: 33 tests covering all 7 lifecycle methods, validation gates (member count, currentUser, duplicate names, blank name), settlement money guards (zero/negative/NaN/infinite rejected, :479-548), lifecycle-event atomicity, and an end-to-end flow. Grew from 14 tests at the prior audit.
- #9 duplicates 4 of its 6 tests almost verbatim (settlement persistence, foreign-currency rejection, member removal, hard-delete gate) with the same 50-LOC hand-wired coordinator stack — FRAGILE duplication. Its unique test is `removeMember blocked when net balance not settled` (:235-245).
- Both mock `balanceCalculator` (#10 stubs a settled balance at :118-124; #9 uses a bare relaxed mock), so the balance-gate logic itself is never really executed, and `DatabaseWriteBarrier` is a relaxed mock in both (barrier untested here).
- Action: merge #9's balance test into #10, extract the shared coordinator wiring into a scenario fixture, and add one test with a real `GroupBalanceCalculator` over seeded expenses/settlements.

### test/…/scenarios/TransactionLifecycleCoordinatorDbContractTest.kt (KEEP P0) + TransactionTargetedUpdateSideEffectsTest.kt (STRENGTHEN P0)
- The only files in the package that exercise a coordinator legal path end-to-end against a real DB: create → `Created` + CREATED event; duplicate → `DuplicateSkipped(existingId)` + CREATE_DUPLICATE_SKIPPED event (:148-194); update/delete with reason-carrying events. Targeted-update file additionally proves merchantKey/dedupeKey regeneration (:190-200) and recurring reconciliation hooks `unlinkExpenseFromOccurrence`/`linkExpenseToOccurrence` (:203-204, :244-245) — the Segment 7↔9 cross-pipeline hook.
- Gaps: #31 test 1 title claims "dispatches budget side effect" but only DB state is checked (its own comment concedes the verify was dropped, :136-138); test 4 repeats test 1 via the seeder. Both build the 14-arg coordinator with relaxed mocks (barrier, validator, side-effect planner all no-ops) — FRAGILE to any refactor, and F-02's coroutine-harness failures in the sibling `domain.*.lifecycle` tests may hit these too once the scenarios package is actually run.

### test/…/scenarios/PrivacyGateContractTest.kt (KEEP P0) + PrivacyCloudLocationDeniedScenarioTest.kt (MERGE P1)
- Contract file is the privacy anchor: fail-closed `PrivacySettings` defaults (cloud/OCR/location off, redaction on, encrypted backup on, :155-185), `DefaultRedactionSanitizer` determinism + blank→merchant_unknown (:120-149), composite short-circuit and all-allow paths, image-upload suppressed when redaction on, bank-statement gate.
- The claimed duplicate `PrivacyGateEnforcementScenarioTest` was indeed deleted; the live duplication is PrivacyCloudLocationDenied test 3 ≡ PrivacyGateContract test 3 (same mocks, same coVerify counts). PrivacyCloudLocationDenied tests 1-2 add the only `LocationPrivacyGate` GPS-denied coverage in the package — carry it into the contract file before deleting.
- Note: prior-audit duplicate pair "PrivacyGateEnforcement vs PrivacyCloudLocationDenied" is resolved; new DUP pair is PrivacyCloudLocationDenied↔PrivacyGateContract.

### Naming-integrity cluster: BackupRestoreMoneyIntegrity (#2), CsvExportImportRoundtrip (#4), BankSync (#3), EmailReceiptPipeline (#7), TaxGolden (#28), InvestmentGolden (#13), TransactionLifecycleDbContract (#30)
- None of these names describes what runs: no backup/restore (#2), no CSV (#4 — just `getExpensesBetween`/`countExpensesBetween`), no bank sync (#3 — DAO CRUD + `isDuplicate`), no email pipeline (#7 — `EmailReceiptSourceDao` dedupe), no golden lock (#13/#28), no transaction lifecycle (#30 — it tests `ScenarioSeeder` itself, including proving seedState does NOT dedupe, TransactionLifecycleDbContractTest.kt:97-147). The measurable behaviors inside are real (dedupe unique indexes, mileage fallback 30+15 vs stored 40 = 85, date-range boundaries) but each leaves the named end-to-end behavior untested — the same master-strategy scenario gaps batch 01 logged (its scenarios 1/2/4/5/6).
- Action: rename to match content now; the pipeline-level versions (real CsvExporter/importer, createCostBackup→restore, bank ingest worker, email ingest, receipt coordinator) remain open coverage gaps.

## Area gaps (what is NOT tested in this area)

- **No scenario runs a real pipeline coordinator** except TransactionLifecycleCoordinator (#29/#31): `NotificationProcessingPipeline`, `ReceiptLifecycleCoordinator`, `ReceiptLinkService`, `RecurringRuleLifecycleCoordinator`, and `GroupTransactionCoordinator`'s write path are all either mocked or bypassed with hand DAO inserts. Master-strategy golden scenarios 1, 2, 3, 4, 6 remain behaviorally unproven.
- **No true concurrency** anywhere (all `runTest` sequential) — the atomic occurrence-claim has no multi-coroutine test in this package.
- **`DatabaseWriteBarrier` relaxed in every coordinator-wiring file** — no scenario proves restore-mode blocks a group/transaction/investment write through its real entry point.
- **Real `GroupBalanceCalculator` never used** in group scenarios; removeMember settlement-gate logic untested with real balances.
- **MoneyAmount.plus-throw invariant only exists inside scenarios/** (twice, duplicated) — nothing in `domain/core/money/` covers it; MOVE needed.
- **GPS/location privacy denial** exists only in a MERGE-doomed file (#22).
- **CurrencyRateStalenessScenarioTest** tests the mocked-store converter path only; the 24h staleness against a real `ExchangeRateStoreAdapter`/DB (where batch 01 found the stale golden) is untested here.
- **Privacy DO_NOT_STORE / raw-text redaction** behavior (master scenario 8) has no scenario-level implementation (PrivacyDoNotStoreTest in golden is tautological; nothing here fills the hole).
- The whole package is **unmeasured** per TEST_FAILURE_LEDGER.md:20 — pass/fail status unknown; F-02/F-05 drift may surface here.

## Rollup

- Verdicts: KEEP 10 · STRENGTHEN 9 · MERGE 8 · REWRITE 3 · DELETE 1 · NIGHTLY 0 · UNKNOWN 0 (31 files, 174 tests, 0 @Ignore)
- P0 count: 6 (#1, #10, #18, #23, #29, #31)
- P4 (negative-value): MapMarkerConversionCurrencyTest.kt (tests only its own in-file helper; no production counterpart exists)
- DUP pairs: GroupLifecycleContract↔GroupLifecycleScenario · PrivacyCloudLocationDenied#3↔PrivacyGateContract#3 · GroupSettlementLifecycle↔GroupLifecycleScenario/SharedExpenseGroup · MixedCurrencyCore#2↔CurrencyRateStaleness#1 · MoneyAggregateConversion#5↔MulticurrencyPartialRate#1 · RecurringNoDoubleCount↔golden RecurringPlannedActualNoDoubleCount (name-level; golden is the real one) · internal: TransactionTargetedUpdate#4↔#1
- FRAGILE: 6 files hand-wire the 14-arg TransactionLifecycleCoordinator (Group×2, Investment, Tax, TLC-DbContract, TargetedUpdate) — systemic; + 3 decorative-seeding money files; + Heatmap's relaxed TimeProvider making rates "fresh" by accident
- Prior-audit changes: PrivacyGateEnforcementScenarioTest and SpeechInputGatewayLifecycleTest confirmed already deleted; 4 verdicts overturned (MapMarker MOVE→DELETE P4; BackupRestoreMoneyIntegrity KEEP P0→STRENGTHEN P2; MixedCurrencyCore KEEP→MERGE P2; MoneyAggregateConversion KEEP P0→REWRITE P2); GroupLifecycleScenarioTest grew 14→33 tests and absorbed GroupGoldenScenarioTest
- MOVE recommendations (per batch guidance): MoneyAggregateBuilderTest → `domain/core/money/`; MoneyAggregateConversionScenarioTest + MulticurrencyPartialRateScenarioTest (money portions) → `domain/core/money/`; MapMarkerConversionCurrencyTest → DELETE (no move target exists)
