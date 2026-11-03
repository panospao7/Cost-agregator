# Plan: fix-1.0.4-receipts-statements (batch D)

Written by feature-planner (read-only, Sonnet) on 2026-11-03 at 8de38722; persisted by the orchestrator. Decisions are taken under owner item 116.

Paths are relative to `app/src/main/java/com/yourname/expensetracker/`. (U) = not verified by the planner. Evidence: `build/review-wt/build/full-review-2026-11/` (05p, 05j, 05n, 05z).

**Scope limit.** The other session owns money display (B1), neutralisation (B2), DR-01..08 (B3) and BL-04..11 (B4). Avoid `ExpenseDao`, `TransactionLifecycleCoordinator` and `config/guards/db_raw_query_classification.yml`. No schema change in this batch.

## Re-anchor (at 8de38722)
| Finding | State | Evidence |
|---|---|---|
| P-03-1 | STILL-PRESENT | `ReceiptExpenseLink.kt:50-56` unique only on (receiptId, expenseId). `ReceiptLinkService.kt:234-244` checks only `getLinksForReceipt`. Sole insert at :256. |
| P-03-1 part 2, stale status after expense delete | STILL-PRESENT | `ScannedReceiptDao.kt:181` excludes matched statuses. |
| P-03-2 | STILL-PRESENT | `ReceiptMatchingViewModel.kt:158,335` omit `requireUnmatchedClaim`. `updateMatchRejected` and `clearMatchFields` (`ScannedReceiptDao.kt:146-160`) have no `expenseId IS NULL` guard. Raw `e.message` at :185, :354. |
| P-03-3 | DEMOTED | `CategorizeReceiptItemsUseCase.kt:141,222-228`. No reader of `ANALYZING` outside entity, DAO and use case (UI not opened, U). |
| N-06-1 | STILL-PRESENT, debug-gated | `BankStatementLifecycleProcessor.kt:1066-1075,1107-1127`; coordinator returns DuplicateSkipped on its own (`TransactionLifecycleCoordinator.kt:1039,1093`, U). |
| N-06-2 | STILL-PRESENT, debug-gated | Receipt with `imageHash` commits at :608-678, reviews follow at :1132; recovery never frees the hash (`BankSyncStartupRecovery.kt:72-76`). |
| J-05-1 | STILL-PRESENT, narrower | `ReviewScreen.kt:1980,1990-1991` gate on `editedType`; `ReviewQueueRepository.kt:214-222` falls back to suggested direction and account. An edited direction or account is dropped. Shipped builds. |
| P-08-4 | STILL-PRESENT | `transactionFingerprint = merchantKey` at processor :1047,1089,1144. `BankStatementImportItemDao.kt:26-27` redacts only `merchant`. `existsByRunAndFingerprint` (:20) has no callers. Nullable, write-only column. |
| Follow-up: gate before mutex | STILL-PRESENT | `CurrencyRatesRepositoryImpl.kt:108-132` gate, :135 lock, no re-check. |
| Follow-up: dedupe window | STILL-PRESENT | `NotificationCaptureService.kt:471` permit, :605 clock read, :608 `tryStart`, which reads its own clock (`NotificationCaptureDeduper.kt:34`). |

## Decisions (orchestrator, owner item 116)
1. **P-03-1:** fix in the service, for new writes only. No unique index: existing rows may already break it, and a destructive dedupe needs the owner. No design for many receipts per expense was found (the only grep hit is unrelated), and the finding treats it as an invariant, so one non-statement receipt per expense is enforced. This is a visible behaviour change; report it.
2. **P-03-1 part 2:** deferred (touches `TransactionLifecycleCoordinator`).
3. **P-03-3:** deferred, no behavioural effect (U).
4. **N-06-1, N-06-2:** deferred to the statement-import design. Both are debug-gated, and N-06-2 alone stays partial. The planner's S4 is dropped from this batch.
5. **P-08-4:** stop writing the fingerprint and clean it through retention and export. Flipping the KNOWN DEFECT assertion at `RetentionTargetCoverageGapTest.kt:397-404` is the intended fix and strengthens the test, not a weakening.
6. **Test independence:** S1, S2, S3, S5, S6 are money or privacy, so each gets a separate test-author, which overrides the planner's single writer for S5 and S6. S7 gets one writer.
7. **BL-02:** the planner called it B4's, but the recorded B4 is BL-04..11 and no plan names BL-02. It is a separate CRITICAL finding, still present structurally, and gets its own plan.

