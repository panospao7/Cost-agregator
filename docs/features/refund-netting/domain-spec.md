# REFUND-NETTING (DR-05): link a refund to its purchase and net it, domain spec

Status: DESIGN ONLY. Owner item 144 approved the behaviour ("net refunds against the purchase"). Slices start once the orchestrator records `decisions.md`.
Source read: mainline 80251ea3 (`Cost-agregator-fu1`), schema `APP_DATABASE_SCHEMA_VERSION = 152` (`data/database/AppDatabase.kt:33`). Paths below are under `app/src/main/java/com/yourname/expensetracker/`.
Inputs: `workflows/active/item144-scoping.md` section E; `docs/architecture/LEGAL_PATHS.md:180-188` (REFUNDS) and `:129-163` (SPENDING INCLUSION, the model this design copies).

## 0. Problem and design principle

Today there is no REFUND type. Refunds arrive as DEPOSIT rows (bank API, statement parser, notification prompt). Spending totals are PURCHASE-only, so a refund never lowers the purchase's category or budget spend, and the refund is counted as income. Example: a 100 EUR purchase at Zara on Nov 3 and a 40 EUR refund on Dec 2. Today November Clothing shows 100 instead of 60, and December income shows +40 instead of 0. `RefundLikelihoodDetector` shows a notice and nothing else. `spendingExclusionReason` can only zero a whole row.

**Principles.**

1. **A link is an explicit user fact.** It says: refund row D pays back purchase row P by applied amount A, in the currency both rows share. No aggregate ever guesses a link.
2. **Netting is row-local.** Each linked row carries a denormalised `refundNettedAmount`, the sum of A on its side. `Expense.effectiveAmount` (Kotlin) and `SpendingInclusionSql.EFFECTIVE_AMOUNT` (SQL) subtract it with one formula. Every consumer that already sums the effective amount therefore nets automatically, with no join and no extra read. This is the existing `spendingExclusionReason` pattern: a denormalised value, the source of truth in another table, and TLC as the only writer.
3. **Both sides net by the same A.** The purchase's gross spend falls by A, and the refund's income falls by A. Linking never changes Σ income − Σ gross spend, so there is no double count.
4. **Zero regression.** A row with `refundNettedAmount = 0.0` computes bit-for-bit the same value as today.

**Rejected alternatives.**

- **Column only** (`refundOfExpenseId` on the deposit). It fixes the model at one refund per purchase pointer forever. It needs an index on `expenses`; the `Expense.kt` note on `spendingExclusionReason` rejected exactly that write cost. It also has no audit row.
- **Link table without a denormalised column.** About 40 SQL totals in `ExpenseDao` would each need a join or a correlated subquery. Kotlin `effectiveAmount` is a property of one row and cannot see links, so every Kotlin consumer would need a second read. That makes two rules that can diverge.
- **Reusing `spendingExclusionReason`** (a new value such as `REFUND_APPLIED` on the deposit). Several places read "non-null" as "installment": `ExportInstallmentMarker.isInstallmentFlagged(reason) = reason != null`, the INV-22 installment lock (`TransactionLifecycleCoordinator.kt:1351`, `failIfInstallmentLocked`), `RecurringInstallmentEligibility` and `TransactionsInstallmentUi`. A refund would be exported and shown as an installment. A flag also cannot express a partial amount.
- **A REFUND transaction type.** It changes the parsers and about 53 files that branch on DEPOSIT. Deferred: v1 leaves parsing unchanged.
- **Automatic linking.** Not in v1 (INV-16, D-4).

## 1. Entities and value objects

### 1.1 New table `expense_refund_links` (entity `ExpenseRefundLink`, `data/database/entity/`)

| Column | Type | Null | Meaning |
|---|---|---|---|
| `id` | INTEGER PK, autoGenerate | no | |
| `refundExpenseId` | INTEGER | no | FK `expenses(id)` ON DELETE CASCADE. The DEPOSIT row |
| `purchaseExpenseId` | INTEGER | no | FK `expenses(id)` ON DELETE CASCADE. The PURCHASE row |
| `appliedAmount` | REAL | no | Major units of `currency`. At least one minor unit, already at the currency's scale (1.3) |
| `currency` | TEXT | no | ISO-4217 code, normalised by `DuplicateDetectionPolicy.normalizeCurrency`. Equal to both rows' currency when the link is made |
| `createdAt` | INTEGER | no | `timeProvider.now()`, epoch ms |
| `source` | TEXT | no | Bounded `RefundLinkSource` name. v1 has one value, `USER_CONFIRMED` |

Indices:

- UNIQUE `(refundExpenseId, purchaseExpenseId)`. It also covers the `refundExpenseId` FK.
- `(purchaseExpenseId)`.

The schema allows many-to-many links. v1 limits a refund to one purchase in the coordinator (INV-4), so lifting that limit later needs no schema bump.

CASCADE is only a safety net for `ExpenseDao` `DELETE FROM expenses` (the full wipe at `ExpenseDao.kt:340`), where both sides are deleted together. The legal single-row delete removes links explicitly first (INV-13).

### 1.2 New column `expenses.refundNettedAmount`

- Definition: `REAL NOT NULL DEFAULT 0.0`, written as `@ColumnInfo(defaultValue = "0.0")` to match the `baseAmount` precedent. Not indexed.
- Meaning: Σ `appliedAmount` of the links on this row's side, in this row's currency.
  - On a PURCHASE it is the refunds received.
  - On a DEPOSIT it is the part applied to purchases.
  - 0.0 means the row has no link.
- It is denormalised. The link table is the source of truth (INV-1).

### 1.3 Money

- **Scale.** `d = CurrencyFractionDigits.of(CurrencyCode(currency))`. If `d` is null, the link is refused with `CURRENCY_DIGITS_UNAVAILABLE`.
- **Rounding.** `s(x) = BigDecimal.valueOf(x).setScale(d, HALF_EVEN)`, the same mode as `roundSnapshot`.
- **Capacity.**
  - `capP = s(P.amount) − Σ BigDecimal.valueOf(l.appliedAmount)` over the links of P.
  - `capD` is computed the same way for D.
- **Applied amount.** `A = min(capP, capD)`. It must be at least one minor unit (`10^-d`); otherwise the result is `NOTHING_TO_APPLY`. v1 has no amount chosen by the user (D-6).
- **Stored values.**
  - `appliedAmount = A.toDouble()`.
  - `refundNettedAmount = (Σ BigDecimal.valueOf(appliedAmount_i)).toDouble()`, recomputed from the link rows inside the transaction. It is never incremented as a Double. So 33.33 + 33.33 + 33.34 gives exactly 100.0.
