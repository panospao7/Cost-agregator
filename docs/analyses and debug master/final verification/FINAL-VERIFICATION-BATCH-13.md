# Final Verification — Batch 13: Database - Group & Financial Entities

## Scope

### Batch entity files
- `com/yourname/expensetracker/data/database/entity/ExpenseGroup.kt`
- `com/yourname/expensetracker/data/database/entity/GroupMember.kt`
- `com/yourname/expensetracker/data/database/entity/GroupExpense.kt`
- `com/yourname/expensetracker/data/database/entity/SplitItemAssignment.kt`
- `com/yourname/expensetracker/data/database/entity/BankConnection.kt`
- `com/yourname/expensetracker/data/database/entity/ExchangeRate.kt`
- `com/yourname/expensetracker/data/database/entity/MerchantCanonical.kt`
- `com/yourname/expensetracker/data/database/entity/MerchantAlias.kt`
- `com/yourname/expensetracker/data/database/entity/MerchantLocation.kt`
- `com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt`
- `com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt`
- `com/yourname/expensetracker/data/database/entity/Investment.kt`

### Corroborating source files read during verification
- `com/yourname/expensetracker/data/database/AppDatabase.kt`
- `com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`
- `com/yourname/expensetracker/data/database/dao/BankConnectionDao.kt`
- `com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt`
- `com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt`
- `com/yourname/expensetracker/data/database/dao/GroupExpenseDao.kt`
- `com/yourname/expensetracker/data/database/dao/GroupMemberDao.kt`
- `com/yourname/expensetracker/data/database/dao/InvestmentDao.kt`
- `com/yourname/expensetracker/data/database/dao/MerchantLocationDao.kt`
- `com/yourname/expensetracker/data/database/dao/MerchantNormalizationDao.kt`
- `com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt`
- `com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/MerchantNormalizationRepository.kt`
- `com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt`
- `com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt`
- `com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt`
- `com/yourname/expensetracker/data/security/BankTokenCipher.kt`
- `com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt`
- `com/yourname/expensetracker/domain/bank/BankApiIntegration.kt`
- `com/yourname/expensetracker/domain/currency/CurrencyConverter.kt`
- `com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt`
- `com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt`
- `com/yourname/expensetracker/domain/intelligence/ml/MerchantNormalizer.kt`
- `com/yourname/expensetracker/domain/investment/InvestmentTracker.kt`
- `com/yourname/expensetracker/ui/screens/recurring/RecurringExpensesScreen.kt`
- `com/yourname/expensetracker/ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt`
- `com/yourname/expensetracker/ui/screens/subscription/SubscriptionManagementViewModel.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/data/database/entity/GroupMember.kt:24-25` | High | Constraint | The schema allows multiple rows per group with `isCurrentUser = 1`, but DAO reads use `LIMIT 1`, so current-user resolution becomes nondeterministic once data drifts. | R | CONFIRMED | Add a partial unique index on `groupId` where `isCurrentUser = 1`, or enforce a transactional "clear old current user, then set new one" write path. |
| 2 | `com/yourname/expensetracker/data/database/entity/GroupExpense.kt:36,46` | High | Constraint | `expenseId` is treated as a one-to-one link (`getGroupExpenseForExpense()` returns a single row) but is not unique, so one expense can be linked to multiple `group_expenses` rows. | R | CONFIRMED | Add a unique index for non-null `expenseId` (or make multi-linking explicit and change DAO/API semantics). |
| 3 | `com/yourname/expensetracker/data/database/entity/GroupExpense.kt:27-32` | High | Foreign Key | `paidById` only references `group_members.id`; the DB does not enforce that the payer belongs to the same `groupId` as the expense. | R | CONFIRMED | Model `(groupId, memberId)` as the actual relationship and reference that composite key from `group_expenses`. |
| 4 | `com/yourname/expensetracker/data/database/entity/BankConnection.kt:11-18,41` | Medium | Foreign Key | `defaultCategoryId` is used downstream as an internal category reference but has no FK to `categories`, so deleted categories can leave stale IDs behind. | R | CONFIRMED | Add a FK to `Category(id)` with `ON DELETE SET NULL` and recreate the table in migration SQL. |
| 5 | `com/yourname/expensetracker/data/database/entity/MerchantCanonical.kt:20-22,29` | High | Constraint | Merchant canonical lookup/creation is keyed by `searchKey`, but only `normalizedName` is unique. Different display names can therefore collapse to the same `searchKey` and make `getCanonicalBySearchKey(... LIMIT 1)` nondeterministic. | R | CONFIRMED | Make `searchKey` unique and migrate any duplicates deterministically. |
| 6 | `com/yourname/expensetracker/data/database/entity/MerchantAlias.kt:20-22,29` | High | Constraint | Alias resolution reads by `normalizedKey LIMIT 1`, but `normalizedKey` is not unique. Two aliases can therefore resolve the same normalized key to different canonicals arbitrarily. | R | CONFIRMED | Enforce uniqueness on `normalizedKey` (or redesign lookup semantics to return all matches and resolve explicitly). |
| 7 | `com/yourname/expensetracker/data/database/entity/MerchantLocation.kt:18,34` | Medium | Constraint | `areaKey` is nullable inside a composite unique index. In SQLite, multiple `(normalizedMerchantName, NULL)` rows bypass uniqueness, which breaks the intended global-cache identity and the DAO's upsert assumptions. | B | CONFIRMED | Backfill legacy `NULL` values to `'global'`, make `areaKey` non-null, and normalize nulls before insert. |
| 8 | `com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt:29` | Medium | Default Value | `isSubscription` defaults to `true`, and at least two generic recurring-expense creation paths omit the flag (`RecurringExpenseRepository.addRecurringExpense()` and `RecurringExpensesScreen.confirmPattern()`), causing non-subscription recurring expenses to be misclassified. | B | CONFIRMED | Change the entity default to `false` and set `true` only in explicit subscription flows. |
| 9 | `com/yourname/expensetracker/domain/investment/InvestmentTracker.kt:91-107` | Medium | Logic | `allTimeHigh` and `allTimeLow` are computed from only the last 30 days of history, so long-lived investments lose true all-time extrema while the API still labels them as all-time values. | D | CONFIRMED | Either query the full history for ATH/ATL or rename the fields to `thirtyDayHigh` / `thirtyDayLow`. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt:28,52` | Medium | Constraint | `fingerprint` is the primary dedupe lookup (`EmailReceiptDao.getByFingerprint(... LIMIT 1)`), but the schema only adds a non-unique index. Duplicate fingerprints can still be stored, making dedupe and duplicate detection nondeterministic. | Make `fingerprint` unique if it is the canonical dedupe key, or stop using single-row lookup semantics and handle collisions explicitly. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | Debugger #1 | `Investment.kt:35` | `currentPrice` is `NOT NULL` in schema/migrations and all current insert paths populate it. Room does not need a SQL default to read existing rows, so the reported reconstruction failure is not present. |
| 2 | Debugger #2 | `GroupExpense.kt:50 / SharedExpenseBudgetOffsetEngine.kt:130` | `calculateMyShare()` already guards `members.isEmpty()` before dividing. The specific critical budget-calculation path cited in the report is no longer real. |
| 3 | Debugger #3 | `MerchantCanonical.kt:35-36` | No current production path updates `MerchantCanonical` via `copy()`/`updateCanonical()` without explicitly controlling timestamps. The report describes a possible future misuse, not an observed bug. |
| 4 | Debugger #4 | `ExchangeRate.kt:15 / ExchangeRateDao.kt:13-14` | Nothing in the current schema references `exchange_rates.id`, so the DELETE+INSERT side effect of `REPLACE` is only a latent future-design concern. |
| 5 | Debugger #5 | `BankConnection.kt:27-28` | Actual creation and migration code encrypts tokens with `BankTokenCipher`; the report relies on a hypothetical bypass path rather than an existing plaintext-storage bug. |
| 6 | Debugger #6 | `SplitItemAssignment.kt:28` | The current codebase does not establish `receiptItemId` as a FK to `receipt_item_categorizations` (or any other Room entity). The report assumes a relationship that is not actually modeled. |
| 7 | Debugger #10 | `EmailReceiptSource.kt:41-42` | Nullable unique semantics are intentional here: uniqueness applies to non-null message IDs only, while no-message-id dedupe is handled by fingerprint. The nullable unique column itself is not a defect. |
| 8 | Debugger #12 | `GroupMember.kt:25` | Case-insensitive member-name uniqueness is a product/design choice, not a violated invariant in the current plan or implementation. |
| 9 | Debugger #13 | `BankConnection.kt:46` | `consecutiveErrors` is currently unused. There is no implemented increment/reset flow whose behavior can be verified as broken. |
| 10 | Debugger Cross #1 | `SharedExpenseBudgetOffsetEngine.kt:130` | Same root cause as Debugger #2: the reported NaN/Infinity propagation path is already guarded in the active calculation method. |
| 11 | Debugger Cross #2 | `Investment.kt:35` | Same root cause as Debugger #1: current code does not depend on a SQL default for `currentPrice` to read valid rows. |
| 12 | Debugger Cross #3 | `ExchangeRate.kt / CurrencyConverter.kt / MultiCurrencyRepository.kt` | The codebase already exposes `shouldUpdateRates()` and intentionally supports cached/manual exchange rates. Lack of a forced stale-rate block is a product choice, not a verified defect in this batch. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `GroupExpense` / `SplitItemAssignment` / `ExchangeRate` / `EmailReceiptSource` / `ManualRecurringExpense` / `Investment` / `MerchantLocation` | Medium | Validation | Financially sensitive numeric fields have no DB-level `CHECK` constraints and almost no constructor-level invariants, so invalid negative/zero/out-of-range values can be persisted and then consumed as if they were trusted. | `GroupExpense.kt`, `SplitItemAssignment.kt`, `ExchangeRate.kt`, `EmailReceiptSource.kt`, `ManualRecurringExpense.kt`, `Investment.kt`, `MerchantLocation.kt` | Add repository/constructor validation for invariants and back it with migration-time SQL `CHECK` constraints where feasible. |
| 2 | `GroupExpense` → split parsing / budget calculation | Medium | Data Contract | `customSplitsJson` is not actually JSON, the entity comment says it is, and parsing is split between the shared `CustomSplitParser` and a separate permissive parser in `SharedExpenseBudgetOffsetEngine`. Malformed payloads can therefore silently change split results instead of following one consistent validation rule. | `GroupExpense.kt`, `SharedExpenseManager.kt`, `GroupsRepositoryImpl.kt`, `SharedExpenseBudgetOffsetEngine.kt` | Centralize serialization/deserialization in one shared component and either rename the column/comment or store real JSON. |
| 3 | `EmailReceiptSource` → `ScannedReceipt` → email ingestion | Medium | Transactionality | `EmailReceiptIngestionService` inserts `ScannedReceipt` and `EmailReceiptSource` in separate DAO calls. If the second step fails or the process dies between them, receipt ingestion is left in a partially-written state. | `EmailReceiptSource.kt`, `EmailReceiptDao.kt`, `ScannedReceiptDao.kt`, `EmailReceiptIngestionService.kt` | Wrap the receipt/source creation and linkage in a single `@Transaction` DAO/repository entry point. |
| 4 | `EmailReceiptSource` → duplicate-message handling | Medium | Conflict Handling | The ingestion flow dedupes by fingerprint only, but `EmailReceiptDao.insert()` uses `REPLACE` against a unique `emailMessageId`. A replayed/resent message can therefore overwrite the existing source row and silently rebind tracking to a different receipt. | `EmailReceiptSource.kt`, `EmailReceiptDao.kt`, `EmailReceiptIngestionService.kt` | Check `emailMessageId` before insert and prefer `IGNORE`/explicit duplicate handling over `REPLACE` for identity-bearing source rows. |

## Summary
- Total verified issues: 14
- Confirmed: 14 (Critical: 0, High: 5, Medium: 9, Low: 0)
- False positives: 12
- Missed issues found: 2
- Files affected: 8/12

## Key Patterns
- Several tables use single-row lookup semantics (`LIMIT 1`) without enforcing the corresponding uniqueness at schema level.
- Important integrity rules are enforced only in selected service paths, not at the database boundary (`isCurrentUser`, same-group payer membership, category references, dedupe keys).
- Serialization/dedupe contracts are underspecified: names/comments do not match stored formats, and conflict handling is inconsistent across writer and reader paths.
- The batch relies heavily on application discipline for financially sensitive data quality, with little DB-backed protection against invalid values.
