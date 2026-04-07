# Final Verification — Batch 33: Repositories — Remaining

> **[RESOLVED BY A.1]** The `effectiveAmount` vs `amount` inconsistency has been standardized across the codebase. All related issues in this batch are now resolved.

## Scope
### Scoped repository files
- `com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
- `com/yourname/expensetracker/data/repository/NotificationRepository.kt`
- `com/yourname/expensetracker/data/repository/PlannedExpenseRepository.kt`
- `com/yourname/expensetracker/data/repository/PromptStateRepository.kt`
- `com/yourname/expensetracker/data/repository/ReceiptItemCategorizationRepository.kt`
- `com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
- `com/yourname/expensetracker/data/repository/RecommendationRepository.kt`
- `com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt`
- `com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`
- `com/yourname/expensetracker/data/repository/SavingsGoalRepository.kt`
- `com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt`
- `com/yourname/expensetracker/data/repository/SourceStatsRepository.kt`
- `com/yourname/expensetracker/data/repository/SubscriptionManagementRepository.kt`
- `com/yourname/expensetracker/data/repository/UserCorrectionRepository.kt`
- `com/yourname/expensetracker/data/repository/WidgetStyleRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/AccountingExportRepository.kt`

### Supporting validation files read during verification
- `com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
- `com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt`
- `com/yourname/expensetracker/data/database/dao/PromptStateDao.kt`
- `com/yourname/expensetracker/data/database/dao/RawNotificationDao.kt`
- `com/yourname/expensetracker/data/database/dao/RecommendationDao.kt`
- `com/yourname/expensetracker/data/database/dao/SourceStatsDao.kt`
- `com/yourname/expensetracker/data/database/dao/SubscriptionCandidateDao.kt`
- `com/yourname/expensetracker/data/database/entity/Expense.kt`
- `com/yourname/expensetracker/data/database/entity/GroupExpense.kt`
- `com/yourname/expensetracker/data/database/entity/GroupMember.kt`
- `com/yourname/expensetracker/data/database/entity/PendingReview.kt`
- `com/yourname/expensetracker/data/database/entity/PromptState.kt`
- `com/yourname/expensetracker/data/database/entity/RawNotification.kt`
- `com/yourname/expensetracker/data/database/entity/RecommendationEntity.kt`
- `com/yourname/expensetracker/data/database/entity/SourceStats.kt`
- `com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt`
- `com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`
- `com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt`
- `com/yourname/expensetracker/domain/groups/SharedExpensePort.kt`
- `com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt`
- `com/yourname/expensetracker/domain/usecase/savings/LifestyleSavingsPromptUseCase.kt`
- `com/yourname/expensetracker/service/RecommendationDeduplicator.kt`
- `com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt:153-185` | Medium | Data integrity / duplicate handling | The oversized-amount fallback still inserts a `PendingReview` without any semantic duplicate check. Exact raw duplicates are blocked by `insertOrIgnore`, but separate notifications for the same oversized transaction can still create duplicate review rows because this path skips the normal pending-review duplicate logic. | B | DOWNGRADED | Reuse `handleNeedsReviewInTransaction()` duplicate checks, or add a DB-level uniqueness/upsert path for fallback reviews. |
| 2 | `com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt:413-445`<br>`com/yourname/expensetracker/data/repository/ReceiptRepository.kt:517-530,557-565,674-695`<br>`com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt:95-118` | High | Incorrect dedupe contract | Duplicate detection is still currency-blind across notification auto-accept, statement import, and review approval. `Expense.generateDedupeKey(...)` and the expense duplicate queries ignore currency, so same-merchant/same-amount/same-time transactions in different currencies can be dropped as duplicates. | B | CONFIRMED | Include currency in the dedupe key and in every duplicate query/helper before insert-or-skip decisions. |
| 3 | `com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt:653-665,683-733` | Medium | Race condition | Subscription detection is still fire-and-forget and still uses non-atomic pending-candidate checks on a table that has only a non-unique index on `canonicalMerchant`. Concurrent jobs can insert duplicate pending candidates for the same merchant. | B | DOWNGRADED | Add a unique pending-candidate signature/upsert, or serialize detection per merchant inside one transactional write path. |
| 4 | `com/yourname/expensetracker/data/repository/NotificationRepository.kt:125-132` | Medium | Data integrity / stale state | `deleteAll()` wipes notifications, expenses, reviews, and corrections but only zeroes `pendingReview` in `source_stats`. Accepted/rejected/duplicate/total counts remain stale and can skew trust-score-driven routing after a supposed full reset. | B | CONFIRMED | Delete or fully rebuild `source_stats` inside the same reset transaction. |
| 5 | `com/yourname/expensetracker/data/repository/PromptStateRepository.kt:39-47` | — | Logic error | The reported bug is not the actual failing behavior. In current code, prompt acknowledgments are the only prompt-state writes used by the lifestyle flow, so inserting an action row does not itself break the cooldown after the user acts. The real bug is that prompt display is never recorded when the recommendation is shown. | B | FALSE_POSITIVE | Keep action persistence, but record prompt presentation when the recommendation is surfaced and only use DAO acknowledgment updates when a shown prompt row exists. |
| 6 | `com/yourname/expensetracker/data/repository/RecommendationRepository.kt:74-103` | High | Contract drift / duplicate persistence | `saveAll()` still does not enforce the advertised “max 5 active recommendations” contract against the combined active set. It only caps the incoming batch, and its existing-vs-new duplicate comparison uses a different signature shape than `RecommendationDeduplicator`, so cross-call dedupe is unreliable even before considering concurrency. | B | CONFIRMED | Use one canonical persisted signature with a unique index, compare existing and new rows with the same algorithm, and enforce the active-cap transactionally against total active rows. |
| 7 | `com/yourname/expensetracker/data/repository/RecommendationRepository.kt:42-45` | Medium | Stale reactive data | `observeActiveForUser()` still binds Room to a `nowMillis` default evaluated when the flow is created. Expired recommendations can remain visible until another DB write invalidates the query. | B | CONFIRMED | Recompute against a live clock/ticker or observe broader data and filter with current time in the repository layer. |
| 8 | `com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt:366-380,429-436` | Medium | Data integrity | `markAsRelevant(true)` still inserts a new `PendingReview` when reparsing fails and does not check whether that raw notification already has a review. Repeating the debug/manual recovery action can create duplicate pending rows for one `rawNotificationId`. | B | DOWNGRADED | Check `getByRawId(...)` before inserting, or enforce uniqueness/upsert on pending reviews keyed by raw notification. |
| 9 | `com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt:49-50` | High | Invariant violation | `addExpense()` still bypasses `GroupTransactionCoordinator` and inserts `GroupExpense` directly. Because the schema only validates `groupId` and `paidById` independently, it still allows cross-group payer mismatches and archived-group inserts. | B | CONFIRMED | Route group-expense creation through the coordinator, or add DB constraints that enforce payer/group consistency and active-group rules. |
| 10 | `com/yourname/expensetracker/data/repository/WidgetStyleRepositoryImpl.kt:31-35,45-60` | Medium | Reliability / data loss | The DataStore flow still lacks `catch`, so store I/O/corruption can terminate `config()`. `parseConfig()` is still all-or-nothing, so one bad widget entry or enum value resets the whole style map to defaults. | B | CONFIRMED | Add DataStore recovery with `catch`, log failures, and parse each widget entry independently. |
| 11 | `com/yourname/expensetracker/data/repository/AccountingExportRepository.kt:50` | High | Data truncation | `exportExpenses()` still reads through `expenseRepository.getExpensesBetween(...)`, which inherits the DAO’s default 2000-row cap. Large exports are silently incomplete. | B | CONFIRMED | Page through `getExpensesBetweenPagedForDeterministicExport(...)` until exhaustion before generating the file. |
| 12 | `com/yourname/expensetracker/data/repository/AccountingExportRepository.kt:78-80,113-160` | High | Incorrect reporting | The accountant report still sums raw amounts across currencies and hardcodes `€` in totals and large-transaction lines. Mixed-currency reports are therefore mathematically meaningless and mislabeled. | B | CONFIRMED | Restrict to one currency, convert first, or group totals by currency with the correct code/symbol. |
| 13 | `com/yourname/expensetracker/data/repository/AccountingExportRepository.kt:18-23,64-80` | Medium | API / format mismatch | `ACCOUNTANT_REPORT_PDF` still writes a plain-text `.txt` file, not a PDF artifact. | B | CONFIRMED | Rename the format to a text report or generate an actual PDF. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/usecase/savings/LifestyleSavingsPromptUseCase.kt:41-49` | High | Anti-nag logic bug | The lifestyle prompt flow never records that a prompt was shown. `evaluateAndPrompt()` checks `hasPromptedRecently(...)` but no caller persists `recordPrompt(...)` when the recommendation is surfaced, so if the user ignores the prompt and revisits the screen, cooldown suppression never starts. | Record prompt presentation when the recommendation is actually shown (UI/use-case boundary), then update that row on user action. |
| 2 | `com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt:42-43` | Medium | Invariant bypass | `addMember()` also bypasses `GroupTransactionCoordinator.addMemberToGroup(...)` and inserts members directly. That means archived/inactive-group validation is skipped for member creation just like expense creation. | Route member creation through the coordinator or add explicit active-group validation in the adapter before insert. |
| 3 | `com/yourname/expensetracker/data/repository/AccountingExportRepository.kt:41-46` | Medium | API contract mismatch | `includeReceipts` is exposed on `exportExpenses(...)` but never used. Any UI/API path that offers “include receipts” gets no behavioral change. | Either implement receipt inclusion/export bundling or remove the parameter and related UI affordance. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `R#5 / D#4` | `com/yourname/expensetracker/data/repository/PromptStateRepository.kt:39-47` | Inserting an acknowledgment row is not the direct bug in the current flow; it is the only prompt-state write currently used after accept/dismiss/defer, and it does enforce cooldown after an action. The real defect is elsewhere: prompt presentation is never recorded when the recommendation is shown. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Notification ingestion → review approval → statement import | High | Data integrity | Duplicate suppression is still not based on a single currency-aware transaction identity, so legitimate cross-currency transactions can be dropped in three different pipelines. | `data/repository/NotificationProcessingPipeline.kt`, `data/repository/ReceiptRepository.kt`, `data/repository/ReviewQueueRepository.kt`, `data/database/entity/Expense.kt`, `data/database/dao/ExpenseDao.kt` | Centralize duplicate identity around amount + merchant + time bucket + currency and make every path use the same contract. |
| 2 | Notification auto-accept → async subscription detection → candidate queue | Medium | Race condition | Subscription detection runs asynchronously after commit, but candidate persistence is still check-then-insert on a non-unique merchant key. Duplicate candidate rows remain possible under concurrent accepted transactions. | `data/repository/NotificationProcessingPipeline.kt`, `data/database/entity/SubscriptionCandidate.kt`, `data/database/dao/SubscriptionCandidateDao.kt` | Add a DB-enforced uniqueness/upsert strategy for pending candidates. |
| 3 | Recommendation generation → repository persistence → dashboard active cards | High | Contract drift | Recommendation generation assumes repository-level dedupe and a stable 5-card cap, but persistence still allows hidden overflow/duplicate active rows. The UI only masks the issue by querying `LIMIT 5`. | `data/repository/RecommendationRepository.kt`, `data/database/dao/RecommendationDao.kt`, `data/database/entity/RecommendationEntity.kt`, `service/RecommendationDeduplicator.kt` | Persist one canonical signature and enforce dedupe/cap transactionally against total active rows. |
| 4 | Shared-expense domain service → data port adapter → group persistence | High | Invariant violation | The shared-expense port uses the coordinator for group creation but bypasses it for member and expense creation, so active-group and payer-membership rules are not enforced uniformly. | `domain/groups/SharedExpenseManager.kt`, `data/repository/SharedExpenseDataPortAdapter.kt`, `data/database/GroupTransactionCoordinator.kt`, `domain/groups/GroupTransactionCoordinator.kt` | Route all mutating shared-expense operations through the coordinator or enforce the same invariants in the adapter layer. |
| 5 | Lifestyle recommendation evaluation → prompt state persistence → savings UI | High | State tracking bug | The prompt-state repository has anti-nag support, but the lifestyle recommendation flow never records prompt presentation. Cooldown only starts after an explicit action, not after the prompt was shown. | `domain/usecase/savings/LifestyleSavingsPromptUseCase.kt`, `data/repository/PromptStateRepository.kt`, `data/database/dao/PromptStateDao.kt`, `ui/screens/savings/SavingsGoalsViewModel.kt` | Record prompt impressions at display time and update that same row when the user accepts/dismisses/defers. |
| 6 | Accounting export → expense reads → generated artifacts | High | Incorrect output | The export pipeline can be incomplete (2000-row cap), semantically wrong (mixed currencies reported as euros), misleadingly typed (PDF enum writes text), and feature-incomplete (`includeReceipts` ignored). | `data/repository/AccountingExportRepository.kt`, `data/repository/ExpenseRepository.kt`, `data/database/dao/ExpenseDao.kt` | Use paged deterministic reads, make currency handling explicit, align file formats with bytes produced, and either implement or remove receipt inclusion. |

## Summary
- Total verified issues: 12
- Confirmed: 12 (Critical: 0, High: 5, Medium: 7, Low: 0)
- False positives: 1
- Missed issues found: 3
- Files affected: 8/16

## Key Patterns
- The dominant defect pattern is **contract drift between repository APIs and actual behavior**: “max 5 recommendations,” “PDF export,” “include receipts,” and “full reset” all promise more than the implementation guarantees.
- **Transaction identity is still fragmented**. Currency-aware dedupe is not enforced consistently, so each ingestion/review pipeline can make different duplicate decisions for the same real-world transaction.
- **Coordinator bypasses are systemic in shared expenses**. Group creation is protected, but member/expense writes still bypass the invariant-enforcing transaction layer.
- **Background and reactive flows rely on snapshot time or check-then-insert logic**, which creates stale UI state and concurrency-sensitive duplicates.
- **Prompt lifecycle tracking is incomplete**: action state is stored, but prompt impression state is not, so anti-nag behavior does not match the intended model.
