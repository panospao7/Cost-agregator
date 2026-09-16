# Batch 14 — data/repository tail + data/security, data/service, data/speech, data/store

Scope: Segments 3 (notification capture/review), 4 (receipt lifecycle), 7 (recurring), 20 (AI follow-through recommendations), 23/35 (savings), 28 (security/key storage), 34 (warranty), 11 (notification delivery), data/store write-barrier facades · Files: 14 · LOC: 3910
Reviewer notes: All three class-level `@Ignore` "stress" files in this batch are dead in CI and two of them are the ONLY repository-level tests for NotificationRepository / ReceiptRepository — unique behavior is stranded. F-09/F-14 (ExpenseStore typed exception) statically re-verified against production: RESOLVED. RecurringExpenseRepositoryTest already contains the F-13 fix pattern (explicit `dagger.Lazy` wrapper).

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 10 | 0 | 1 | 3 | 0 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/data/repository/NotificationRepositoryStressTest.kt | 306 | 21 | 21 (class) | MOCKED | NotificationRepository | REWRITE | P3 | NotificationRepositoryDeleteAllNotificationsClockTest; (no live NotificationRepositoryTest exists) | @Ignore class; flow tests tautological; delete/stats-decrement unique but dead |
| 2 | test/…/data/repository/PlannedExpenseRepositoryDiagnosticsTest.kt | 134 | 5 | 0 | MOCKED | PlannedExpenseRepository | KEEP | P1 | DashboardContractsAdapterTest, FinancialWeatherRepositoryTest | Best-effort diagnostics contract; real event assertions |
| 3 | test/…/data/repository/ReceiptRepositoryStatementDuplicateTest.kt | 216 | 1 | 0 | MOCKED | ReceiptRepository | KEEP | P1 | ReceiptRepositoryStressTest, DedupeKeyProducerConsistencyTest | Currency-aware dup lookup; FRAGILE (22 ctor deps); ledger F-21 |
| 4 | test/…/data/repository/ReceiptRepositoryStressTest.kt | 408 | 12 | 12 (class) | MOCKED | ReceiptRepository | REWRITE | P3 | ReceiptLifecycleCoordinatorTest; (no live repo-level test) | @Ignore class; OCR-fallback/text-preserve behavior stranded; FRAGILE |
| 5 | test/…/data/repository/RecommendationRepositoryTest.kt | 426 | 17 | 0 | MOCKED | RecommendationRepository | KEEP | P2 | RecommendationLifecycleManagerTest, RecommendationStateManagerTest | saveAll cap/priority/merge tests are real; setMain never reset |
| 6 | test/…/data/repository/RecurringExpenseRepositoryTest.kt | 87 | 3 | 0 | MOCKED | RecurringExpenseRepository | KEEP | P1 | CashFlowCalculatorTest (indirect) | Exercises legal path (coordinator.createRule); F-13 fix pattern in place |
| 7 | test/…/data/repository/ReviewQueueRepositoryStressTest.kt | 171 | 8 | 8 (class) | MOCKED | ReviewQueueRepository | MERGE | P4 | ReviewQueueRepositoryTest | @Ignore dead; port 2 bulk-op verifies, then delete |
| 8 | test/…/data/repository/ReviewQueueRepositoryTest.kt | 604 | 11 | 0 | MOCKED | ReviewQueueRepository | KEEP | P0 | ReviewQueueRepositoryStressTest, DedupeKeyProducerConsistencyTest | Approve/dedup/status contracts; ledger F-08+F-13 (8 failed); FRAGILE |
| 9 | test/…/data/repository/SavingsContributionHistoryRepositoryTest.kt | 112 | 3 | 0 | MOCKED | SavingsContributionHistoryRepository | KEEP | P1 | SavingsGamificationEngineTest (indirect) | Real temp-file DataStore persistence; gold standard |
| 10 | test/…/data/repository/WarrantyTrackerRepositoryTest.kt | 804 | 25 | 0 | MOCKED | WarrantyTrackerRepository | KEEP | P1 | AutoCreateWarrantyFromReceiptUseCaseTest, WarrantyExpirationWorkerTest | Confidence bands w/ exact boundaries; calendar-math + privacy assertions; some verify-only TODOs |
| 11 | test/…/data/security/SecureKeyStorageTest.kt | 341 | 17 | 17 (class) | MOCKED | SecureKeyStorage | REWRITE | P0 | (AI provider tests mock SecureKeyStorage, not test it) | P0 area with ZERO active coverage; @Ignore + tautological prefs mocks |
| 12 | test/…/data/service/AndroidNotificationServiceTest.kt | 74 | 2 | 0 | ROBOLECTRIC | AndroidNotificationService | KEEP | P1 | — | Permission gate → NOT_DELIVERED, notify not called; metric gate contract |
| 13 | test/…/data/speech/AndroidSpeechInputGatewayTest.kt | 89 | 3 | 0 | ROBOLECTRIC | AndroidSpeechInputGateway | KEEP | P2 | — | Injected checkers/factory; real error-surface contracts |
| 14 | test/…/data/store/ExpenseStoreTest.kt | 138 | 11 | 0 | MOCKED | ExpenseWriteStore, ExpenseReadStore, DatabaseWriteBarrier | KEEP | P0 | ExpenseWriteStoreObservabilityTest, DatabaseBarrierTest | F-09/F-14 RESOLVED statically (see findings) |

