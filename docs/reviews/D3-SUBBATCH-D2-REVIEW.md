# D3 SubBatch D.2 Review

Scope audited: `MASTER-ISSUE-REGISTRY.md` → `### D.3: Medium (Quick Wins)` → `### SubBatch D.2`

Read context:
- `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- `docs/reviews/AUDIT-PHASE-C-D.md`

## Summary
- Total issues audited: **15**
- **RESOLVED:** 8
- **PARTIALLY_RESOLVED:** 1
- **STILL_OPEN:** 6
- **FALSE_POSITIVE:** 0

## Issue Audit

| # | Registry issue | Status | Evidence | Suggested registry wording if status should change |
|---|---|---|---|---|
| 1 | `TransferDirection.valueOf(review.suggestedDirection)` assumes valid enum — parse with `runCatching` | **PARTIALLY_RESOLVED** | `ReviewQueueRepository.kt:117-120` now parses `suggestedDirection` via `runCatching { ... }.getOrNull()`, but `ReviewScreen.kt:884-886` still calls `TransferDirection.valueOf(it)` directly when rendering the badge. | Replace with: `- \`TransferDirection.valueOf(review.suggestedDirection)\` is only partially fixed — review approval now parses defensively, but \`ReviewScreen\` still calls \`TransferDirection.valueOf(it)\` directly for transfer badges **[PARTIALLY_RESOLVED]**` |
| 2 | `CategoryDao.getByName()` is case-sensitive — add unique `COLLATE NOCASE` index | **STILL_OPEN** | `CategoryDao.kt:39-40` still uses `WHERE name = :name LIMIT 1`, and `Category.kt:7-15` declares no case-insensitive uniqueness/index on `name`. | No status change; keep open. |
| 3 | `ReceiptItemCategorizationDao.getTotalForCategoryInExpense()` counts rows where either suggested or corrected matches — use `COALESCE` | **STILL_OPEN** | `ReceiptItemCategorizationDao.kt:88-93` still sums rows with `WHERE (suggestedCategoryId = :categoryId OR userCorrectedCategoryId = :categoryId)`, so a corrected category does not override the suggestion in SQL semantics. | No status change; keep open. |
| 4 | `PendingReviewDao` legacy fallback queries have no index on `suggestedMerchant` — add composite index | **STILL_OPEN** | `PendingReviewDao.kt:110-118` and `143-149` still query legacy rows by `suggestedMerchant` with `suggestedMerchantKey IS NULL`, while `PendingReview.kt:34-41` indexes only `suggestedMerchantKey` / `(status, suggestedMerchantKey, suggestedDate)`. | No status change; keep open. |
| 5 | `MerchantNormalizationDao.getAliasByNormalizedKey()` does `LIMIT 1` on non-unique key — enforce uniqueness | **RESOLVED** | `MerchantAlias.kt:20-21` now declares `Index(value = ["normalizedKey"], unique = true)`, so `MerchantNormalizationDao.kt:56-57` no longer returns an arbitrary alias for duplicate keys. | Replace with: `- \`MerchantNormalizationDao.getAliasByNormalizedKey()\` no longer relies on arbitrary \`LIMIT 1\` behavior — \`merchant_aliases.normalizedKey\` is now unique, making lookup deterministic **[RESOLVED BY B.4 — Batch 5: \`normalizedKey\` uniqueness enforced]**` |
| 6 | `ManualRecurringExpenseDao` dual APIs disagree on ordering — make both use same ordering | **STILL_OPEN** | `ManualRecurringExpenseDao.kt:16-17` orders `getAllFlow()` by `nextDate ASC`, while `ManualRecurringExpenseDao.kt:29-30` orders `getAll()` by `createdAt DESC`. | No status change; keep open. |
| 7 | `GroupMemberDao.getCurrentUserFlow()` uses `LIMIT 1` with no `ORDER BY` — enforce single current-user row | **RESOLVED** | `AppDatabase.kt:3772-3778` and `4929-4933` create the partial unique index `index_group_members_groupId_currentUser`, and `GroupMemberDao.kt:83-102` updates current-user state transactionally. | Replace with: `- \`GroupMemberDao.getCurrentUserFlow()\` no longer depends on unordered \`LIMIT 1\` behavior — a partial unique index now enforces at most one \`isCurrentUser = 1\` row per group **[RESOLVED BY B.4 — Batch 3: partial unique index on \`(groupId)\` where \`isCurrentUser = 1\`]**` |
| 8 | `GroupExpenseDao.getGroupExpenseForExpense()` returns `LIMIT 1` but `expenseId` only indexed — add unique constraint | **RESOLVED** | `AppDatabase.kt:3797-3803` and `4935-4938` create partial unique index `index_group_expenses_expenseId_unique`, so `GroupExpenseDao.kt:33-34` can no longer match multiple non-null `expenseId` rows. | Replace with: `- \`GroupExpenseDao.getGroupExpenseForExpense()\` no longer returns an arbitrary row — a partial unique index now enforces one non-null \`expenseId\` mapping **[RESOLVED BY B.4 — Batch 3: unique index on non-null \`expenseId\`]**` |
| 9 | `MerchantLocationDao.upsertLocation()` read-then-insert under unique index — use single-statement upsert | **STILL_OPEN** | `MerchantLocationDao.kt:24-43` still performs `getByNormalizedNameAndArea(...)` and then branches to `updateExistingLocation(...)` or `insertLocation(...)`; this remains a read-then-write upsert pattern rather than a single SQL upsert statement. | No status change; keep open. |
| 10 | `ExchangeRateDao.getAllRatesForBase()` filters on non-leading index column — add index on `(toCurrency, fromCurrency)` | **RESOLVED** | `ExchangeRateDao.kt:25-26` still filters by `toCurrency`, and `ExchangeRate.kt:14-18` now includes `Index(value = ["toCurrency"])`, eliminating the non-leading-column scan problem. | No status change; existing resolved marker remains accurate. |
| 11 | `EmailReceiptDao.getByReceiptId()` returns single row but multiple sources can share same receiptId — return `List` | **RESOLVED** | `EmailReceiptDao.kt:40-41` now returns `List<EmailReceiptSource>`. | No status change; existing resolved marker remains accurate. |
| 12 | `SplitItemAssignmentDao.getParticipantTotals()` groups by `participantName` only — group by stable key | **STILL_OPEN** | `SplitItemAssignmentDao.kt:39-46` still groups only by `participantName`, while `SplitItemAssignment.kt:29-30` has both `participantName` and stable `participantIndex`; duplicate names still collapse into one bucket. | No status change; keep open. |
| 13 | `UserCorrectionDao` tie-breaking uses `LIMIT 1` with no secondary ordering — add stable secondary sort | **RESOLVED** | `UserCorrectionDao.kt:49-51` and `88-90` now break ties with `MAX(createdAt)` and a deterministic secondary column. | No status change; existing resolved marker remains accurate. |
| 14 | `AiChatRepositoryImpl.appendMessage()` persists message and updates session as two writes — move to one transaction | **RESOLVED** | `AiChatRepositoryImpl.kt:67-80` now wraps the insert and `updateLastTouched(...)` in `database.withTransaction { ... }`. | Replace with: `- \`AiChatRepositoryImpl.appendMessage()\` no longer splits message persistence and session timestamp updates across separate writes — both operations now run inside \`database.withTransaction { ... }\` **[RESOLVED]**` |
| 15 | `InvestmentValueDao.getPortfolioValueHistory()` one query per investment — add batched query | **RESOLVED** | `InvestmentTracker.kt:205-210` now calls `investmentValueDao.getPortfolioHistoryBatch(...)` once for all investment IDs and groups the results in memory before aggregation. | Replace with: `- \`InvestmentTracker.getPortfolioValueHistory()\` no longer issues one query per investment — it now uses batched DAO reads via \`investmentValueDao.getPortfolioHistoryBatch(...)\` **[RESOLVED]**` |