## Slices
### Lane 1, `Cost-agregator-we`, `feature/fix104-rs-receipts` (S1 then S2)
- **S1. P-03-1. T1.** `domain/receipt/lifecycle/ReceiptLinkService.kt`.
  - Inside the existing transaction, for non-BANK_STATEMENT receipts, reject when `getLinksForExpense(expenseId)` holds a link from another non-statement receipt, checking its type with `scannedReceiptDao.getById`. No new DAO query.
  - Tests: new `ReceiptLinkServiceExpenseUniquenessTest`: two receipts to one expense fail; statement receipts still link many expenses; same receipt and expense stays a duplicate. Existing `ReceiptLinkService*Test`, `ReceiptLifecycleDbContractTest`.
- **S2. P-03-2. T1.** `domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt`, `ui/screens/receiptmatching/ReceiptMatchingViewModel.kt`.
  - Pass `requireUnmatchedClaim = true` at :158, :335 and :202. Leave `manualMatch` :272 unclaimed if it may act on REJECTED receipts (verify).
  - In `rejectAllSuggestions` and `clearMatchForReceipt`, skip the write and the event when `receipt.expenseId != null`, inside the transaction. DAO unchanged.
  - Replace `e.message` at :185, :354 with fixed text, following how the view model already surfaces errors.
  - Tests: `ReceiptMatchingViewModelTest` (a claimed receipt fails with no overwrite and no raw message in state); new `ReceiptMatchLifecycleServiceGuardTest`.

### Lane 2, `Cost-agregator-rsv`, `feature/fix104-rs-fingerprint` (S3)
- **S3. P-08-4. T2, privacy.** `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` (3 lines), `data/database/dao/BankStatementImportItemDao.kt`, `data/privacy/ExportAnonymizer.kt`.
  - Write `transactionFingerprint = null` at the 3 sites. Extend the existing `redactMerchantOlderThan` SQL to null it; change the existing query, add none. Null it in `ExportAnonymizer`, column-guarded. Legacy rows are cleaned by retention.
  - Tests: flip the assertion at `RetentionTargetCoverageGapTest.kt:397-404`; `ExportAnonymizerTest` (U, exists?); a processor test that no fingerprint is persisted.

### Lane 3, `Cost-agregator-sgd`, `feature/fix104-rs-small` (S5, S6, S7, in that order)
- **S5. J-05-1. T3.** `ui/screens/review/ReviewScreen.kt`. Key the transfer-field submit on `selectedType`, not `editedType`. Extract a pure internal helper. Tests: helper unit test.
- **S6. Gate re-check. T2, network.** `data/repository/CurrencyRatesRepositoryImpl.kt`. One gate function, called before the lock and again inside `dailyMutex.withLock`, before `runDailyAttempt`. Tests: `CurrencyRatesRepositoryImplTest`: hold refresh 1 at the opener, flip the gate to Denied, release; refresh 2 returns `DisabledByPrivacy` and the opener count stays 1.
- **S7. Dedupe arrival time. T2.** `domain/notification/capture/NotificationCaptureDeduper.kt`, `service/NotificationCaptureService.kt`. Add `tryStart(key, windowMs, nowMs: Long)`, keeping the two-argument delegate. Take `arrivedAt` before `captureLimiter.withPermit` (:471), pass it at :608. Check both clocks are the same source (U). Tests: update `deduper.tryStart` mocks in the four `NotificationCaptureService*Test` classes and `NotificationCaptureDeduperTest`; add one where a permit wait longer than the window still suppresses a duplicate.

