# Final Verification — Batch 29: Database — Models/DTOs

## Scope
- `com/yourname/expensetracker/data/database/model/DashboardWidgetConfig.kt`
- `com/yourname/expensetracker/data/database/model/ExpenseGroupWithDetails.kt`
- `com/yourname/expensetracker/data/database/model/ExpenseWithCategory.kt`
- `com/yourname/expensetracker/data/database/model/ExpenseWithCategoryName.kt`
- `com/yourname/expensetracker/data/database/model/ExpenseWithCategory_Extensions.kt`
- `com/yourname/expensetracker/data/database/model/PendingReviewWithReceipt.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/data/database/model/ExpenseWithCategory.kt:41-42` | High | Logic / data transformation | `formattedAmount` is built from `expense.amount` and omits transaction polarity. The transaction list therefore renders raw unsigned values while totals, filters, and analytics use `expense.effectiveAmount`; shared and not-mine rows can visibly disagree with the rest of the app. | B | CONFIRMED | Format from `expense.effectiveAmount` with explicit sign rules, or remove formatting from the DTO and use one shared formatter at the UI boundary. |
| 2 | `com/yourname/expensetracker/data/database/model/ExpenseWithCategory_Extensions.kt:14-32` | Medium | API shadowing / maintainability | The extension properties `formattedDate` and `formattedAmount` are shadowed by the member properties already declared on `ExpenseWithCategory`. `TransactionsScreen` even imports the extensions, but Kotlin still resolves `transaction.formattedDate` / `formattedAmount` to the member properties, leaving a misleading dead API and silently preventing the extension behavior from ever being used. | B | DOWNGRADED | Delete the duplicate extensions or rename them, then keep a single tested formatting surface for `ExpenseWithCategory`. |
| 3 | `com/yourname/expensetracker/data/repository/ExpenseRepository.kt:183-190`<br>`com/yourname/expensetracker/data/database/model/ExpenseWithCategory.kt:17-25` | Medium | Partial projection / data integrity | `getExpensesPagedDynamic()` selects only a subset of `expenses` columns but maps the result into `ExpenseWithCategory`, which embeds a full `Expense`. Newly added fields such as `isBusinessExpense`, `businessPurpose`, `businessCategory`, `businessProject`, `requiresReceipt`, `splitTemplateId`, and `splitVisualization` are not projected, so paged results return partially hydrated `Expense` objects with default/null values instead of persisted data. | B | DOWNGRADED | Use `SELECT e.*` for this query, or replace `ExpenseWithCategory` with a dedicated projection DTO that exactly matches the selected columns. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/data/database/model/ExpenseWithCategory.kt:41-42` | Low | Localization / presentation | The user-facing `formattedAmount` hardcodes `Locale.US`, so transaction amounts always render with US numeric conventions regardless of device locale. This is a separate display defect from the wrong amount source. | Centralize formatting with `NumberFormat`/`Currency` using the active locale, ideally outside the database DTO. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| - | None | - | No original reviewer/debugger issue was a full false positive. Issues 2 and 3 were severity-overstated, but both describe real defects. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `Expense.effectiveAmount` → `ExpenseWithCategory.formattedAmount` → `TransactionsScreen` | High | Display inconsistency | The list row amount is derived from raw `amount`, while grouped totals and analytics use `effectiveAmount`. Shared/not-mine transactions can therefore show inconsistent spending numbers in the same screen. | `data/database/entity/Expense.kt`, `data/database/model/ExpenseWithCategory.kt`, `ui/screens/transactions/TransactionsScreen.kt` | Use one canonical amount formatter fed by `effectiveAmount`. |
| 2 | `ExpenseWithCategory_Extensions` → `TransactionsScreen` imports → `ExpenseWithCategory` members | Medium | Shadowed API contract | The UI imports the extension-based display API, but member-property precedence means the imported extensions never run. This creates contract drift between the advertised formatter surface and the values actually shown on screen. | `data/database/model/ExpenseWithCategory_Extensions.kt`, `data/database/model/ExpenseWithCategory.kt`, `ui/screens/transactions/TransactionsScreen.kt` | Remove the duplicate API and keep only one formatter contract. |
| 3 | `ExpenseRepository.getExpensesPagedDynamic` → `ExpenseDao.getExpensesDynamic` → `ExpenseWithCategory` | Medium | Projection drift | A repository raw query manually mirrors the `Expense` schema while returning a model that embeds the full entity. As the entity evolves, paged DTOs silently fall out of sync and return incomplete objects. | `data/repository/ExpenseRepository.kt`, `data/database/dao/ExpenseDao.kt`, `data/database/model/ExpenseWithCategory.kt`, `data/database/entity/Expense.kt` | Project `e.*` or introduce a DTO whose fields exactly match the raw query. |

## Summary
- Total verified issues: 3
- Confirmed: 3 (Critical: 0, High: 1, Medium: 2, Low: 0)
- False positives: 0
- Missed issues found: 1
- Files affected: 2/6

## Key Patterns
- `ExpenseWithCategory` currently mixes persistence concerns with UI formatting, and that formatting contract is duplicated again in shadowed extensions.
- The batch's simple relation wrappers (`DashboardWidgetConfig`, `ExpenseGroupWithDetails`, `ExpenseWithCategoryName`, `PendingReviewWithReceipt`) are structurally fine; the real problems are concentrated around `ExpenseWithCategory`.
- The paged/raw-query pipeline is brittle because it hydrates a full embedded entity from a hand-maintained partial projection, so schema growth will keep creating silent drift bugs.