## Registry Update Instructions

Apply the following status updates in `MASTER-ISSUE-REGISTRY.md` under `### SubBatch D.2`:

1. Replace the current bullet for transfer-direction enum parsing with:

   `- \`TransferDirection.valueOf(review.suggestedDirection)\` is only partially fixed — review approval now parses defensively, but \`ReviewScreen\` still calls \`TransferDirection.valueOf(it)\` directly for transfer badges **[PARTIALLY_RESOLVED]**`

2. Replace the current bullet for merchant-alias normalized-key uniqueness with:

   `- \`MerchantNormalizationDao.getAliasByNormalizedKey()\` no longer relies on arbitrary \`LIMIT 1\` behavior — \`merchant_aliases.normalizedKey\` is now unique, making lookup deterministic **[RESOLVED BY B.4 — Batch 5: \`normalizedKey\` uniqueness enforced]**`

3. Replace the current bullet for `GroupMemberDao.getCurrentUserFlow()` with:

   `- \`GroupMemberDao.getCurrentUserFlow()\` no longer depends on unordered \`LIMIT 1\` behavior — a partial unique index now enforces at most one \`isCurrentUser = 1\` row per group **[RESOLVED BY B.4 — Batch 3: partial unique index on \`(groupId)\` where \`isCurrentUser = 1\`]**`

4. Replace the current bullet for `GroupExpenseDao.getGroupExpenseForExpense()` with:

   `- \`GroupExpenseDao.getGroupExpenseForExpense()\` no longer returns an arbitrary row — a partial unique index now enforces one non-null \`expenseId\` mapping **[RESOLVED BY B.4 — Batch 3: unique index on non-null \`expenseId\`]**`

5. Replace the current bullet for `AiChatRepositoryImpl.appendMessage()` with:

   `- \`AiChatRepositoryImpl.appendMessage()\` no longer splits message persistence and session timestamp updates across separate writes — both operations now run inside \`database.withTransaction { ... }\` **[RESOLVED]**`

6. Replace the current bullet for the portfolio-history N+1 issue with:

   `- \`InvestmentTracker.getPortfolioValueHistory()\` no longer issues one query per investment — it now uses batched DAO reads via \`investmentValueDao.getPortfolioHistoryBatch(...)\` **[RESOLVED]**`

7. Leave the other 9 SubBatch D.2 entries unchanged:
   - 6 remain **STILL_OPEN**
   - 3 already have correct resolved markers