## Validation (validation-runner only, through the queue)
- Lane 1: `*ReceiptLinkService*`, `*ReceiptMatchingViewModelTest`, `*ReceiptMatchLifecycleService*`, `*ReceiptLifecycleDbContractTest`.
- Lane 2: `*RetentionTargetCoverageGapTest`, `*ExportAnonymizerTest`, `*BankStatementLifecycleProcessor*`.
- Lane 3: `*CurrencyRatesRepositoryImplTest`, `*NotificationCaptureService*Test`, `*NotificationCaptureDeduperTest`, the ReviewScreen helper test.
- Each lane's targeted run before combining. Then one `static-guards` and one `app-check` on the candidate. No migration tests, no schema change.
- Guard traps: `db_access` flags `.delete()` or `.insert*` on untyped receivers; no new `@Query` methods; no raw `runCatching` in suspend functions.
- Combined guardian per lane; `reviewer-strict` at the candidate.

## Deferred, recorded
P-03-1 part 2; legacy duplicate receipt links; P-03-3; N-06-1 and N-06-2 (statement import design, may need an additive nullable `pending_reviews` column); statement midnight versus daytime expense matching.

## Status
| Slice | State |
|---|---|
| S1, S2 | 84d33c24; guardian one MAJOR, fixed; targeted vr-20261103-083419-48688c9f PASS (139 tests) |
| S3 | committed 44bbbda8; guardian PASS-WITH-MINORS; targeted vr-20261103-074224-f5f25984 PASS |
| S5, S6, S7 | committed 9ada98df; guardian PASS-WITH-MINORS; targeted vr-20261103-074913-f6c234c7 PASS |

