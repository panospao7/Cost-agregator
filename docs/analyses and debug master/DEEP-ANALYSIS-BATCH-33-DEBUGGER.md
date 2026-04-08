# Deep Analysis — Batch 33: Repositories — Remaining (@debugger)

## Scope
- data/repository/NotificationProcessingPipeline.kt
- data/repository/NotificationRepository.kt
- data/repository/PlannedExpenseRepository.kt
- data/repository/PromptStateRepository.kt
- data/repository/ReceiptItemCategorizationRepository.kt
- data/repository/ReceiptRepository.kt
- data/repository/RecommendationRepository.kt
- data/repository/RecurringExpenseRepository.kt
- data/repository/ReviewQueueRepository.kt
- data/repository/SavingsGoalRepository.kt
- data/repository/SharedExpenseDataPortAdapter.kt
- data/repository/SourceStatsRepository.kt
- data/repository/SubscriptionManagementRepository.kt
- data/repository/UserCorrectionRepository.kt
- data/repository/WidgetStyleRepositoryImpl.kt
- data/repository/AccountingExportRepository.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | NotificationProcessingPipeline.kt:153 | **HIGH** | Data Integrity | Oversized-notification fallback skips duplicate checks and can create duplicate pending reviews. | 1. Notification exceeds size limit. 2. Fallback path creates pending review. 3. No duplicate check performed. 4. Same notification processed again creates duplicate review. | Reuse the normal pending-review duplicate logic before inserting fallback reviews. |
| 2 | NotificationProcessingPipeline.kt:413, ReceiptRepository.kt:517, ReviewQueueRepository.kt:95 | **HIGH** | Logic Error | Duplicate detection is currency-blind across notification, statement, and review flows, causing false duplicate drops for same amount/merchant in different currencies. | 1. €50 at "Store" and $50 at "Store". 2. Dedupe matches on amount+merchant only. 3. Second transaction dropped as duplicate. | Include currency in dedupe key and all duplicate queries. **[RESOLVED BY A.4]** Currency is now included in persisted dedupe keys and in the policy-aware duplicate candidate queries used by notification, statement, and review flows. |
| 3 | NotificationProcessingPipeline.kt:653 | **HIGH** | Race Condition | Async subscription detection uses non-atomic check-then-insert with no unique merchant constraint, so concurrent jobs can insert duplicate candidates. | 1. Two notifications for same merchant arrive. 2. Both pass dedupe check. 3. Both insert candidates. | Add DB uniqueness/upsert or serialize per merchant. |
| 4 | PromptStateRepository.kt:39 | **HIGH** | Logic Error | Prompt acknowledgments are recorded as new prompt rows instead of updating the shown prompt, breaking anti-nag/history semantics. | 1. User acknowledges prompt. 2. New row inserted instead of updating existing. 3. History shows multiple prompts instead of one acknowledged. | Update the existing/latest prompt row via DAO. |
| 5 | RecommendationRepository.kt:74 | **HIGH** | Race Condition | Recommendation max-5/dedup policy is not transactionally enforced, so concurrent saves can exceed limits and persist duplicates. | 1. Two recommendations saved concurrently. 2. Both check count < 5. 3. Both insert. 4. 6+ active recommendations exist. | Persist a unique signature and enforce cap atomically. |
| 6 | ReviewQueueRepository.kt:366 | **HIGH** | Data Integrity | `markAsRelevant(true)` can insert multiple pending reviews for the same raw notification. | 1. Mark notification as relevant. 2. Called twice. 3. Two pending reviews created. | Check existing review by `rawNotificationId` or upsert. |
| 7 | SharedExpenseDataPortAdapter.kt:49 | **HIGH** | Architecture | Shared-expense adapter bypasses the coordinator for group-expense inserts, allowing cross-group/member invariant violations. | 1. Insert group expense through adapter. 2. Coordinator validation skipped. 3. Expense linked to wrong group. | Route writes through `GroupTransactionCoordinator`. |
| 8 | AccountingExportRepository.kt:50 | **HIGH** | Data Loss | Accounting exports are silently truncated because export reads use a DAO path capped at 2000 rows. | 1. User has 5000 expenses. 2. Export includes only 2000. 3. Accountant receives incomplete data. | Page through the export range until exhaustion. |
| 9 | AccountingExportRepository.kt:78 | **HIGH** | Logic Error | Accountant report sums mixed currencies and labels totals as euros. | 1. Expenses in EUR, USD, GBP. 2. Report sums all as if EUR. 3. Total is meaningless. | Convert or group by currency before totaling. |
| 10 | NotificationRepository.kt:125 | **MEDIUM** | Data Integrity | Full notification reset leaves stale accepted/rejected/duplicate totals in source stats. | 1. Full notification reset performed. 2. Source stats still show old totals. 3. Analytics incorrect. | Clear/rebuild `source_stats` during full reset. |
| 11 | RecommendationRepository.kt:42 | **MEDIUM** | Logic Error | Active recommendation flow can show expired cards until another DB write occurs. | 1. Recommendation expires. 2. Still shown as active. 3. Next DB write triggers re-evaluation. | Re-evaluate against a live clock/ticker. |
| 12 | WidgetStyleRepositoryImpl.kt:31 | **MEDIUM** | Reliability | Widget-style config flow lacks corruption recovery and a single bad entry resets the whole map to defaults. | 1. One widget style entry corrupted. 2. Entire config resets to defaults. 3. All widget styles lost. | Add DataStore `catch` and per-entry parsing/logging. |
| 13 | AccountingExportRepository.kt:18 | **MEDIUM** | Logic Error | `ACCOUNTANT_REPORT_PDF` produces a `.txt` report, not a PDF. | 1. User selects PDF export. 2. Receives .txt file. | Rename the format or generate a real PDF. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| C1 | NotificationProcessingPipeline ↔ ReviewQueueRepository | **HIGH** | Duplicate Reviews | Oversized-notification fallback skips dedupe, creating duplicate pending reviews. | Reuse normal dedupe logic in fallback path. |
| C2 | All Repositories ↔ Currency | **HIGH** | Currency-Blind Dedupe | Duplicate detection ignores currency across notification, statement, and review flows. | Include currency in all dedupe keys. |
| C3 | RecommendationRepository ↔ RecommendationStateManager | **HIGH** | Cap Enforcement | Max-5 recommendation cap not enforced transactionally, allowing concurrent saves to exceed limit. | Enforce cap atomically with unique signature. |
| C4 | AccountingExportRepository ↔ ExpenseRepository | **HIGH** | Export Truncation | Export reads capped at 2000 rows, producing incomplete accountant reports. | Page through full range. |
| C5 | SharedExpenseDataPortAdapter ↔ GroupTransactionCoordinator | **HIGH** | Bypassed Validation | Adapter bypasses coordinator, allowing cross-group invariant violations. | Route through coordinator. |

## Summary
- **Total issues: 18** (13 file-level + 5 cross-component)
- **Critical: 0**, **High: 9**, **Medium: 4**, **Low: 0**
- **Files with issues: 13/16**

## Key Patterns

### 1. Currency-Blind Deduplication
Duplicate detection ignores currency across multiple flows (notification, statement, review), causing false positive matches for same-amount transactions in different currencies.

### 2. Silent Data Truncation
Export and query methods silently cap results at 2000 rows, producing incomplete data for exports, analytics, and reports.

### 3. Non-Atomic Check-Then-Insert
Multiple repositories use read-then-insert patterns without transactions or unique constraints, allowing concurrent operations to create duplicates.

### 4. Bypassed Validation
Shared-expense adapter bypasses the group transaction coordinator, allowing invariant violations.

### 5. Mixed Currency Aggregation
Accounting exports sum amounts across currencies without conversion, producing meaningless totals.
