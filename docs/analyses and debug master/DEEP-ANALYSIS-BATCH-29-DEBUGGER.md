# Deep Analysis — Batch 29: Database — Models/DTOs (@debugger)

## Scope
- data/database/model/DashboardWidgetConfig.kt
- data/database/model/ExpenseGroupWithDetails.kt
- data/database/model/ExpenseWithCategory.kt
- data/database/model/ExpenseWithCategoryName.kt
- data/database/model/ExpenseWithCategory_Extensions.kt
- data/database/model/PendingReviewWithReceipt.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | ExpenseWithCategory.kt:41 | **HIGH** | Logic Error | `ExpenseWithCategory.formattedAmount` formats `expense.amount` instead of `expense.effectiveAmount` and drops transaction sign semantics, so shared/not-mine and deposit/withdrawal rows can display values inconsistent with analytics/totals. | 1. View shared expense in transaction list. 2. `formattedAmount` shows full amount (€120) instead of user's share (€30). 3. Analytics show €30 but list shows €120. | Format from `effectiveAmount` with explicit signed rules, or move formatting out of the DTO into one canonical formatter. |
| 2 | ExpenseWithCategory_Extensions.kt:14 | **HIGH** | Code Quality | `ExpenseWithCategory_Extensions.kt` defines `formattedDate`/`formattedAmount` with different logic than the member properties already declared on `ExpenseWithCategory`; Kotlin resolves members before extensions, so this API is silently shadowed and misleading. | 1. Developer calls `expenseWithCategory.formattedAmount`. 2. Member property is used, not extension. 3. Extension logic is dead code but appears functional. | Remove the duplicate extensions or rename them, and keep a single formatting contract with tests. |
| 3 | ExpenseRepository.kt:183 | **HIGH** | Data Integrity | Dynamic paged queries project only a partial `Expense` column set into `ExpenseWithCategory`, so embedded `Expense` instances can lose persisted fields and fall back to defaults/nulls for newer columns (e.g., business/split fields). | 1. Query uses `SELECT e.id, e.amount, ...` (partial columns). 2. Room maps to `ExpenseWithCategory` with embedded `Expense`. 3. Unselected columns get defaults/nulls. 4. `isNotMine` defaults to false for all results. | Change the raw query to `SELECT e.*` or use a dedicated projection DTO that exactly matches the selected columns. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| C1 | ExpenseWithCategory ↔ Transaction List UI | **HIGH** | Display Inconsistency | `formattedAmount` uses raw `amount` instead of `effectiveAmount`, so shared expenses display differently in lists vs. analytics. | Use `effectiveAmount` for formatting. |
| C2 | ExpenseWithCategory ↔ Extension Functions | **MEDIUM** | Shadowed API | Extension functions are silently shadowed by member properties, creating dead code that appears functional. | Remove or rename extensions. |
| C3 | ExpenseRepository ↔ ExpenseWithCategory | **HIGH** | Partial Projection | Dynamic paged queries project partial columns into embedded entities, causing field loss for newer columns. | Use `SELECT e.*` or dedicated projection DTOs. |

## Summary
- **Total issues: 6** (3 file-level + 3 cross-component)
- **Critical: 0**, **High: 3**, **Medium: 1**, **Low: 0**
- **Files with issues: 3/6**

## Key Patterns

### 1. Formatting Inconsistency
`formattedAmount` exists in multiple places with different logic: member property uses `amount`, extension may differ, and neither uses `effectiveAmount`. This creates display inconsistency across the app.

### 2. Partial Entity Projection
Dynamic paged queries select partial columns but map to full embedded entities, causing newer columns to default to null/false. This is a subtle data integrity issue that gets worse as the schema evolves.