## Notes during implementation
- S3: source done. No reader of `transactionFingerprint` was found. Both old WHERE clauses would have skipped rows with a fingerprint and a null merchant; both fixed. `BackupVerifier` names the table as TIER_1_EXACT; its row comparison was not read, so the guardian should check it.
- S7, design changed by the orchestrator (item 116): the plan's step mixed clocks, because the deduper is monotonic and the service `timeProvider` is wall clock. Now `deduper.markArrival()` plus `tryStart(key, windowMs, arrivedAtNanos)`, with an absolute gap and the later timestamp kept when arrivals are out of order.
- S5, S6: source done, test-author follows S7 in the same lane.
- S1, regression caught by its writer: the email `DuplicateSkipped` branch (`ReceiptLifecycleCoordinator.kt:1239-1249`) throws on a link failure, so a second email receipt for an expense that already has one would roll back the whole receipt save. Decision (item 116): the refusal becomes a typed exception; that one branch keeps the receipt saved and unlinked, flagged for review, instead of failing. This adds the coordinator to Lane 1's write set. The other session's branches do not touch that file (checked with git).
- S1 deviation: `claimForAutoMatch` hardcodes AUTO_MATCHED, so passing the claim flag on approve would have downgraded MANUALLY_MATCHED. The status is rewritten after a successful claim, in the same transaction.
- S7: done with tests. An existing test changed: `NotificationCaptureDeduperTest` "impossible backward read" now steps back 4 s instead of 5 s, because an absolute gap of exactly one window now admits. Deliberate: an older arrival beyond the window is treated as a separate event and not suppressed. The reviewer should confirm. `cleanupExpired` runs only on listener reconnect, so an older queued duplicate could pass after a reconnect; low likelihood, and canonical checks follow.
- S3: committed 44bbbda8; targeted job 1793691733629_rsv queued. Guardian PASS-WITH-MINORS: `BackupVerifier` compares row counts only, so no false mismatch; a pre-change backup restores fingerprints and retention then cleans them; rows newer than the cutoff keep the legacy key until they age out. Not checked: whether the backup file passes through `ExportAnonymizer`. Doc minors to apply at the candidate, after the lane's run, so that result stays valid: `docs/releases/statement-import/statement-import-investigation.md:123,131`, comment at `RetentionTargetCoverageGapTest.kt:356-357`, `ExportAnonymizer` KDoc :32.
- S1: email branch fixed. A second email receipt for an already-receipted expense returns `NeedsReview("expense_already_linked")`; the receipt is saved unlinked with a `PendingReview`. Other link failures still roll back. Stale KDoc at `EmailReceiptProcessResult.kt:25`, to update at the candidate. S5, S6, S7 committed on feature/fix104-rs-small.
- Lane 3 guardian on 9ada98df: PASS-WITH-MINORS. To apply in one pass at the candidate: `CurrencyRatesRepositoryImplTest.refreshWhileGateFlips` counts down `secondPastPreLockGate` before `behavior()` runs, which can give a spurious failure, so count down after it returns. Recorded, not fixed: a suggested-DEPOSIT row where the user switches to TRANSFER, edits, and switches back submits the hidden edited direction or account (amount and currency unaffected; a type changed to DEPOSIT already behaved this way); `ReviewScreenTransferSubmitTest` covers the helper only, so reverting the call site at `ReviewScreen.kt:2015` fails no test.
- S1, S2 committed 9be1a671 (plus the view model's four missing cancellation rethrows, found by the test-author). Targeted vr-20261103-081009-05382e67 FAIL: 17 of 18 view model tests pass; `INV_P032 rerun of a claimed receipt...` expects the rerun error but state has null. Guardian running; fixes go in one pass after it reports.
- Lane 1 guardian on 9be1a671 (first attempt lost to an API timeout, retried narrower, tests and docs not covered by it): one MAJOR. `ReceiptTransactionMatcher.findBestMatch` never excludes expenses another receipt owns, so an owned top candidate is refused on every periodic run, a lower-scored one is never tried, and a suggestion can never be approved. Confirmed OK: a suggestion writes only `suggestedExpenseId`, so SUGGESTED receipts still approve; reject and clear are not blocked for real rows; other link callers use new expenses. Fix pass (review round 1 for this lane): shared ownership rule used by the matcher; rerun error no longer erased by the reload (pre-existing, found by the failing test); a clear message when a link is refused.
- Recorded residual: approving the `expense_already_linked` review re-runs creation, likely ending DUPLICATE with the receipt unlinked. Unedited, it stays unlinked. Edited, it creates a new expense and links it.
- Combined candidate on `Cost-agregator-int`, branch feature/fix104-rs-candidate: batches D and E merged from 8de38722, plus the recorded doc minors and the S6 test race fix.

## reviewer-strict on 92346ad0: REJECT (round 1 of 2)
- MAJOR, J-05-1 regression: unedited TRANSFER or DEPOSIT rows now submit seeded values, and bulk approve (`ReviewViewModel.kt:558-559`) propagates them. Same merchant, row A OUTGOING/"Savings", row B INCOMING/"Main": approve-all on A stores B as OUTGOING/"Savings". Fix: submit only values changed from the suggestion.
- MINOR: new `Timber.e(e, ...)` at `ReceiptMatchingViewModel.kt:371` logs a raw exception; use the class name.
- Recorded, not fixed: post-commit matching can link an `expense_already_linked` receipt to a near-identical expense (same merchant and amount, within about 12h); the email refusal test mocks the link service and `AppDatabase`, so the real no-rollback path is not exercised.
- Verified by the reviewer: P-03-1, P-03-2, P-08-4, the gate re-check and the dedupe clock. All changed existing tests are corrections, not weakenings.

## Fix pass, round 1
- J-05-1: 9f4db9dc submits a transfer value only when it differs from the dialog's seed, so unedited rows, alone or in bulk, fall back to their own suggestion. A direction or account edit on one row now reaches the others in a bulk approve; at base such an edit was dropped for every row. Other types never submit hidden values. Targeted vr-20261103-091140-17904d82 PASS. Bulk approve is pinned at view model level with a mocked repository; the Compose call site is not pinned.
- e7276d60: rerun failures log the exception class only.

## reviewer-strict on c6c51b19: ACCEPT-WITH-MINORS (round 2 of 2)
- m2, for the owner: with A OUTGOING and B OUTGOING, editing A to INCOMING makes B INCOMING in a bulk approve; at base A stayed wrong and B stayed right. Reviewer's judgement: keep propagation, but apply it only to rows whose suggestion equals A's seed. Orchestrator (item 116): take that recommendation in the follow-up lane.
- Direction's effect on totals (`TransferDirectionAnalytics`) was not traced.
- Follow-up outside this batch: raw `Timber.e(e, ...)` remains in `ReceiptMatchingViewModel.kt` at :140, :169, :188, :217, :247, :295, :303, :321 (pre-existing).
