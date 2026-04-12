# Deep Analysis — Batch 29: Database — Models/DTOs (@reviewer)

> **[B.4 SYNC]** Issue dispositions below have been updated to reflect the B.4 final closeout. Authoritative resolutions are recorded in `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-29.md`.

## Scope
- `data/database/model/DashboardWidgetConfig.kt`
- `data/database/model/ExpenseGroupWithDetails.kt`
- `data/database/model/ExpenseWithCategory.kt`
- `data/database/model/ExpenseWithCategoryName.kt`
- `data/database/model/ExpenseWithCategory_Extensions.kt`
- `data/database/model/PendingReviewWithReceipt.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `app/src/main/java/com/yourname/expensetracker/data/database/model/ExpenseWithCategory.kt:41-42` | HIGH | Data transformation | `formattedAmount` is built from `expense.amount`, not `expense.effectiveAmount`, and it does not encode transaction polarity. Shared / not-mine transactions can therefore display a different amount from the totals, filters, and analytics that already use `effectiveAmount`; deposits/withdrawals also lose their sign semantics. **[RESOLVED BY B.4 — Batch 10]** | Format from `expense.effectiveAmount` with explicit signed/currency rules, or remove presentation formatting from the DTO and centralize it in one formatter. |
| 2 | `app/src/main/java/com/yourname/expensetracker/data/database/model/ExpenseWithCategory_Extensions.kt:14-32` | HIGH | Logic / Architecture | This file defines `formattedDate` and `formattedAmount` extension properties with different behavior from the member properties already declared on `ExpenseWithCategory` (`ExpenseWithCategory.kt:31-42`). In Kotlin, member properties win over extensions, so this API is silently shadowed and callers do not get the behavior this file advertises. **[RESOLVED BY B.4 — Batch 29 closeout: extension renamed from `formattedDate` to `formattedTime` (time-only "HH:mm" helper) so it no longer clashes with the `formattedDate` member property; dead `formattedAmount` extension deleted; `TransactionsScreen.kt` import updated from the stale extension reference to `formattedTime`]** | Keep exactly one formatter surface for `ExpenseWithCategory` and delete or rename the conflicting extensions; add regression tests that assert the rendered row text. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | `ExpenseRepository.getExpensesPagedDynamic` → `ExpenseDao.getExpensesDynamic` → `ExpenseWithCategory` | HIGH | Data transformation | The dynamic raw query projects only a subset of `Expense` columns (`ExpenseRepository.kt:183-190`), while `ExpenseWithCategory` embeds a full `Expense` (`ExpenseWithCategory.kt:18-25`). Newer `Expense` fields such as `isBusinessExpense`, `businessPurpose`, `businessCategory`, `businessProject`, `requiresReceipt`, `splitTemplateId`, and `splitVisualization` are not selected, so paged/dynamic consumers can receive default/null values instead of persisted data. **[RESOLVED BY B.4 — Batch 10; re-verified in late closeout: `SELECT e.*` confirmed present at `ExpenseRepository.kt:198`]** | Change the raw query to `SELECT e.*` (or otherwise include every `Expense` column), or replace `ExpenseWithCategory` with a dedicated projection DTO that exactly matches the selected columns. |

## Summary
- Total issues: 3
- Critical: 0, High: 3, Medium: 0, Low: 0
- Files with issues: 2/6

## Key Patterns
- `ExpenseWithCategory` currently mixes persistence DTO responsibilities with presentation formatting, and the same formatting contract is duplicated again in extensions with conflicting semantics.
- The batch's simple relation wrappers (`ExpenseGroupWithDetails`, `ExpenseWithCategoryName`, `PendingReviewWithReceipt`) are structurally sound, but `ExpenseWithCategory` is being used as a "full entity + display helper" object across multiple pipelines, which makes projection drift especially risky.