## Findings (noteworthy files only)

### test/…/data/store/ExpenseStoreTest.kt (F-09 / F-14 — verdict: test now MATCHES production)
- Ledger recorded "expected DatabaseAccessBlockedException but was IllegalStateException" (5 failures, ledger F-09 + F-14 cluster).
- Test asserts the typed exception in all 5 block tests (ExpenseStoreTest.kt:45,54,63,72,80) plus metadata catch at :133.
- Production verified: `DatabaseWriteBarrier.checkWritesAllowed(DatabaseAccessOperation)` throws `DatabaseAccessBlockedException` (app/src/main/…/data/backup/DatabaseWriteBarrier.kt:15-23); the exception subclasses IllegalStateException (DatabaseAccessModels.kt:22-28), matching batch-10's finding at DatabaseAccessModels.kt:22.
- Metadata assertions (`"ExpenseWriteStore.updateMerchantKey"`, `"P2"`, `"Expense"`) exactly match ExpenseWriteStore.kt:21-24.
- Test uses a REAL barrier over a mocked RestoreMaintenanceMode — exercises the real fail-closed semantics, not a mock tautology.
- Recommended action: close F-09 (and the ExpenseStore portion of F-14) as resolved at HEAD; expect the 5 tests green on next measured run. KEEP P0.

### test/…/data/security/SecureKeyStorageTest.kt — P0 security area with zero active coverage
- Entire class `@Ignore("AndroidKeyStore not available on desktop JVM")` (line 37) — 17 tests, 341 LOC, never run.
- Even un-ignored, value is low: `EncryptedSharedPreferences.create` is mockkStatic'd (lines 60-69), so encryption-at-rest is never verified; roundtrip tests assert MockK stubs, not storage (tautology, admitted by 12 in-file TODO comments).
- No secret leakage risk in the test itself: values are obviously fake ("sk-1234567890abcdef"), nothing is logged or written to disk (prefs fully mocked); `unmockkAll()` in tearDown.
- The genuinely valuable contracts are stranded: migration skips null keys and never overwrites existing keys (:176-211), `validateSecureStorage` fails closed on exception (:288-298).
- Recommended action: REWRITE P0 — resurrect as a live JVM test with an in-memory prefs fake for delegation/migration/fail-closed logic, plus a small androidTest (device) for real Keystore encryption. BankTokenCipher itself is covered elsewhere (BankApiIntegrationTest, BankPrivacyHardeningTest), so the gap is SecureKeyStorage only.

### test/…/data/repository/ReviewQueueRepositoryTest.kt (F-08 + F-13)
- Ledger: 8 failures; F-13 signature `ClassCastException: Object cannot be cast to CreateExpenseResult` from relaxed-mock/Lazy stub gap.
- Static read: production casts `mutation.value as CreateExpenseResult.Created` at ReviewQueueRepository.kt:627; a relaxed mock return on any un-stubbed generic path yields a bare Object → exact signature. Tests stub `createExpenseDbOnlyV2` but the 8 failures indicate an un-stubbed production branch (NEEDS INVESTIGATION per ledger — likely a prod refactor, not stale assertions; comments in the file already reflect the coordinator-owned dedup contract).
- Content is high value: approve → coordinator legal path, PENDING→PROCESSING→APPROVED/DUPLICATE transitions, negative correction recording, type-aware dedupeKey policy, race → Duplicate, amount-limit rejection before any transition.
- FRAGILE: 22-dependency constructor with relaxed mocks is what hides stub gaps; a stricter (non-relaxed) coordinator mock would have surfaced F-13 at compile/stub time.
- KEEP P0; fix the stub gap at runtime triage, do not weaken assertions.

### test/…/data/repository/RecurringExpenseRepositoryTest.kt — F-13 fix pattern already applied
- Setup wraps the coordinator in `dagger.Lazy { ruleLifecycleCoordinator }` with an explanatory comment (:35-41) — this is the correct fix for the F-13 ClassCastException root cause and should be the template for other affected files.
- Asserts the repository routes creation through the legal path (`RecurringRuleLifecycleCoordinator.createRule`) with `nextDate` matching `RecurrenceCalculator` for IRREGULAR/SEMI_ANNUALLY/ANNUALLY. KEEP P1.

