# D3 Audit — SubBatch D.3

Reviewed sources:
- `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- `docs/reviews/AUDIT-PHASE-C-D.md`

Scope: only `### SubBatch D.3`.

Summary:
- RESOLVED: 5
- PARTIALLY_RESOLVED: 1
- STILL_OPEN: 10
- FALSE_POSITIVE: 0

## Issue-by-Issue Audit

1. `MileageTracking` reporting queries need composite index `(isBusinessTrip, date)` (B27)
- Status: **STILL_OPEN**
- Evidence: `MileageTracking.kt:22-26` and `AppDatabase.kt:5045-5047` define only single-column indexes on `isBusinessTrip` and `date`; `MileageTrackingDao.kt:26-39` runs reporting queries that filter by both.
- Suggested registry wording if status should change: No status change needed.

2. `BudgetForecastDao.getForecastForDate()` returns `LIMIT 1` without ordering — add `ORDER BY` (B27)
- Status: **STILL_OPEN**
- Evidence: `BudgetForecastDao.kt:27-28` still uses `LIMIT 1` with no `ORDER BY`. The active-row uniqueness in `AppDatabase.kt:4983-4989` only covers identical `(budgetId, targetPeriodStart, targetPeriodEnd)` tuples, not overlapping active periods.
- Suggested registry wording if status should change: No status change needed.

3. `HealthScoreHistory` `(periodStart, periodEnd)` only indexed — make unique (B27)
- Status: **STILL_OPEN**
- Evidence: `HealthScoreHistory.kt:14-18` and `AppDatabase.kt:3152-3154` still create a non-unique index only. `FinancialHealthScoreV2.kt:523-550` still does read-then-insert/update, so duplicates remain possible without a uniqueness constraint.
- Suggested registry wording if status should change: No status change needed.

4. `SubscriptionUsageDao.getAllUsageSince()` effectively unindexed — add standalone index on `usedAt` (B27-missed)
- Status: **STILL_OPEN**
- Evidence: `SubscriptionUsageDao.kt:27-28` still queries by `usedAt` alone, while `SubscriptionUsage.kt:22` still defines only `Index(value = ["subscriptionId", "usedAt"])`; no standalone `usedAt` index exists in current schema.
- Suggested registry wording if status should change: Replace the current bullet with:

  `- \`SubscriptionUsageDao.getAllUsageSince()\` effectively unindexed — add standalone index on \`usedAt\` (B27-missed) **[STILL_OPEN - Current schema still exposes only the composite index \`(subscriptionId, usedAt)\`; no standalone \`usedAt\` index exists for global \`getAllUsageSince()\` scans]**`

5. `SubscriptionCandidate.convertedSubscriptionId` has no FK — add nullable FK (B28-missed)
- Status: **STILL_OPEN**
- Evidence: `SubscriptionCandidate.kt:12-19,57-58` still declares no foreign key for `convertedSubscriptionId`, and the canonical schema rebuild in `AppDatabase.kt:3200-3224` also creates the column without any FK.
- Suggested registry wording if status should change: Replace the current bullet with:

  `- \`SubscriptionCandidate.convertedSubscriptionId\` has no FK — add nullable FK (B28-missed) **[STILL_OPEN - \`SubscriptionCandidate\` and canonical schema definitions still declare \`convertedSubscriptionId\` without a foreign key to \`ManualRecurringExpense(id)\`]**`

6. `MileageTracking` entity accepts impossible states — add validation (B28)
- Status: **PARTIALLY_RESOLVED**
- Evidence: `AppDatabase.kt:5015-5037` and `AppDatabase.kt:4577-4647` now enforce DB-level checks for positive distance, non-negative rates/fuel cost, and odometer ordering, but `MileageTracking.kt:28-64` still has no constructor invariants and `BusinessExpenseRepository.kt:79-80` still inserts directly with no pre-insert validation.
- Suggested registry wording if status should change: Replace the current bullet with:

  `- \`MileageTracking\` entity accepts impossible states — add validation (B28) **[PARTIALLY_RESOLVED - DB CHECK constraints now reject negative distance/rates, negative fuel cost, and inverted odometers, but \`MileageTracking\` still has no constructor/repository validation and invalid instances can still be created before insert]**`

7. `formattedAmount` hardcodes `Locale.US` — centralize formatting (B29-missed)
- Status: **RESOLVED**
- Evidence: `ExpenseWithCategory.kt:49-56` now formats with `Locale.getDefault()` and no longer hardcodes `Locale.US`.
- Suggested registry wording if status should change: No status change needed.

8. `ExpenseWithCategory_Extensions` shadowed by member properties — delete duplicate extensions (B29)
- Status: **RESOLVED**
- Evidence: `ExpenseWithCategory_Extensions.kt:22-31` now exposes only `formattedTime`; the shadowing `formattedAmount`/duplicate `formattedDate` extensions are gone.
- Suggested registry wording if status should change: No status change needed.

9. `getExpensesPagedDynamic()` selects subset of columns but maps to full `ExpenseWithCategory` — use `SELECT e.*` (B29)
- Status: **RESOLVED**
- Evidence: `ExpenseRepository.kt:115-131` now passes `selectClause = "SELECT e.*"` for the paged dynamic query.
- Suggested registry wording if status should change: No status change needed.

