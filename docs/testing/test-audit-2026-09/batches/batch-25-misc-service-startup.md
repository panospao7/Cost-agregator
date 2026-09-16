# Batch 25 — misc/service + misc/startup

Scope: top-level `service/` (notification capture/filter, recommendation follow-through cluster, filter serialization/navigation), `service/receiptmatching/`, `service/reminder/`, `service/warranty/` workers, and `startup/` recovery (merged batch-26 file). Segments: 3 (capture), 11 (notifications), 12 (startup/background runtime), 34 (warranty), 36 (bill reminders), 38 (receipt matching). · Files: 17 · LOC: 5,297 · Tests: 250 · @Ignore: 0

Reviewer notes:
- Systemic finding (answers the batch-09 echo): **no worker test in this batch uses the real `WorkerExecutionGuard`** — all 5 worker test files mock it, in three fidelity tiers: (a) `WarrantyExpirationWorkerTest` ships a documented *faithful mirror* (CancellationException rethrow → `RetryableWorkerException` → transient-keyword heuristic, setup lines 76-92); (b) `ReceiptMatchingWorkerTest` mirrors a *subset* (transient keywords only, no `RetryableWorkerException` branch, setup lines 67-80); (c) `BillReminderWorker*` and the two action-worker tests use blanket run-the-block mocks (prove delegation to the guard, nothing about guard semantics). Real guard semantics live in `domain/workers/WorkerExecutionGuardTest` (batch 21/24 area), so the split is complementary — but mirrors (a)/(b) can silently drift from the real classifier; recommend one shared faithful stub or one real-guard integration test per worker family.
- Split vs batch 21 (ledger F-06): F-06 is `domain/reminder/BillReminderManagerTest` (legacy `markBillPaid` contract). My `BillReminderWorkerTest` covers the *worker dispatch path* (permission precheck, claim/unclaim on SecurityException) — different class, different behaviors; **not a duplicate**, no batch-21 overlap found.
- Prior (2026-05) audit re-verification: `NotificationCaptureServiceStressTest.kt` (prior DELETE, @Ignore'd "run manually") **no longer exists on disk — already removed, verified**. `NotificationCaptureServiceFallbackTest` prior verdict KEEP P1 is **overturned** (see findings — file no longer touches any production class). NotificationFilterTest / StateManagerTest / ReceiptMatchingWorkerTest / WarrantyExpirationWorkerTest prior KEEP verdicts re-confirmed against current (grown) sources.
- None of the 17 files appear in `TEST_FAILURE_LEDGER.md` families F-01…F-21.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 13 | 3 | 0 | 0 | 1 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/service/NavigationTargetResolverTest.kt | 424 | 32 | 0 | MOCKED | NavigationTargetResolverImpl | KEEP | P2 | HomeViewModelRecommendationTest, HomeViewModelStressTest | Real mapping/period logic asserted |
| 2 | test/…/service/NotificationCaptureServiceCleanupTest.kt | 185 | 7 | 0 | MOCKED | NotificationCaptureService, NotificationServiceWorkTracker | STRENGTHEN | P1 | NotificationPrivacyHardeningTest, PrivacyStorageContractTest | 2 reflection signature tests FRAGILE |
| 3 | test/…/service/NotificationCaptureServiceFallbackTest.kt | 69 | 5 | 0 | PURE | (none — tautology) | DELETE | P4 | — | Re-implements fallback in test; prior KEEP overturned |
| 4 | test/…/service/NotificationFilterTest.kt | 345 | 25 | 0 | PURE | NotificationFilter | KEEP | P1 | — | Pins NEW-P1-2026-001 "pos" regression |
| 5 | test/…/service/RecommendationCacheServiceTest.kt | 383 | 18 | 0 | MOCKED | RecommendationCacheService | STRENGTHEN | P2 | RecommendationLifecycleManagerTest | No resetMain; TTL test is a no-op |
| 6 | test/…/service/RecommendationDeduplicatorTest.kt | 203 | 11 | 0 | PURE | RecommendationDeduplicator | KEEP | P2 | RecommendationRepositoryTest | Real dedup semantics, real serializer |
| 7 | test/…/service/RecommendationDismissalHandlerTest.kt | 387 | 23 | 0 | MOCKED | RecommendationDismissalHandler | STRENGTHEN | P2 | HomeViewModelRecommendationTest | Verify-heavy; low-value permutations |
| 8 | test/…/service/RecommendationLifecycleManagerTest.kt | 473 | 32 | 0 | MOCKED | RecommendationLifecycleManager | STRENGTHEN | P2 | RecommendationCacheServiceTest | FRAGILE unguarded private-field reflection |
| 9 | test/…/service/RecommendationStateManagerTest.kt | 830 | 30 | 0 | MOCKED | RecommendationStateManager | KEEP | P1 | RecommendationDismissalHandlerTest | Deterministic generation-guard overlap tests |
| 10 | test/…/service/TransactionFilterSerializerTest.kt | 236 | 16 | 0 | PURE | TransactionFilterSerializer | KEEP | P1 | DashboardFollowThroughEngineTest, RecommendationRepositoryTest | Round-trip + malformed-input robustness |
| 11 | test/…/service/receiptmatching/ReceiptMatchingWorkerTest.kt | 686 | 24 | 0 | ROBOLECTRIC | ReceiptMatchingWorker | KEEP | P0 | WorkerGuardStaticVerificationTest | Partial guard mirror; superb diagnostics/privacy pins |
| 12 | test/…/service/reminder/BillReminderWorkerTest.kt | 255 | 4 | 0 | ROBOLECTRIC | BillReminderWorker | KEEP | P1 | WorkerIdempotencyTest, BillReminderWorkerTimeProviderTest | Guard blanket-mocked (systemic tier c) |
| 13 | test/…/service/reminder/BillReminderWorkerTimeProviderTest.kt | 133 | 3 | 0 | ROBOLECTRIC | BillReminderWorker | KEEP | P2 | BillReminderWorkerTest | Quiet hours via FakeTimeProvider |
| 14 | test/…/service/reminder/DismissReminderActionWorkerTest.kt | 71 | 2 | 0 | MOCKED | DismissReminderActionWorker | KEEP | P2 | WorkerGuardStaticVerificationTest | No guard Skipped/Retry mapping tests |
| 15 | test/…/service/reminder/SnoozeReminderActionWorkerTest.kt | 71 | 2 | 0 | MOCKED | SnoozeReminderActionWorker | KEEP | P2 | WorkerGuardStaticVerificationTest | Mirror of #14 for snooze |
| 16 | test/…/service/warranty/WarrantyExpirationWorkerTest.kt | 342 | 12 | 0 | ROBOLECTRIC | WarrantyExpirationWorker | KEEP | P0 | WorkerGuardStaticVerificationTest, DbGuardPolicyFixtureTest | Real Room claim/dedup; faithful guard mirror |
| 17 | test/…/startup/AppStartupCoordinatorRecoveryTest.kt | 204 | 4 | 0 | ROBOLECTRIC | AppStartupCoordinator, RestoreJournal, RestoreMaintenanceMode | KEEP | P0 | WriteBarrierArchitectureGuardTest, WorkerContractTest | Real journal files + persisted mode |

## Findings (noteworthy files only)

### test/…/service/NotificationCaptureServiceFallbackTest.kt — DELETE (P4)
- All 5 tests re-implement the `takeIf { isNotBlank } ?: …` fallback chain *inside the test* (e.g. lines 14-16) and assert the test's own expression; zero imports of `NotificationCaptureService` or any production class.
- Pure tautology per MASTER_TESTING_STRATEGY "tests stdlib" rule; gives false green confidence.
- Prior 2026-05 audit (generated/test-batches/batch-004.md:120) said KEEP P1 attributing it to `NotificationTextParts`/`NotificationServiceWorkTracker` — that no longer matches current source; **overturned**.
- Related verification: `NotificationCaptureServiceStressTest.kt` (prior DELETE) is confirmed gone from `app/src/test/.../service/`.

### test/…/service/NotificationCaptureServiceCleanupTest.kt — STRENGTHEN (P1)
- Good: 4 tests assert the real production constant `NotificationCaptureService.SENSITIVE_EXTRAS_KEYS` (prod `NotificationCaptureService.kt:192`) incl. camelCase/snake_case/case-insensitive coverage — genuine privacy invariant, no sibling test covers this key set.
- Bad: `service_destruction_after_filter_pass_does_not_lose_notification` (line 99) and `processNotification_accepts_privacy_settings_parameter` (line 148) are pure reflection signature checks (parameter count/type names); the first's name claims a NonCancellable behavior it never exercises. Misleading + breaks on any private refactor (FRAGILE).
- Action: delete the two reflection tests; cover the NonCancellable durability behavior via the existing `CancellationPropagationContractTest`/`CancellationSafetyArchitectureGuardTest` seam instead.

### test/…/service/receiptmatching/ReceiptMatchingWorkerTest.kt — KEEP (P0)
- Best-in-batch: pins durable match diagnostics (MATCH_ATTEMPTED / MATCH_NOT_FOUND / MATCH_SKIPPED_DOCUMENT_TYPE / AUTO_MATCH_LINK_FAILED), structured reason codes **not raw exception messages** (privacy, lines 263-313), `requireUnmatchedClaim = true` atomic-claim contract (lines 429-464), CancellationException propagation → failure with no diagnostic (lines 405-426), notification metrics only after real send, and core matching/linking continuing while permission denied (AGENTS.md permission boundary).
- Gap: guard mock (lines 67-80) mirrors only the transient-keyword heuristic and omits the `RetryableWorkerException` branch, so the three "stops retrying / handles db error" tests partially assert the mock's classifier. Align the stub with WarrantyExpirationWorkerTest's faithful mirror or use the real guard.

### test/…/service/warranty/WarrantyExpirationWorkerTest.kt — KEEP (P0)
- Real in-memory Room `WarrantyReminderDeliveryDao`: claim-before-notify atomicity, cross-run dedup ("already-sent … not re-notified", line 188), claim-race protection via pre-seeded SENT row, FAILED reason codes, notificationId range guard, TimeProvider-derived timestamps.
- Guard mock is the documented faithful mirror (CancellationException rethrow highest, then `RetryableWorkerException`, then transient heuristic, lines 76-92) — the right pattern the other worker tests should copy.

### test/…/startup/AppStartupCoordinatorRecoveryTest.kt — KEEP (P0) (merged batch-26 file)
- Fail-closed crash-recovery contract: unrecoverable SWAPPING journal → `CRITICAL_RECOVERY_REQUIRED`, writes blocked, and mode **survives a simulated process restart** using real journal files + persisted SharedPreferences (lines 115-147); success restart-required mode still auto-resets (line 150); stale-cutoff derived from injected TimeProvider (line 181).
- Exercises the legal restore/write-barrier path (RestoreJournal/RestoreMaintenanceMode), not a bypass. Small, deterministic, high value.

### test/…/service/RecommendationLifecycleManagerTest.kt — STRENGTHEN (P2)
- Setup reflectively injects a TimeProvider mock into a private `RecommendationRepository.timeProvider` field (lines 54-59) **unguarded** — guaranteed break on refactor (FRAGILE); StateManagerTest does the same but try/catches `NoSuchFieldException`.
- Body is mostly `coVerify` interaction checks; real value is confined to ordering, single-start periodic guard, and continue-after-error tests (~6 of 32).

## Area gaps (what is NOT tested in this area)

- No test exercises the real `NotificationCaptureService.captureNotification`/`processNotification` pipeline (extras sanitization on a real `StatusBarNotification`); only the key-set constant and reflection signatures are covered here — the NonCancellable durability behavior named in CleanupTest has no behavioral owner.
- Action workers (Snooze/Dismiss): no tests for guard `Skipped`/`Retry`/`Failed` → `ListenableWorker.Result` mapping, nor idempotency across WorkManager retries (double-dismiss/double-snooze).
- `RecommendationLifecycleManager` periodic loop is only ever mock-verified; no fake-time integration of the 6h scheduler.
- `ReceiptMatchingWorker`: retry path under the real guard classifier (e.g. SQLITE_BUSY → `Result.retry()`) untested; `Suggested`-match notification suppression paths only partially covered.
- `BillReminderWorker`: no combined quiet-hours + permission-denied test; no diagnostic-event assertion on guard skip.
- NotificationFilter: finance-package decision when only bigText carries the amount (text empty) is untested (discovery mode covers bigText, finance tests use text).
- RecommendationCacheService TTL of the cache *entry* (`cachedAt`-based) is untestable with current seams — only recommendation `expiresAt` is exercised.

## Rollup

- Verdicts: KEEP 13 · STRENGTHEN 3 · MERGE 0 · REWRITE 0 · DELETE 1 · NIGHTLY 0 · UNKNOWN 0
- Priority: P0 ×3 (ReceiptMatchingWorkerTest, WarrantyExpirationWorkerTest, AppStartupCoordinatorRecoveryTest) · P1 ×5 · P2 ×8 · P4 ×1
- DUP pairs: none (BillReminderWorkerTest vs BillReminderWorkerTimeProviderTest and Snooze vs Dismiss action tests are same-class/mirror-structured but behaviorally complementary; all overlap with HomeViewModel*/WorkerGuardStaticVerificationTest is complementary unit-vs-static/vm)
- FRAGILE count: 3 (NotificationCaptureServiceCleanupTest reflection signatures; RecommendationLifecycleManagerTest unguarded reflection; RecommendationStateManagerTest guarded reflection)
- Prior-audit outcomes: 1 stale KEEP overturned (FallbackTest → DELETE P4); 1 prior DELETE confirmed already executed (StressTest gone); 5 KEEP verdicts re-confirmed