- **Currency equality.** `normalizeCurrency(P.currency) == normalizeCurrency(D.currency)`, the detector's rule. There is no FX anywhere in linking.

### 1.4 Netting formula

**Kotlin owner: `Expense.effectiveAmount`.** The first matching branch wins:

```
spendingExclusionReason != null              -> 0.0
!(refundNettedAmount > 0.0)                  -> <today's expression, unchanged: notMine 0 / myShareAmount / pct / amount>
isNotMine                                    -> 0.0
refundNettedAmount >= amount                 -> 0.0
isSharedExpense && myShareAmount != null     -> myShareAmount * (amount - refundNettedAmount) / amount
isSharedExpense && mySharePercentage != null -> (amount - refundNettedAmount) * mySharePercentage / 100.0
else                                         -> amount - refundNettedAmount
```

**SQL mirror: `SpendingInclusionSql.EFFECTIVE_AMOUNT` and `EFFECTIVE_AMOUNT_E`.** Same branches, same order and same arithmetic order. Built with `+`, like today:

```
CASE WHEN spendingExclusionReason IS NOT NULL THEN 0.0
     WHEN refundNettedAmount <= 0.0 THEN (<ExpenseDao.EFFECTIVE_AMOUNT_SQL>)
     WHEN isNotMine = 1 THEN 0.0
     WHEN refundNettedAmount >= amount THEN 0.0
     WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount * (amount - refundNettedAmount) / amount
     WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN (amount - refundNettedAmount) * mySharePercentage / 100.0
     ELSE amount - refundNettedAmount END
```

The rule: a refund reduces the gross amount, and the user's share is taken from the net gross. A percentage share stays the same percentage. A fixed share is scaled by net/gross. The result is never negative, and there is no division unless `amount > refundNettedAmount > 0`.

**Home-currency value: `ComparableHomeValue.snapshotValue`.**

- When `refundNettedAmount > 0` and the row has a usable snapshot, the value is `0.0` if `refundNettedAmount >= amount`, and otherwise `normalizeZero(snapshotAmount(e) * (amount - refundNettedAmount) / amount)`.
- The neutralised, not-mine and no-snapshot branches are unchanged, and `Expense.normalizedEffectiveAmount` keeps delegating to it.
- The applied amount is therefore valued at the purchase's own snapshot rate (the purchase date), never at the refund date's rate. Consumers that convert `effectiveAmount` (`MoneyNormalizationEngine`, `AnalyticsCurrencyNormalizer`, `MultiCurrencyRepository`) convert the purchase row on its own rate basis too.

**Unchanged (gross, D-8):**

- `ComparableHomeValue.comparableValue`, `listAmount`, `comparator` and `withinBounds`;
- the inner `ExpenseDao.EFFECTIVE_AMOUNT_SQL` and `EFFECTIVE_AMOUNT_E_SQL`;
- `ExpenseDao.COMPARABLE_HOME_VALUE_E_SQL`;
- `ExpenseRepository.SortOrder.AMOUNT_*` and the assistant `RawEffective` filters (`ExpenseRepository.kt:452-458`).

**Also unchanged (D-9):** `SPENDING_PREDICATE` and `countsAsSpending`.

### 1.5 New value objects (in memory only)

- `RefundLinkSource { USER_CONFIRMED }`.
- `RefundLinkResult` is one of:
  - `Linked(linkId, applied: MoneyAmount)`
  - `AlreadyLinked(linkId)`
  - `Refused(code: RefundLinkRefusal)`
  - `Failed(code)`, with code `WRITE_BLOCKED` or `INTERNAL`.
- `RefundLinkRefusal` codes:
  - `REFUND_NOT_FOUND`, `PURCHASE_NOT_FOUND`, `SAME_ROW`
  - `NOT_A_DEPOSIT`, `NOT_A_PURCHASE`
  - `CURRENCY_MISMATCH`, `CURRENCY_DIGITS_UNAVAILABLE`
  - `INVALID_AMOUNT`, `NOTHING_TO_APPLY`
  - `REFUND_BEFORE_PURCHASE`, `REFUND_LINKED_ELSEWHERE`
  - `INSTALLMENT_MANAGED`, `GROUP_LINKED`
- `RefundUnlinkResult` = `Unlinked(linkId)`, `NotLinked`, or `Failed(code)`.
- `RefundCandidate` has these fields: `purchaseId`, `purchaseDate`, `amount: MoneyAmount`, `remaining: MoneyAmount`, `exactAmountMatch`, `merchantMatch`, `dayDelta: Int`. Display text (merchant, category) is read live by the UI and never copied in.
- `RefundCandidates` = `Available(items)` or `Unavailable(READ_BLOCKED | READ_FAILED)`.
- `TransactionUpdateKind.REFUND_NETTING`. Both `affectsRecurringMatch()` and `involvesCategoryChange()` return false for it.
- `RefundLinkedExpenseException`, with code `EXPENSE_LOCKED_BY_REFUND_LINK`.

### 1.6 Definitions

- **Linked row:** `refundNettedAmount > 0.0`. By INV-1 and INV-5 this is the same as having at least one link.
- **Eligible refund D:** all of these hold:
  - `transactionType == DEPOSIT`;
  - `amount` is finite and > 0;
  - `spendingExclusionReason == null`;
  - D is not group-linked (`groupExpenseDao().existsForExpense`);
  - D has no link to any purchase (v1, INV-4);
  - `capD` is at least one minor unit.
- **Eligible purchase P:** all of these hold:
  - `transactionType == PURCHASE` (WITHDRAWAL, TRANSFER and UNKNOWN are refused);
  - `amount` is finite and > 0;
  - `spendingExclusionReason == null`;
  - P is not group-linked;
  - `capP` is at least one minor unit.
- **Eligible pair (D, P):** all of these hold:
  - both rows are eligible;
  - `D.id != P.id`;
  - the currencies are equal (1.3);
  - `TimePeriodUtils.daysBetween(P.date, D.date) >= -1`. The refund may be at most one calendar day before the purchase, in the device zone, to allow for posting lag.

  `isNotMine` and the share fields never affect eligibility, because the formula handles them.