10. `CloudReceiptItemCategorizationService` uses `ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS` for cloud — add cloud-specific constant (B09)
- Status: **RESOLVED**
- Evidence: `CloudReceiptItemCategorizationService.kt:248-253` now uses `AppConfig.Ai.CLOUD_RECEIPT_ITEM_MAX_TOKENS`; the cloud constant exists in `AppConfig.kt:163`.
- Suggested registry wording if status should change: Update the bullet to:

  `- \`CloudReceiptItemCategorizationService\` uses \`ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS\` for cloud — add cloud-specific constant (B09) **[RESOLVED - Cloud request now uses \`AppConfig.Ai.CLOUD_RECEIPT_ITEM_MAX_TOKENS\` in \`buildRequestBody()\`]**`

11. `CloudWarrantyExtractionService` hardcodes model name and token budget — use shared config (B09)
- Status: **STILL_OPEN**
- Evidence: `CloudWarrantyExtractionService.kt:72-78` still hardcodes `maxOutputTokens = 1024` and model `gemini-2.0-flash` instead of reading shared config.
- Suggested registry wording if status should change: No status change needed.

12. `CloudReviewExplanationService` generates new correlation ID per retry — generate one before retry loop (B09)
- Status: **STILL_OPEN**
- Evidence: `CloudReviewExplanationService.kt:67-72` still creates `CloudCorrelation.newCorrelationId()` inside the retry loop/HTTP-failure branch rather than once per request.
- Suggested registry wording if status should change: No status change needed.

13. `CloudWarrantyExtractionService` accepts `"null"` string placeholders — filter placeholders (B09)
- Status: **STILL_OPEN**
- Evidence: `CloudWarrantyExtractionService.kt:243,258-261` still accepts any non-blank `supportPhone`, `supportEmail`, and `returnConditions`; literal `"null"` strings are not filtered.
- Suggested registry wording if status should change: No status change needed.

14. `CloudReceiptItemCategorizationService` hardcodes `€` in prompts — use input currency (B09)
- Status: **RESOLVED**
- Evidence: `CloudReceiptItemCategorizationService.kt:184-191` formats amounts with `CurrencyFormatter.format(item.totalPrice, input.currency)`; no hardcoded euro literal remains in the prompt path.
- Suggested registry wording if status should change: Update the bullet to:

  `- \`CloudReceiptItemCategorizationService\` hardcodes \`€\` in prompts — use input currency (B09) **[RESOLVED - Prompt now formats line-item amounts via \`CurrencyFormatter.format(item.totalPrice, input.currency)\`; no hardcoded euro literal remains]**`

15. `OnDeviceDashboardBriefingService` confidence parsed with `optDouble.toFloat()` without finiteness check — parse strictly (B09-missed)
- Status: **STILL_OPEN**
- Evidence: `DashboardBriefingResponseParser.kt:18-23` still uses `root.optDouble("confidence").toFloat()` with no finiteness validation before constructing `DashboardBriefing`.
- Suggested registry wording if status should change: No status change needed.

16. `OnDeviceDedupeJudgeService` `matchedTargetId`/`confidence` use lenient parsing — use strict parsing (B09-missed)
- Status: **STILL_OPEN**
- Evidence: `OnDeviceDedupeJudgeService.kt:93-100` still parses `matchedTargetId` with `optLong()` and `confidence` with `optDouble().toFloat()`; both remain lenient and allow coercion/non-finite values.
- Suggested registry wording if status should change: No status change needed.

## Registry Update Instructions

Apply these registry edits under `### SubBatch D.3`:

1. Replace the `SubscriptionUsageDao.getAllUsageSince()` bullet with:

   `- \`SubscriptionUsageDao.getAllUsageSince()\` effectively unindexed — add standalone index on \`usedAt\` (B27-missed) **[STILL_OPEN - Current schema still exposes only the composite index \`(subscriptionId, usedAt)\`; no standalone \`usedAt\` index exists for global \`getAllUsageSince()\` scans]**`

2. Replace the `SubscriptionCandidate.convertedSubscriptionId` bullet with:

   `- \`SubscriptionCandidate.convertedSubscriptionId\` has no FK — add nullable FK (B28-missed) **[STILL_OPEN - \`SubscriptionCandidate\` and canonical schema definitions still declare \`convertedSubscriptionId\` without a foreign key to \`ManualRecurringExpense(id)\`]**`

3. Replace the `MileageTracking entity accepts impossible states` bullet with:

   `- \`MileageTracking\` entity accepts impossible states — add validation (B28) **[PARTIALLY_RESOLVED - DB CHECK constraints now reject negative distance/rates, negative fuel cost, and inverted odometers, but \`MileageTracking\` still has no constructor/repository validation and invalid instances can still be created before insert]**`

4. Update the `CloudReceiptItemCategorizationService` cloud-token bullet to:

   `- \`CloudReceiptItemCategorizationService\` uses \`ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS\` for cloud — add cloud-specific constant (B09) **[RESOLVED - Cloud request now uses \`AppConfig.Ai.CLOUD_RECEIPT_ITEM_MAX_TOKENS\` in \`buildRequestBody()\`]**`

5. Update the `CloudReceiptItemCategorizationService` hardcoded-euro bullet to:

   `- \`CloudReceiptItemCategorizationService\` hardcodes \`€\` in prompts — use input currency (B09) **[RESOLVED - Prompt now formats line-item amounts via \`CurrencyFormatter.format(item.totalPrice, input.currency)\`; no hardcoded euro literal remains]**`

No other SubBatch D.3 registry text changes are required from this audit.