### Class-level @Ignore stress files (#1, #4, #7)
- All three are dead in CI and will rot as constructors drift (NotificationRepository already at 13 ctor deps with inline `mockk(relaxed=true)` positionals).
- #1 and #4 are NOT duplicates of anything: no live NotificationRepositoryTest / ReceiptRepositoryTest exists; unique behaviors (source-stats decrement on delete with pending review; OCR-failure fallback writing "Scan Failed: …"; parse-failure preserving raw text; deleteReceipt removing the image file) are stranded behind @Ignore. Overturn prior MOVE_TO_NIGHTLY verdicts → REWRITE: salvage the behavioral subsets into live classes, drop the `assertNotNull(flow)` tautologies.
- #7 IS substantially duplicated by #8 (empty-queue no-crash, flow assertions) except two verify-only bulk-op tests → MERGE into ReviewQueueRepositoryTest, then delete. P4 as-is.

### test/…/data/repository/WarrantyTrackerRepositoryTest.kt
- Strong: three-band confidence thresholds with exact boundaries (0.75 auto / 0.30 review / 0.1 discard), calendar-month end-date math incl. leap boundary (2024-01-31 + 1mo = 2024-02-29, :137-169), lifecycle events best-effort (primary mutation survives event-writer failure, :761-803), and a privacy assertion that manual placeholders do NOT store the product name in rawOcrText (:481-496).
- Minor: calendar assertion converts via `ZoneId.systemDefault()` (:165) — can drift if production math uses UTC; one verify-only test (`markWarrantyAsClaimed`). KEEP P1.

### test/…/data/repository/RecommendationRepositoryTest.kt
- saveAll tests verify real policy: max-5 cap, HIGH>MEDIUM>LOW prioritization, deterministic merge/overflow archive against existing set, dedup collapse with overflow pruning (:126-265) using a real `RecommendationDeduplicator` + `TransactionFilterSerializer`.
- Isolation hazard: `Dispatchers.setMain(testDispatcher)` in @Before with no `Dispatchers.resetMain()` teardown (:44) — leaks the Main dispatcher into other test classes in the same fork. KEEP P2, add resetMain.

## Area gaps (what is NOT tested in this area)

- NotificationRepository has NO active repository-level test: save/dedup-exists, delete + source-stats decrement, package block/unblock only exist inside the @Ignore'd stress file.
- ReceiptRepository has NO active repo-level test: OCR-failure fallback record, parse-failure raw-text preservation, batch progress/failure counting, deleteReceipt asset deletion — all stranded in @Ignore; live coverage only via ReceiptLifecycleCoordinatorTest (coordinator side).
- SecureKeyStorage: zero active coverage; nothing anywhere verifies keys are actually encrypted at rest (EncryptedSharedPreferences mocked everywhere it appears in tests).
- ReviewQueue `approveAllReview` / `rejectAllReviews` batch paths have no live test.
- ExpenseWriteStore methods `conditionallySetLocation`, `updateMerchant`, `insertAll`, `deleteAll` (debug path) have no block/delegate test in ExpenseStoreTest (only insert/update/delete/updateMerchantKey/incrementBackfillAttempts).

## Rollup

- Verdicts: KEEP 10 · MERGE 1 (ReviewQueueRepositoryStressTest → ReviewQueueRepositoryTest) · REWRITE 3 (NotificationRepositoryStressTest, ReceiptRepositoryStressTest, SecureKeyStorageTest) · DELETE 0 · NIGHTLY 0 · UNKNOWN 0.
- P0 count: 3 (ExpenseStoreTest, ReviewQueueRepositoryTest, SecureKeyStorageTest).
- DUP pairs: ReviewQueueRepositoryStressTest ⊂ ReviewQueueRepositoryTest (substantial overlap); NotificationRepositoryStressTest / ReceiptRepositoryStressTest are NOT duplicated — they are unique-but-dead.
- FRAGILE count: 5 (ReceiptRepositoryStatementDuplicateTest, ReceiptRepositoryStressTest, ReviewQueueRepositoryTest, ReviewQueueRepositoryStressTest, NotificationRepositoryStressTest; WarrantyTrackerRepositoryTest borderline).
- Ledger families touched: F-09 + F-14 ExpenseStore portion → statically RESOLVED (typed exception now thrown and asserted; metadata exact-matches). F-08 + F-13 ReviewQueue portion → open, root cause consistent with un-stubbed generic path at ReviewQueueRepository.kt:627; RecurringExpenseRepositoryTest already carries the F-13 fix pattern. F-21 includes ReceiptRepoStatementDup (file #3) → KEEP, runtime triage needed.
- Prior-audit overturns: SecureKeyStorageTest KEEP→REWRITE (now fully @Ignore'd); Notification/Receipt/ReviewQueue stress MOVE_TO_NIGHTLY→REWRITE/REWRITE/MERGE (no live siblings exist; "nightly" would still run nothing useful).