- **Candidate scope for D:** PURCHASE rows read with the read-only `expenseDao.getRefundCandidatePurchasesBetween(D.date − RefundLikelihoodDetector.WINDOW_MS, D.date + 1 day)`, filtered to eligible pairs. This query has no `isNotMine` filter (the shared `getExpensesBetween` family filters `isNotMine = 0`, which would hide not-mine purchases that 1.6 and D-11 make eligible); it also skips rows with `spendingExclusionReason` set. Rank, from first to last key:
  1. `exactAmountMatch` first (`DuplicateDetectionPolicy.areAmountsEqual(D.amount, P.amount)`);
  2. then `merchantMatch` first (the detector's canonical merchant key is equal);
  3. then `|dayDelta|` ascending;
  4. then `P.id` descending.

  At most 50 candidates are returned.

## 2. Invariants

Owners:

- **TLC** is `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`, the only writer.
- **The policy** is a new pure object, `domain/transaction/RefundNettingPolicy.kt`, with no DAO and no clock. It owns eligibility, capacity, the applied amount and the ranking. For the amount-equality and merchant-key rules it calls `RefundLikelihoodDetector`'s helpers, which become internal, so those rules are not duplicated.
- **The use case** is a new `domain/transaction/RefundLinkUseCase.kt`. It is the only caller of the TLC link and unlink methods, and it owns the candidate reads.

The invariants:

- **INV-1 Consistency.** For every expense X, `X.refundNettedAmount` equals the BigDecimal sum, at scale, of `appliedAmount` over the links where X is a side. It is `0.0` when X has no link. Owner: TLC. Checked by a TLC test and by the `BackupVerifier` consistency query (INV-20).
- **INV-2 Single writer.**
  - `expense_refund_links` and `expenses.refundNettedAmount` are written only by `TLC.linkRefund`, `TLC.unlinkRefund` and the in-transaction cleanup in `TLC.deleteExpense`, through new `@RestrictedExpenseDaoMutation` methods: `ExpenseDao.updateRefundNettedAmount(id, value)`, `ExpenseRefundLinkDao.insert` and `ExpenseRefundLinkDao.deleteById`.
  - `createExpense`, `createExpenseDbOnly` and `createExpenseDbOnlyV2` always store `0.0`, whatever the request carries (for example an undo copy).
  - `updateExpense` and every other full-row writer keep the value read inside their transaction, as `spendingExclusionReason` is kept at `TransactionLifecycleCoordinator.kt:1469`. The other full-row writers are `updateType`, `updateTypeAndTransferDetails`, `updateOwnershipDbOnlyV2` and any caller of `ExpenseDao.update`.

  Owner: TLC.
- **INV-3 One eligibility rule.** A link is created only for an eligible pair (1.6). TLC evaluates the policy inside the write transaction on freshly read rows and link sums. The use case uses the same policy functions to build candidates. Owner: the policy.
- **INV-4 Cardinality.** A refund links to at most one purchase in v1. A purchase may have any number of refunds. Each pair is unique (UNIQUE index).
  - Linking a pair that is already linked returns `AlreadyLinked`, with no write and no event.
  - Linking a refund that is linked to another purchase returns `Refused(REFUND_LINKED_ELSEWHERE)`.

  Owner: TLC.
- **INV-5 Capacity, never negative.**
  - `A = min(capP, capD)`, and A is at least one minor unit.
  - After every write, Σ applied(P) ≤ `s(P.amount)` and Σ applied(D) ≤ `s(D.amount)`.
  - By 1.4, `effectiveAmount` is at least 0 for every row.

  Owner: the policy computes A; TLC enforces it inside the transaction.
- **INV-6 Netting formula.** `Expense.effectiveAmount` follows 1.4.
  - `SpendingInclusionSql.EFFECTIVE_AMOUNT` and `EFFECTIVE_AMOUNT_E` mirror it.
  - `SpendingInclusionParityTest` asserts exact `==` over the AE-19 grid.
  - Rows with `refundNettedAmount = 0.0` equal the pre-feature value bit-for-bit.

  Owner: `Expense.effectiveAmount`, with `SpendingInclusionSql` as the SQL projection.
- **INV-7 Home value.** `ComparableHomeValue.snapshotValue` nets by the factor in 1.4, using the row's own snapshot. A refund is never valued at the refund date's rate on the purchase side. Owner: `ComparableHomeValue.snapshotValue`.
- **INV-8 No income double count.** The applied part of a linked DEPOSIT adds 0 to every income total, because the deposit side uses the same formula. For any set of rows that contains both sides of a link, Σ gross income − Σ gross spend is unchanged by the link or the unlink. Owner: `Expense.effectiveAmount` and `SpendingInclusionSql`; the totals are made to use them by INV-9.
- **INV-9 Totals use the outer constant.**
  - No SQL total sums `ExpenseDao.EFFECTIVE_AMOUNT_SQL` or `EFFECTIVE_AMOUNT_E_SQL` directly. All `SUM(${EFFECTIVE_AMOUNT_SQL})` and `SUM(${EFFECTIVE_AMOUNT_E_SQL})` sites in `ExpenseDao` change to `SpendingInclusionSql.EFFECTIVE_AMOUNT(_E)`. At 80251ea3 that is 40 sites, lines 321 to 2511, including the deposit totals at 2150, 2156 and 2170.
  - The inner constants stay for list, sort and filter, for `SpendingInclusionSql` itself, and for the `INCOME_EFFECTIVE_BY_CURRENCY` fingerprint (D-14).
  - A static test fails if `SUM(` wraps an inner constant in any `@Query` or known RawQuery builder.

  Owner: `ExpenseDao`. The change is behaviour-neutral before links exist, because the WHERE clauses already exclude flagged rows.
- **INV-10 Period and category.** A refund nets in the purchase's date, category, merchant and ownership, because the netting is row-local. The refund row's date and category never move spend.
  - Linking in December changes November totals and November budget results.
  - Already-sent budget alerts are not withdrawn, and accepted savings-sweep months are not re-run (D-3).

  Owner: follows from INV-6. Stated here so that it is tested.
- **INV-11 List stays gross.** Comparable value, list amount, sort, min/max filter and the assistant `RawEffective` paths are unchanged. The duplicate BLOCKING and RESOLVER families keep raw `amount`, so a netted row stays a duplicate target. The day header uses `normalizedEffectiveAmount` and therefore nets, following the decision 131 precedent. Owner: `ComparableHomeValue` (unchanged).
- **INV-12 Edit lock.** On a linked row (either side), changes to `amount`, `currency` or `transactionType` are refused with `RefundLinkedExpenseException(EXPENSE_LOCKED_BY_REFUND_LINK)` before any write or event. Merchant, date, category, notes and ownership stay editable. Owner: TLC, which gets a `failIfRefundLinked(existing)` probe beside `failIfInstallmentLocked`.
- **INV-13 Delete.** `TLC.deleteExpense(X)` works inside its existing transaction, after the group check and before the DELETED event:
  - it reads the links of X;
  - it deletes each link;
  - it recomputes each counterpart's `refundNettedAmount` from its remaining links;
  - it writes one UPDATED event per counterpart (REFUND_NETTING, reason `REFUND_COUNTERPART_DELETED`).

  Any failure throws and rolls the delete back. The counterparts' `planUpdated` batches are merged into the delete's post-commit work. Owner: TLC.
- **INV-14 Unlink.** `TLC.unlinkRefund(refundExpenseId)` deletes the refund's link, recomputes both sides, and writes two UPDATED events (REFUND_NETTING, reason `REFUND_UNLINKED`). With no link, the result is `NotLinked`, with no write. Owner: TLC.
- **INV-15 Atomic link.** `TLC.linkRefund(refundExpenseId, purchaseExpenseId)` runs as `writeBarrier.runWrite { database.withTransaction { … } }`, with these steps:
  1. re-read both rows and both link lists;
  2. evaluate the policy (INV-3 and INV-5);
  3. insert the link;
  4. update both columns;
  5. write two UPDATED events (REFUND_NETTING, reason `REFUND_LINKED`).

  After the commit, it dispatches `planUpdated(…, REFUND_NETTING)` for both rows. Concurrent links are serialized by SQLite's single writer, and the later one sees the reduced capacity. No mutex is needed, because there is no post-commit write. Owner: TLC.
- **INV-16 No automatic link.** A link exists only after one explicit user action that names `(refundExpenseId, purchaseExpenseId)`. No worker, parser, intake path or post-commit action calls `linkRefund`. Owner: `RefundLinkUseCase`, the sole caller, proved by an architecture test.
- **INV-17 Suggestions are read-only and fail closed.**
  - `RefundLinkUseCase.findCandidates(refundId)` uses `DatabaseReadBarrier.checkReadAllowed`, one range read and no writes.
  - A blocked read gives `Unavailable(READ_BLOCKED)`; any other error gives `Unavailable(READ_FAILED)`. An error never produces `Available([])`.
  - `CancellationException` is rethrown.

  Owner: the use case.
- **INV-18 Notice.** `RefundLikelihoodDetector.isLikelyRefund` returns false for a deposit with `refundNettedAmount > 0`, and it skips candidate purchases with `refundNettedAmount >= amount`. Otherwise its semantics are unchanged. Owner: `RefundLikelihoodDetector`.
- **INV-19 Installment and group exclusions.**
  - Rows with `spendingExclusionReason` set are refused (`INSTALLMENT_MANAGED`). The installment plan's REFUNDED flow owns refunds of installment purchases. A row that is an installment plan purchase record (`getPlanByPurchaseExpenseId`) or the linked expense of a plan entry (`getEntryByLinkedExpenseId`) is refused the same way; TLC reads both facts in the link transaction and passes them to `decideLink`.
  - Installments and refund links are mutually exclusive in the other direction too (D-19): `setSpendingExclusionDbOnly` refuses to set any exclusion flag on a row with `refundNettedAmount > 0` (`SpendingInclusionResult.RefundLinked`, nothing written, no event). Installment declare (`declareOnExpense`, `declareWithNewExpense`) and the REFUNDED plan path then return `Failed(REFUND_LINKED)` and roll back; reconcile repairs skip the flag. Clearing a flag is never refused. Owner: `TransactionLifecycleCoordinator` (single choke point), with `InstallmentPlanLifecycleCoordinator.setPurchaseFlag` mapping the result.
  - Group-linked rows are refused (`GROUP_LINKED`).
  - The group link writer (`GroupTransactionCoordinator`, reached through `SharedExpenseDataPortAdapter`) refuses to link an expense with `refundNettedAmount > 0`, using a bounded code `REFUND_LINKED`.

  Owner: the policy, plus `GroupTransactionCoordinator`.
- **INV-20 Backup and restore.** In `BackupVerifier`:
  - `expense_refund_links` is `TIER_1_EXACT` and required from `REFUND_LINKS_SCHEMA_VERSION = 153`.
  - `expectedCountsAfterMigration` seeds 0 for sources below 153.
  - An orphan query counts links whose either side is missing.
  - A consistency query counts expenses where `|refundNettedAmount − Σ appliedAmount of links on either side| > 1e-6`.
  - Either count above 0 fails verification (fail-closed).

  Semantic aggregates follow D-14. Owner: `BackupVerifier` and `SemanticAggregates`.
- **INV-21 Export and import.**
  - `ExportTransaction` gains `refundNettedAmount`, mapped by `ExpenseExportMapper`. The generic CSV and JSON exports write it as a trailing column.
  - `effectiveAmount` is exported net. The accounting exporters skip rows that bear no amount (DR-08 `bearsNoAmount`).
  - Link identity is not exported.
  - `CsvExpenseImporter` and `JsonExpenseImporter` keep reading `amount` first (RP-19), ignore the new column, and never create a link.

  Owner: `ExpenseExportMapper`.
- **INV-22 Privacy.** Events, logs and diagnostics carry only ids, counts and bounded codes (section 6). Owner: TLC and the use case.
- **INV-23 Restore barrier.** While writes are blocked, link and unlink return `Failed(WRITE_BLOCKED)` and write nothing. Owner: TLC.
- **INV-24 Migration.** `MIGRATION_152_153` is additive only (section 5). Every pre-existing row gets `refundNettedAmount = 0.0`, and every aggregate is unchanged. Owner: `DatabaseMigrations`.

## 3. State machines

### 3.1 Link (D, P)

| From | To | Trigger | Writes |
|---|---|---|---|
| NONE | LINKED | `linkRefund`, with the pair eligible | 1 link, 2 columns, 2 events |
| LINKED | NONE | `unlinkRefund(D)` | link deleted, 2 columns, 2 events |
| LINKED | NONE | `deleteExpense(D or P)` | link deleted, counterpart column, 1 counterpart event, DELETED event |
| LINKED | LINKED | `linkRefund` on the same pair | none (`AlreadyLinked`) |

Illegal transitions:

- A link for an ineligible pair returns `Refused(code)`.
- A second purchase for a linked D returns `REFUND_LINKED_ELSEWHERE`.
- An edit of amount, currency or type on a linked row throws `RefundLinkedExpenseException`.
- No path edits `appliedAmount` in place.

### 3.2 Derived row states (never persisted as a status)

| Row | State | Condition |
|---|---|---|
| Purchase | GROSS / PARTLY_REFUNDED / FULLY_REFUNDED | `refundNettedAmount` is 0 / between 0 and amount / at least amount |
| Deposit | UNLINKED / APPLIED (with remainder) / APPLIED_IN_FULL | `refundNettedAmount` is 0 / less than amount / at least amount |

### 3.3 Retry, cancellation and process death

- **Link and unlink.**
  - Each is one transaction. Process death before the commit leaves nothing written, and after the commit leaves everything written.
  - A retry after the commit returns `AlreadyLinked` or `NotLinked`, with no second event.
  - Cancellation before the commit rolls back.
  - The post-commit batch runs under the existing TLC MutationResult contract. If it is lost, only refreshes are skipped: every total reads the row columns live.
- **Delete.** As today, plus the INV-13 cleanup inside the same transaction.
- **Candidates.** There is no state, so a retry recomputes.

## 4. Edge cases

In this table, home is EUR and the device zone is Europe/Athens unless stated otherwise.

| Case | Behaviour |
|---|---|
| Partial refund | A = refund amount. The purchase is netted to the rest (AE-1). |
| Multiple refunds | Each one adds a link. Σ is capped at the purchase amount, and a later refund gets `NOTHING_TO_APPLY` or the remainder (AE-2, AE-3, AE-18). |
| Refund larger than the purchase | A = capP. The deposit's excess stays income (AE-4). |
| One refund for two purchases | Not in v1 (D-5). The user links it to one purchase, and the excess stays income. |
| Mixed currencies | Refused, `CURRENCY_MISMATCH` (AE-6, D-7). |
| Same foreign currency | Native netting is exact. The home value uses the purchase's snapshot rate. The FX difference between the purchase-date and refund-date rates disappears from the home totals (AE-10, L-3). |
| Missing or stale FX | Not used by linking. A netted row with no snapshot keeps today's unavailable handling (`snapshotValue` is null). |
| Unavailable vs zero | A fully refunded purchase is a real 0. A failed candidate read is `Unavailable`, never an empty list. |
| Rounding conservation | Capacity and sums are BigDecimal at the currency's scale. Totals are double sums, as today (AE-20). |
| Shared, percentage share | Share of the net gross (AE-7). |
| Shared, fixed share | Scaled by net/gross (AE-8). |
| Not-mine purchase | Allowed. The purchase stays at 0, and the deposit leaves income (AE-9). |
| Installment purchase, or an installment expense (flagged, plan purchase record, or plan entry's linked expense) | Refused, `INSTALLMENT_MANAGED` (AE-11). |
| Declaring an installment plan on, or REFUNDED-flagging, a row with `refundNettedAmount > 0` | Refused by TLC, `Failed(REFUND_LINKED)`, rolled back (D-19). Unlink first. |
| Group-linked row | Refused. The group writer also refuses a linked row (AE-11). |
| Recurring-generated or recurring-linked purchase | Allowed. Occurrence status and `paidAmount` stay gross. Recurring matching uses raw `amount`. |
| Receipt-linked purchase | Allowed. Receipt, warranty and return-window rows are unchanged. |
| Refund dated before the purchase | Refused when more than 1 calendar day before (AE-12). |
| Time zone change after entry | The day check can shift by 1 (L-6). |
| DST | `daysBetween` uses calendar days. The 90-day candidate window is in ms and can move by 1 h; it only bounds suggestions. |
| Edits of past items | Category or date edits move the net with the purchase. Amount, currency and type are locked (AE-16). |
| Delete either side | The counterpart is restored in the same transaction (AE-14, AE-15). |
| Undo of a delete, or re-create | Treated as a new row with `refundNettedAmount = 0.0`. The link is not restored (AE-17, L-4). |
| Duplicate refund row | The second deposit gets `NOTHING_TO_APPLY` on a fully refunded purchase. Deleting it unlinks it. |
| Stale UI | The in-transaction re-read gives `Refused(...)` or `AlreadyLinked`. |
| Restore running | `Unavailable(READ_BLOCKED)` and `Failed(WRITE_BLOCKED)` (AE-22). |
| Empty states | No deposits: no notice. No candidates: `Available([])` (AE-28). |
| Fully refunded purchase in counts | Still counted, at 0, in COUNT and AVG (D-9). |
| Cash-flow day series | The refund's cash effect moves to the purchase day. The running total over the range is unchanged (D-16). |
| Historical refunds (existing DEPOSIT rows) | No backfill. Any deposit can be linked from its row (D-18). |

## 5. Persistence impact

**Schema 152 → 153, additive.** `APP_DATABASE_SCHEMA_VERSION = 153`. `MIGRATION_152_153` in `data/database/DatabaseMigrations.kt`:

```
ALTER TABLE expenses ADD COLUMN refundNettedAmount REAL NOT NULL DEFAULT 0.0;
CREATE TABLE IF NOT EXISTS expense_refund_links (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  refundExpenseId INTEGER NOT NULL, purchaseExpenseId INTEGER NOT NULL, appliedAmount REAL NOT NULL,
  currency TEXT NOT NULL, createdAt INTEGER NOT NULL, source TEXT NOT NULL,
  FOREIGN KEY(refundExpenseId) REFERENCES expenses(id) ON UPDATE NO ACTION ON DELETE CASCADE,
  FOREIGN KEY(purchaseExpenseId) REFERENCES expenses(id) ON UPDATE NO ACTION ON DELETE CASCADE);
CREATE UNIQUE INDEX IF NOT EXISTS index_expense_refund_links_refundExpenseId_purchaseExpenseId ON expense_refund_links (refundExpenseId, purchaseExpenseId);
CREATE INDEX IF NOT EXISTS index_expense_refund_links_purchaseExpenseId ON expense_refund_links (purchaseExpenseId);
```

The exact text must match the exported `schemas/.../153.json` that Room generates. The test is authoritative, not this sketch.

Required with the migration:

- the 153 snapshot;
- a `152 → 153` migration test covering row preservation, the column default, the FKs, both indices, and aggregates bit-identical before and after;
- the full-chain migration test.

**Not needed:** no backfill and no destructive step. SQLite allows `ADD COLUMN … NOT NULL DEFAULT`.

**DAO.**

- New `ExpenseRefundLinkDao`:
  - restricted writes: `insert`, `deleteById`;
  - reads: `getById`, `getByRefund(refundId)`, `getByPurchase(purchaseId)`, `getForExpense(id)` (either side), and the verifier's count queries.
- `ExpenseDao.updateRefundNettedAmount(id, value)`, restricted and TLC-only.
- No SQL SUM over `appliedAmount` in the write path: TLC sums the rows in BigDecimal.
- Candidates reuse `expenseDao.getExpensesBetween`.
- New rows are needed in the `db_access` classification and in `DbGuardPolicyFixtureTest` for the new DAO and methods (Q-2).

**Backup.** `BackupVerifier` gets the changes listed in INV-20. The backup file already carries every table, and the new table is added to the exact tier.

**Semantic aggregates (D-14).**

- `SPENDING_EFFECTIVE_BY_CURRENCY` uses `SpendingInclusionSql.EFFECTIVE_AMOUNT`, so its definition hash changes with the constant's text.
- `INCOME_EFFECTIVE_BY_CURRENCY` stays on the inner constant; its hash is unchanged.

## 6. Privacy rules

- **Stored:** ids, the applied amount (the user's own money data, stored locally like `amount`), a currency code, a timestamp, and a bounded source. No merchant, note or free text.
- **Event snapshots:** `{"id": X, "refundLinkCount": n}` before and after. Metadata: `{"updateKind": "REFUND_NETTING", "linkId": L, "counterpartExpenseId": Y}`. Reason: one of the bounded codes in INV-13, INV-14 and INV-15. No amount, merchant or notes, following the SPENDING_INCLUSION precedent.
- **Logged:** Timber and diagnostics use bounded codes and counts only: `REFUND_LINKED`, `REFUND_UNLINKED`, `REFUND_LINK_REFUSED(<RefundLinkRefusal>)`, `REFUND_LINK_FAILED(WRITE_BLOCKED|INTERNAL)`, `REFUND_COUNTERPART_RECOMPUTED(count)` and `REFUND_CANDIDATES_UNAVAILABLE(READ_BLOCKED|READ_FAILED)`. Never a merchant, amount, path, SQL message or stack trace.
- **Exported:** one numeric column (INV-21). Link identity is not exported.
- **Permissions and cloud:** no new permission and no cloud use.

## 7. Acceptance examples

The fixture applies everywhere unless stated otherwise:

- Home is EUR, the zone is Europe/Athens, and today is Dec 10, 2026.
- P1 is a 100.00 EUR PURCHASE at "Zara", Nov 3, category Clothing, not shared.
- Clothing has a November budget of 150.00. Other November Clothing spend is 60.00, so November Clothing is 160.00.
- D1 is a 40.00 EUR DEPOSIT at "ZARA", Dec 2.
- S is a 2,000.00 EUR salary DEPOSIT, Dec 1.

- **AE-1 (INV-3, 5, 6, 8, 10, 15).**
  - When D1 is linked to P1, the result is `Linked(applied 40.00 EUR)`.
  - Stored state: one link row (40.0, "EUR", USER_CONFIRMED); `P1.refundNettedAmount = 40.0`; `D1.refundNettedAmount = 40.0`.
  - Effective amounts: P1 60.0, D1 0.0.
  - Totals: November Clothing is 120.00, moving from 10 over budget to 30 under. December spend is unchanged. December income is 2,000.00 (it was 2,040.00).
  - Net over Nov–Dec: it was 2,040 − 160 = 1,880 and it stays 2,000 − 120 = 1,880.
  - Exactly two UPDATED events are written, with REFUND_NETTING.
- **AE-2 (INV-4, 5).**
  - Given AE-1, D2 is a 60.00 refund on Dec 8. Linking D2 to P1 gives `Linked(60.00)`.
  - P1's `refundNettedAmount` becomes 100.0 and its effective amount 0.0. D2's effective amount is 0.0.
  - P1 still counts in the November Clothing purchase count.
  - The QuickBooks, Xero and FreshBooks exports omit P1.
- **AE-3 (INV-5).** Given AE-2, D3 (10.00) linked to P1 gives `Refused(NOTHING_TO_APPLY)`, and nothing is written.
- **AE-4 (INV-5, 8).** P2 is 30.00 and D4 is 35.00 at the same merchant. Linking gives `Linked(30.00)`. P2's effective amount is 0.0. D4's `refundNettedAmount` is 30.0 and its effective amount 5.0, so 5.00 stays income.
- **AE-5 (INV-4).** Given AE-1:
  - D1 linked to P2 gives `Refused(REFUND_LINKED_ELSEWHERE)`.
  - D1 linked to P1 again gives `AlreadyLinked`, with no event.
- **AE-6 (INV-3).** P3 is 50.00 USD and D5 is 46.00 EUR. Linking gives `Refused(CURRENCY_MISMATCH)`, and nothing is written.
- **AE-7 (INV-6).** P4 is 100.00, shared at 50%, effective 50.0. D6 is 40.00. After the link, P4's effective amount is (100 − 40) × 50 / 100 = 30.0, and D6's is 0.0.
- **AE-8 (INV-6).** P5 is 100.00 with `myShareAmount` 30.00. D7 is 40.00. After the link, P5's effective amount is 30 × 60 / 100 = 18.0.
- **AE-9 (INV-6, 8).** P6 is 80.00 and `isNotMine`. D8 is 80.00. Linking gives `Linked(80.00)`. P6's effective amount stays 0.0. D8's effective amount is 0.0, so income falls by 80.00.
- **AE-10 (INV-7).**
  - P7 is 100.00 USD, Nov 5, with `baseAmount` 92.00 EUR at rate 0.92. D9 is 40.00 USD, Dec 1, with `baseAmount` 36.00 at rate 0.90.
  - After the link, P7's effective amount is 60.0 USD and its `normalizedEffectiveAmount` is 92 × 60 / 100 = 55.2. D9's normalized amount is 0.0.
  - The home net was −56.00 and becomes −55.20.
- **AE-11 (INV-19).**
  - P8 has `spendingExclusionReason = INSTALLMENT_PURCHASE`: linking gives `INSTALLMENT_MANAGED`.
  - P9 is group-linked: linking gives `GROUP_LINKED`.
  - Given AE-1, adding P1 to a group split is refused with `REFUND_LINKED`.
- **AE-12 (INV-3).**
  - D10 is dated Nov 1 against P1 (Nov 3): `REFUND_BEFORE_PURCHASE`.
  - D11 is dated Nov 2 at 23:30 local: `daysBetween` is −1, so the link is allowed.
- **AE-13 (INV-14).** Given AE-1, `unlinkRefund(D1)` gives `Unlinked`.
  - P1's effective amount is 100.0 and D1's is 40.0.
  - November Clothing is 160.00 and December income 2,040.00.
  - Two events are written.
  - A second unlink gives `NotLinked`, with no write.
- **AE-14 (INV-13).** Given AE-2, deleting P1 has this outcome:
  - Both links are removed inside the delete transaction.
  - D1 and D2 have `refundNettedAmount` 0.0, and December income is 2,100.00.
  - Two UPDATED events (`REFUND_COUNTERPART_DELETED`) and one DELETED event are written.
  - If a counterpart write is forced to fail, the delete rolls back and all three rows are unchanged.
- **AE-15 (INV-13).** Given AE-1, deleting D1 gives P1 `refundNettedAmount` 0.0, effective 100.0.
- **AE-16 (INV-12, 2, 10).** Given AE-1:
  - Editing P1's amount to 120 throws `RefundLinkedExpenseException`, and nothing is written.
  - Changing D1's type to TRANSFER throws the same exception.
  - Changing P1's category to Gifts is allowed: November Gifts gains 60, and Clothing returns to 60.00 (other spend only).
  - Changing P1's date to Oct 30 moves the 60 to October.
  - A stale editor copy with `refundNettedAmount = 0.0` passed to `updateExpense` leaves the column at 40.0.
- **AE-17 (INV-2).** A `createExpense` request whose row carries `refundNettedAmount = 25.0` stores 0.0.
- **AE-18 (INV-15, 5).**
  - P10 is 100.00. D12 (70.00) and D13 (50.00) are confirmed at the same time.
  - In either order, the result is A = 70 then 30, or 50 then 50, and Σ = 100.0.
  - No committed state ever has Σ above 100.
- **AE-19 (INV-6, 9).** Parity and zero regression.
  - The grid crosses three dimensions:
    - ownership: unshared, 0%, 50%, 100%, fixed share, not-mine, flagged;
    - `refundNettedAmount`: 0.0, 0.01, 40.0, amount, amount + 1;
    - type: PURCHASE, DEPOSIT.
  - For every case, the SQL `EFFECTIVE_AMOUNT` equals the Kotlin `effectiveAmount` exactly.
  - Every case with 0.0 equals the pre-feature formula.
  - The static test finds no `SUM(` over an inner constant.
- **AE-20 (INV-5, rounding).**
  - JPY: 1000 and 333 give an effective amount of 667.0.
  - BHD (3 digits): 10.125 and 2.005 give 8.12.
  - EUR 10.005 (an imported value) with a 20.00 refund: `capP` = 10.00, A = 10.00, and the purchase's effective amount is about 0.005 (never below 0). The deposit's effective amount is 10.0.
  - On a 100.00 purchase, refunds of 33.33, 33.33 and 33.34 give `refundNettedAmount` exactly 100.0 and an effective amount of exactly 0.0.
  - Tolerances: the stored `appliedAmount` and `refundNettedAmount` values are asserted exactly. The non-integer effective amounts (BHD and EUR 10.005) are asserted within 1e-9, because the subtraction is in Double. That is the same arithmetic as the SQL mirror, so the INV-6 parity assertion stays exact.
- **AE-21 (INV-17, 18).**
  - Extra fixture rows: P11 is Zara 40.00 on Nov 20, P12 is "H&M" 40.00 on Nov 25, and P13 is Zara 100 on Aug 1.
  - Candidates for D1: `Available([P11, P12, P1])` in that order. P13 is outside the window.
  - After AE-1, the notice on D1 is gone, and P1 shows 60.00 remaining.
- **AE-22 (INV-17, 23).** While a restore is running, `findCandidates` returns `Unavailable(READ_BLOCKED)`, and link and unlink return `Failed(WRITE_BLOCKED)`. Nothing is written.
- **AE-23 (INV-24).** A 152 database holds a flagged installment row, a shared row and a deposit. After the 153 migration:
  - every row has `refundNettedAmount` 0.0;
  - the table exists and is empty, with both indices and the FKs;
  - the spending and income aggregate buckets are identical before and after.
- **AE-24 (INV-20).** Backup and restore.
  - A backup taken after AE-1 has a manifest count of `expense_refund_links = 1`. Restoring it verifies the count, 0 orphans and 0 inconsistencies.
  - A staged database where P1's `refundNettedAmount` is set to 50.0 fails verification, and the restore is refused.
  - A pre-153 backup gets an expected count of 0, and its spending aggregate is compared through the legacy hash (D-14).
- **AE-25 (INV-21).** CSV export after AE-1:
  - P1 is exported as `amount 100.00, effectiveAmount 60.00, refundNettedAmount 40.00`.
  - D1 is exported as `40.00, 0.00, 40.00`.
  - Re-importing creates P1 at 100 and D1 at 40, unlinked, with the column at 0.0.
- **AE-26 (INV-16).** When D1 arrives through notification intake, no link is written, and only the notice appears.
- **AE-27 (INV-22).** After AE-1, the event rows and log lines contain no "Zara", no 40, no 100 and no note text. They contain only ids, counts and codes.
- **AE-28.** With no deposits, there is no notice. A deposit with no eligible purchase in the window gives `Available([])`. A DEPOSIT row that is not eligible (installment-flagged, or already linked) offers no "Apply to purchase" action.

## 8. Consumers

**Must change (rule owners and write path):**

- `Expense.effectiveAmount`;
- `SpendingInclusionSql`;
- `ComparableHomeValue.snapshotValue`;
- `SpendingInclusionParityTest`, the fixture grid;
- `TransactionLifecycleCoordinator`: link, unlink, delete cleanup, create forces 0, update keeps the column, lock;
- `TransactionUpdateKind`;
- `ExpenseDao`, for the INV-9 switch of 40 sites and the new setter;
- the new `ExpenseRefundLinkDao`, `ExpenseRefundLink`, `RefundNettingPolicy` and `RefundLinkUseCase`;
- `RefundLikelihoodDetector`;
- `BackupVerifier` and `SemanticAggregates`;
- `ExpenseExportMapper` and `ExportTransaction`;
- `GroupTransactionCoordinator`, for the refusal;
- `TransactionsScreen`, `TransactionsViewModel` and the transaction detail sheet (UI, D-17);
- `AppDatabase` and `DatabaseMigrations`.

**Net automatically, with no code change (they sum `Expense.effectiveAmount`, copy it into snapshots, or call the switched DAO totals). Verified at 80251ea3:**

- `MultiCurrencyRepository` (Kotlin at :217, :653 and :684; SQL grouping through the DAO), `TotalsAggregationEngine`, `BudgetAutopilotEngine` (`getHistoricalCategoryMonthlySpend`), and budget spent;
- `NetCashflowBalanceProvider`, `CashFlowCalculator` together with `GroupCashFlowRule` (its payer `copy()` keeps the column), and `FinancialStressForecastEngine`;
- `ForecastInputAssembler` (`ExpenseSnapshot.effectiveAmount`), `MoneyNormalizationEngine`, `AnalyticsCurrencyNormalizer`, `NormalizedAnalyticsInput` and `FinancialHealthScoreV2`;
- the accounting exporters and `AccountantReportPdfExporter`.

The impact map must confirm the rest by grep and classify each one as a total or a list: `ComputeDashboardWidgetsUseCase`, `ComputeMoneyRadarUseCase`, `SmartSavingsEngine`, `MonthlySavingsSweepUseCase`, `AnomalyAlertOrchestrator`, `SpendingThresholdCalculator`, `AdvancedAnalyticsDashboard`, `ExecuteFinancialQueryUseCase`, `NaturalLanguageExpenseQueryRepositoryImpl`, `DashboardContractsAdapter`, `FinancialRescueCoordinator`, `SharedExpenseBudgetOffsetEngine` and `InsightsEngine`.

**Stay gross by decision:**

- `ComparableHomeValue` list rules and `COMPARABLE_HOME_VALUE_E_SQL`;
- `ExpenseRepository.SortOrder` and the assistant `RawEffective` filters;
- the duplicate BLOCKING and RESOLVER families;
- recurring payment matching;
- the installment facts readers;
- the CSV and JSON importers.

## 9. Proposed LEGAL_PATHS change (not edited; for the architecture owner)

This replaces the REFUNDS block at `LEGAL_PATHS.md:180-188`:

```
REFUNDS (DR-05 refund netting, schema 153):
  → Refunds stay TransactionType.DEPOSIT (parsers unchanged). A user links one refund to one purchase:
    UI → RefundLinkUseCase.link(refundId, purchaseId) → TransactionLifecycleCoordinator.linkRefund()
    (write barrier, one transaction: re-read, RefundNettingPolicy check, insert expense_refund_links row,
     set expenses.refundNettedAmount on both rows, two UPDATED events with TransactionUpdateKind.REFUND_NETTING)
  → Unlink: RefundLinkUseCase.unlink(refundId) → TLC.unlinkRefund(). Delete of either side: TLC.deleteExpense
    removes links and recomputes the counterpart in the same transaction.
  → Netting: Expense.effectiveAmount / SpendingInclusionSql.EFFECTIVE_AMOUNT(_E) subtract refundNettedAmount
    (purchase spend and deposit income both drop by the applied amount); all SQL totals use the outer constant.
  → Suggestions: RefundLinkUseCase.findCandidates (read-only, read barrier, Available | Unavailable);
    RefundLikelihoodDetector notice only. No automatic link.
FORBIDDEN:
  ❌ Writing expense_refund_links or expenses.refundNettedAmount outside TLC linkRefund / unlinkRefund / deleteExpense
  ❌ Calling TLC.linkRefund / unlinkRefund from anywhere except RefundLinkUseCase
  ❌ SUM over ExpenseDao.EFFECTIVE_AMOUNT_SQL / _E_SQL in any total
  ❌ Linking installment-flagged, group-linked, cross-currency or non-PURCHASE/DEPOSIT rows
  ❌ Amount / currency / type edits on a linked row
```

Also proposed:

- an ENGINE_INTERACTION_MAP row for `RefundNettingPolicy` and `RefundLinkUseCase`;
- notes on the `ComparableHomeValue` and `MoneyNormalizationEngine` rows ("snapshotValue nets; list stays gross").

## 10. Open decisions

Every open decision has a recommended default, which `decisions.md` adopts. They are D-1 to D-18. The owner questions are Q-1 and Q-2 (`decisions.md`).

Proposed slices. All are money or schema work: a Sonnet writer plus a separate test author, and reviewer-strict at the merge.

- **S1, schema.** Entity, DAO, migration, snapshot, migration tests, and the `BackupVerifier` and `SemanticAggregates` changes. Room-migration guardian.
- **S2, formula.** `effectiveAmount`, `SpendingInclusionSql` and `snapshotValue`; the parity grid; the INV-9 switch of 40 sites and its static test. This is behaviour-neutral, because the column is 0 everywhere.
- **S3, write path.** TLC link, unlink, delete, create, update and lock; the policy; the use case; the group refusal; `TransactionUpdateKind`.
- **S4, UI and export.** The detector change, "Apply to purchase", the picker and confirm, the chips, unlink, the export column, and strings in en, with el and es as DRAFT.

## 11. Known limits

- **L-1.** No automatic link: until the user confirms, a refund stays income and the purchase stays gross. This is today's behaviour.
- **L-2.** One refund cannot be split across two purchases (D-5).
- **L-3.** Cross-currency refunds cannot be linked. For the same foreign currency, the home totals drop the FX difference between the purchase-date and refund-date rates.
- **L-4.** Undoing a delete, a CSV or JSON re-import, or any re-create does not restore links. The rows come back gross and unlinked.
- **L-5.** Linking a refund for a closed month changes that month's totals. Budget alerts are not withdrawn, and swept savings months are not re-run.
- **L-6.** A time zone change can shift the one-day date tolerance by 1.
- **L-7.** Pattern, subscription and anomaly detectors see a fully refunded purchase at 0 effective (or gross where they read `amount`). They are not tuned for refunds in v1.
- **L-8.** A linked row's amount must be unlinked before it can be edited.
- **L-9.** The return-window and receipt features are not connected to refund links in v1.
