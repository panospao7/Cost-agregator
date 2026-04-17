# Plan: Remaining Phase 2 Issue Fixes (V2)

## Overview
Conservative open count: 532 registry / dependency entries still unannotated after final-review closures
Prioritized by remaining HIGH > MEDIUM > LOW + dependency blockers

- This V2 plan supersedes `docs/plans/PLAN-REMAINING-PHASE2-FIXES.md`, which covered the final reviewer-derived HIGH subset.
- Resolved / excluded from V2: all Section A epics, all B.4 database / DAO pipeline items, all B.1-B.6 CRITICAL / HIGH items, and all B.7-B.12 CRITICAL / HIGH items except the 2 residual B.9 HIGH items confirmed still open by `docs/reviews/PHASE2-REVIEW-B7-12-FINAL.md`.
- In scope for V2:
  - the 2 remaining B.9 HIGH issues,
  - all remaining B.1-B.12 MEDIUM / LOW items,
  - all still-relevant Section C dependency entries,
  - all Section D quick wins that are not already explicitly resolved by annotation or by the final-review closure state.
- To keep execution safe, the remaining registry bullets are grouped into issue families by owning module. Each remaining open registry entry should map to exactly one batch below.
- Coverage note:
  - Batches 1-11 own the remaining open B-subsection backlog.
  - Section D items are folded into the owning batch by root module.
  - Batch 12 owns the remaining Section C dependency work plus unassigned cross-cutting DAO / domain / infrastructure carry-over fixes.
- Recommended execution order:
  1. Batch 1 - residual B.9 HIGH closeout and UI follow-through
  2. Batch 2 - AI / provider / runtime contract hardening
  3. Batch 3 - budget / forecast / dashboard / analytics consistency
  4. Batch 4 - receipt / OCR / warranty runtime hardening
  5. Batch 5 - notification / recommendation / service cleanup
  6. Batch 6 - export / backup / accounting tooling cleanup
  7. Batch 7 - savings / investment / tax / financial-health follow-through
  8. Batch 8 - location / geocoding / map / price-protection behavior
  9. Batch 9 - categorization / intelligence / dedupe convergence
  10. Batch 10 - email / parser / import utility hardening
  11. Batch 11 - groups / shared-expense / split semantics convergence
  12. Batch 12 - cross-cutting DAO / domain / dependency closeout
- Highest-risk unknowns to verify before coding:
  - whether challenge creation should be a dedicated screen, modal, or editor-reuse flow;
  - whether currency centralization should always render home currency or preserve per-row transaction currency;
  - which remaining Section D bullets are stale carry-overs vs still-live code paths in the current tree;
  - whether Batch 12 introduces a second migration lane for remaining DAO / schema / invariant fixes.

---

## Batch 1: Remaining UI / Challenge / Currency Closeout (owns open B.9 backlog + UI D entries)
### Issues
1. [B.9][HIGH][sources: B19 + PHASE2-REVIEW-B7-12-FINAL] Spending challenge creation is still not end-to-end because `MainActivity` still routes `onCreateChallenge` to a placeholder / back-navigation path even though challenge persistence and active-challenge loading now exist.
2. [B.9][HIGH][sources: B18 + PHASE2-REVIEW-B7-12-FINAL] Currency presentation is still not centralized; remaining hardcoded `EUR` / `€` UI surfaces include `RetroTopCategoriesCard`, `ForecastTimeline`, `CashFlowCalendarScreen`, `BudgetScreen`, `TotalsDashboardCard`, plus any additional grep-identified callers.
3. [B.9][MEDIUM][sources: B19/B18/B19-missed] Challenge and currency UI follow-through is still stale: `SpendingChallengesScreen` does not keep `completedActions` / empty-state actions fresh, the active-challenges branch still renders placeholder UI, conversion dialogs still allow invalid submission states, and editable numeric fields still round-trip through parsed numeric text instead of preserving user input.
4. [B.9][MEDIUM][sources: B16/B17] Screen-state and filter semantics are still inconsistent: ownership-only filtering can hide the active-filter banner, month / year ranges still use wall-clock timestamps and clipped end bounds, `correlationId` still affects filter equality, budget suggestions do not refresh after mutations, and "Month" semantics still drift across Home / Analytics / Transactions.
5. [B.9][MEDIUM][sources: B17/B18/B36] Several UI flows still fail closed or mutate during composition: `AdvancedAnalyticsViewModel` falls through to a blank screen, `ReviewScreen` mutates ViewModel state during composition, processing guards are incomplete, `approveAllPending` still misses matches by using the wrong merchant key, OSM metadata is still dropped, starting-balance edits can trigger uncancelled recalculations, and stale counters / stale async jobs can still win.
6. [B.9][LOW][sources: B17/B18/B19 + related D.3/D.4 carry-over] UI polish remains open: scaffold padding, `ErrorBanner` overlap, `NaN` chart-height guards, delete-from-ALL pagination reset, hardcoded English copy, starter prompts, lifecycle-aware state collection, invalid currency-instance handling, and other current-tree UI carry-overs should be swept here instead of fragmenting them into later batches.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/navigation/*`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/challenge/*`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/currency/*`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/cashflow/CashFlowCalendarScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/review/*`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/*`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/map/*`
- `app/src/main/java/com/yourname/expensetracker/ui/components/RetroTopCategoriesCard.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/ForecastTimeline.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/TotalsDashboardCard.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/util/CurrencyFormatter.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/**/*`

### Approach
- Wire a real create-challenge destination from `MainActivity` through navigation into the existing persisted challenge flow; do not introduce a second in-memory creation path.
- Finish the remaining currency sweep by forcing all targeted UI surfaces through shared currency-formatting helpers and by grepping for lingering user-facing hardcoded currency literals after the refactor.
- Make challenge and currency screens state-driven instead of "remember once" snapshots; keep editable amount text in text-state models and prevent invalid inputs from being submitted.
- Normalize date / filter semantics around `TimeProvider`, stable period boundaries, and active-filter visibility derived from all active filter dimensions rather than one flag.
- Add explicit error / retry states and side-effect guards so recomposition never mutates ViewModel state and stale async jobs cannot overwrite newer results.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "*SpendingChallenges*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Currency*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Review*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*AdvancedAnalyticsViewModel*"`
- `./gradlew.bat :app:lintDebug`
- `rg "€|EUR" app/src/main/java/com/yourname/expensetracker/ui`

### Done when
- Creating a spending challenge from the UI no longer lands on a placeholder path.
- Remaining high-traffic UI surfaces stop hardcoding `€` / `EUR` and share one formatting path.
- Challenge, review, budget, analytics, and currency screens no longer depend on stale remembered state or composition-time mutations.
- Filter banners, month semantics, and editable amount fields behave consistently across screens.

---

## Batch 2: AI Runtime / Provider Contract / Review-Priority Hardening (owns open B.1 backlog + AI D entries)
### Issues
1. [B.1][MEDIUM][sources: B06/B10/B25/B35/B34] Hybrid AI services still drift from actual execution: route provenance is collapsed in persistence, hybrid wrappers can re-route after metadata is built, `usedImageInput` can be inaccurate after fallback, and startup defaults can transiently disagree with persisted AI settings.
2. [B.1][MEDIUM][sources: B09/B10/B25/B26] Provider IO semantics are still unsafe: JSON extraction is still greedy, non-query on-device providers still lack timeouts, cloud retry still ignores `Retry-After`, and lenient numeric / ID parsing still allows malformed confidence or target data to degrade into `NaN`, `0`, or mismatched candidates.
3. [B.1][MEDIUM][sources: B10/B25/B35/B36] Sanitization and domain-boundary cleanup is still incomplete: multiple providers repeat truncate-before-redact mistakes, domain builders still import provider-internal helpers, source hashing is still weaker than it should be in some assist paths, and cancellation is still swallowed in some fallback flows.
4. [B.1][MEDIUM][sources: B08/B25/B26/B34] Router and contract support is still incomplete: `WARRANTY_EXTRACTION` remains policy-coupled to receipt-assist settings, cloud mode can still skip viable on-device capabilities, structured queries still cannot express transaction types, multi-value filters can still collapse during navigation mapping, and `QueryMetric.MIN` / dead review-priority paths still need explicit ownership.
5. [B.1][LOW/MEDIUM][sources: B34/B35/B36/B24 + related D.3/D.4] Review-priority and briefing support code still has consistency gaps: exact-string `merchantClarity`, short UUID correlation IDs, shared `SimpleDateFormat`, first-match severity messaging, clock-free priority scoring factors, and duplicated route-diagnostic formatting should be cleaned up in one pass.
6. [D.3/D.4][cross-cutting AI carry-over] Related open quick wins should land here as part of the same contract sweep: `GetAiRuntimeStatusUseCase` sequential capability checks, `CloudReceiptAssistService` file-size checks before full image read, safe enum parsing in on-device dedupe, `AiSettingsRepositoryImpl.settings()` corruption recovery, `CloudJsonParser` first-valid-JSON extraction, tighter phone redaction, and removal of shared mutable `lastUsedImageInput` state.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/*`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/*`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/**/*`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/ai/**/*`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/review/**/*`
- `app/src/main/java/com/yourname/expensetracker/domain/review/*`
- `app/src/main/java/com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt`
- `app/src/test/java/com/yourname/expensetracker/data/ai/provider/**/*`
- `app/src/test/java/com/yourname/expensetracker/domain/ai/**/*`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/**/*`

### Approach
- Make route selection single-source-of-truth: build metadata after the final route is chosen, preserve that route through persistence, and remove wrapper re-routing that can desynchronize artifacts from execution.
- Centralize provider parsing / timeout / retry behavior behind shared helpers so JSON extraction, finiteness checks, `Retry-After`, and correlation IDs behave consistently across cloud and on-device providers.
- Move sanitization / hashing helpers to a shared non-provider boundary and reuse them from all builders and providers.
- Treat remaining query / router support gaps as contract mismatches, not one-off bugs: either support the field end-to-end or make unsupported paths explicit so navigation / UI cannot silently widen queries.
- Re-run all AI error-propagation / cancellation paths after the contract cleanup so failures stay diagnosable and cancellation remains structural.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Ai*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*ReviewPriority*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*DashboardBriefing*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*FinancialQuery*"`
- `./gradlew.bat :app:lintDebug`

### Done when
- AI artifact metadata always matches the route that actually executed.
- Provider parsing, timeout, retry, and sanitization rules are shared and deterministic.
- Structured query / navigation contracts stop silently dropping unsupported fields.
- Remaining AI runtime quick wins are absorbed into one auditable provider-contract pass.

---

## Batch 3: Budget / Forecast / Dashboard / Analytics Consistency (owns open B.2 backlog + analytics D/C entries)
### Issues
1. [B.2][MEDIUM][sources: B02/B32/B37/B48] Budget and forecast semantics still drift in medium-severity paths: historical analysis still drops zero-spend months, month bucketing still mixes UTC and local calendar logic, sparse-history confidence is still rewarded, active forecast rows can still overlap, stale time bounds can still leak into long-lived collectors, and `CRITICAL` status handling still does not flow cleanly into budget-health / summary messaging.
2. [B.2][MEDIUM][sources: B36/B37/B12] Advanced analytics and dashboard engines still have correctness and performance gaps: month-end boundaries remain off by one second, monthly trend still does N+1 queries, sparklines can stop before today, weekday results are still ordered by spend instead of weekday index, transfer-correction totals are still not rebuilt, and `IRREGULAR` recurring semantics still diverge by code path.
3. [B.2][MEDIUM][sources: B32/B37/B43/B48] Shared-budget and widget composition is still inconsistent: repository spend can diverge from adjusted shared-expense spend, member-contribution APIs remain placeholders, overall-vs-category budget resolution still differs across widget paths, and budget summary copy can still report "all on track" even when warning / critical statuses exist.
4. [D.2/D.3][sources: B05/B36/B37/B40/B43/B48] Related forecast / widget / synthesis carry-overs remain open: `MoneyRadar` / Monte Carlo messaging can still say "exceed by 0.00", independent fetches still run sequentially, time capture can still mix multiple `now` values, runway calculations can still misclassify zero-burn cases, and non-finite `pastSumDaily` inputs can still poison projections.
5. [Section C][sources: C.2 steps 2/5/7 + C.3 groups 9/11/12/14/15/17/18/28] Remaining dependency cleanup is still required here: shared period math, rollover-aware clocks, split-resolution alignment where budgets depend on group shares, analytics-engine de-duplication, weekly aggregate boundary ownership, financial-health KPI convergence, dual Monte Carlo consolidation, and dashboard / weather / forecast assumption alignment.
6. [B.2][LOW][sources: B02/B37/B01] Low-severity budget backlog should be closed in the same pass: anchor-time daily windows, unreachable seasonal adjustment, hardcoded English domain copy in `BudgetMonitor`, and stale plan references like the missing `SpendingPaceModels.kt` file reference.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/budget/*`
- `app/src/main/java/com/yourname/expensetracker/domain/forecast/*`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/TransferDirectionAnalytics.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/forecast/*`
- `app/src/main/java/com/yourname/expensetracker/domain/synthesis/SynthesisEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedBudgetManager.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/**/*Budget*`
- `app/src/test/java/com/yourname/expensetracker/domain/**/*Analytics*`

### Approach
- Finish the budget / forecast stack around one period and bucket model, then propagate that model into dashboard widgets, analytics, and forecast summaries instead of letting each layer bucket history independently.
- Use aggregate or batched repository reads for trend / history builders so correctness fixes also remove N+1 behavior.
- Align budget-health summaries, shared-budget overlays, and Monte Carlo / widget messages to the same resolved budget inputs and risk tiers.
- Treat remaining D quick wins in this area as contract mismatches between calculators rather than isolated copy bugs.
- Close the relevant Section C dependency entries by documenting which component is now the source of truth for period math, weekly aggregates, and forecast assumptions.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Budget*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Forecast*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Analytics*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*MoneyRadar*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*DashboardWidgets*"`

### Done when
- Budget, forecast, dashboard, and analytics layers agree on period windows, zero buckets, and risk semantics.
- Monthly trends and sparklines use correct boundaries without N+1 queries.
- Shared-budget overlays and widget summaries stop diverging from repository spend calculations.
- The open Section C dependency items for budget / analytics are explicitly closed or narrowed.

---

## Batch 4: Receipt / OCR / Warranty Runtime Hardening (owns open B.3 backlog + receipt D entries)
### Issues
1. [B.3][MEDIUM][sources: B09/B26/B45] OCR and assist runtime still has structural gaps: parsed numerics still use lenient coercion, `ReceiptOcrService` can still close a recognizer mid-OCR because `close()` does not share the same lock, and OCR improvement components are still registered in DI without actually affecting runtime execution.
2. [B.3][MEDIUM][sources: B44/B45] Receipt parsing and merchant extraction still have correctness gaps: bank-statement header / date-column detection is still computed but unused, `EnhancedMerchantExtractor.isPrice()` still misses amount-only lines, known merchants can still be dropped when OCR candidates are empty, and `ReceiptParser` can still fabricate negative subtotals when tax is wrong.
3. [B.3][MEDIUM][sources: B44/B45/B18] Warranty and status behavior still needs cleanup: formatter parsing is still lenient, multiline date regex handling is still incomplete, model / token settings remain hardcoded, string placeholder contacts can still leak through, and UI status filters still assume transitions that may not yet exist in historical data.
4. [B.3][MEDIUM/LOW][sources: B09/B44/B45] Image / cache / prompt behavior is still incomplete: image cache keys still ignore requested dimensions, disk cache still never evicts, cloud item categorization still uses the wrong max-token constant and hardcoded prompt currency, and retry correlation IDs still regenerate per attempt in some receipt-related services.
5. [D.3][related receipt / parsing carry-over] Remaining receipt-adjacent quick wins should land in the same pass: strict `lineItemsFromJson()` failure handling, explicit SMS transfer ambiguity handling, and any remaining current-tree OCR / receipt utility bugs that touch the same services.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptAssistService.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ocr/*`
- `app/src/main/java/com/yourname/expensetracker/domain/cache/ImageCache.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/warranty/*`
- `app/src/test/java/com/yourname/expensetracker/domain/receipt/**/*`
- `app/src/test/java/com/yourname/expensetracker/data/ai/provider/**/*Receipt*`

### Approach
- Make OCR / receipt services strict by default: fail invalid provider output instead of coercing it, and share one lock / lifecycle contract for OCR recognizer use and teardown.
- Apply the remaining merchant / subtotal / statement parsing fixes in one parser pass so extracted text, known-merchant fallback, and subtotal derivation stop disagreeing with each other.
- Externalize receipt- and warranty-service model / token / prompt settings, and normalize placeholder filtering / correlation IDs while doing so.
- Treat cache correctness as part of parsing correctness: bitmap reuse must be dimension-aware, and disk caches must have deterministic pruning rules.
- Re-verify warranty UI filters against actual persisted status semantics so the UI does not assume transitions that historical rows never received.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Receipt*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Warranty*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Ocr*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*BankStatement*"`

### Done when
- OCR services stop accepting malformed provider numerics or unsafe recognizer shutdown races.
- Receipt parsing no longer loses known merchants, fabricates negative subtotals, or ignores computed statement metadata.
- Warranty / receipt provider settings and correlation behavior are centralized and configurable.
- Image caches are dimension-safe and prune deterministically.

---

## Batch 5: Notification / Recommendation / Service Lifecycle Cleanup (owns open B.6 backlog + service D entries)
### Issues
1. [B.6][MEDIUM][sources: B20/B21/B33] Notification capture and repository lifecycle still has correctness gaps: currency regex matching is still case-sensitive after content normalization, service teardown can still abort in-flight persistence, repeated boot / alarm restarts still create unnecessary wakeups, invalidation semantics still do not match method names, and `deleteAll()` still leaves `source_stats` partially stale.
2. [B.6][MEDIUM][sources: B20/B21/B47] Recommendation state and dedupe rules are still inconsistent: non-current-user clears still wipe in-memory state, signatures still overfit `category` and underfit `ownership`, priority ordering still needs explicit severity semantics, and invalidation still does not reliably clear all active rows.
3. [B.6][MEDIUM][sources: B33/B44] Review / notification pipelines still have a few persistence and duplicate-handling gaps: reparsed `PendingReview` rows can still duplicate, oversized-amount fallbacks still bypass semantic duplicate checks, and parser amount handling for SMS / Revolut remains too narrow for grouped amounts.
4. [B.6][LOW][sources: B21/B39 + related D.2/D.3] Diagnostics and background-service support still need cleanup: briefing notifications should use the shared ID allocator, invalidators should log failures instead of swallowing them, seeders still derive bad package names and unrealistic recurring patterns, diagnostics counters still race in `SharedPreferences`, and `BankApiIntegration.shouldSync()` should not schedule disabled or disconnected connections.
5. [D.2/D.3][related service carry-over] Service-adjacent quick wins should land here too: `BankApiIntegration` demo / mock OAuth behavior should be gated behind explicit not-implemented paths, dashboard follow-through recommendations should preserve source transaction type, and any remaining current-tree recommendation / notification service carry-overs should be collapsed into this cleanup batch.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/service/notification/*`
- `app/src/main/java/com/yourname/expensetracker/service/*Worker*.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/recommendation/*`
- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/RecommendationRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/WidgetStyleRepositoryImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/SmsParser.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/RevolutParser.kt`
- `app/src/main/java/com/yourname/expensetracker/data/bank/*`
- `app/src/test/java/com/yourname/expensetracker/**/*Notification*`
- `app/src/test/java/com/yourname/expensetracker/**/*Recommendation*`

### Approach
- Normalize lifecycle semantics first: service shutdown should drain or hand off work safely, and scheduled starts should only happen when the service actually needs to run.
- Rebuild recommendation signature / priority logic around explicit fields and explicit ordering, then reuse that logic everywhere recommendation invalidation or dedupe occurs.
- Make notification / review fallback paths run through the same duplicate policy as primary paths so edge-case inserts cannot bypass protections.
- Fold diagnostics, seeder realism, and demo bank-integration behavior into the same service audit so background behavior becomes predictable and production-safe.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Notification*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Recommendation*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*WidgetStyle*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*BankApiIntegration*"`

### Done when
- Notification capture and teardown no longer risk partial persistence or redundant wakeups.
- Recommendation dedupe, priority, and invalidation behavior is explicit and internally consistent.
- Edge-case review / notification inserts stop bypassing duplicate policy.
- Seeder, diagnostics, and bank-sync background behavior is production-safe.

---

## Batch 6: Export / Backup / Accounting Tooling Cleanup (owns open B.7 backlog + export D entries)
### Issues
1. [B.7][MEDIUM][sources: B39/B33-missed/B37] Exporters still have shared formatter and serialization issues: singleton `SimpleDateFormat`, raw `Double.toString()` money output, `DebugData.toJson()` hand-built escaping, unused `includeReceipts`, missing currency column in generic CSV, and no protection against CSV formula-injection prefixes.
2. [B.7][LOW][sources: B39/B37] Export payload polish is still open: transaction dates in debug output still mix epoch and ISO styles, and mileage summaries still report the first rate as if it applied to the whole period.
3. [D.3][related export / backup carry-over] Export / backup quick wins still need cleanup in the same lane: `AccountingExporters` should use `java.time` or per-call formatters, money formatting should be centralized, and backup import / restart semantics should return explicit result models instead of sentinel-value tunnels.
4. [D.3/D.4][ownership / DI carry-over] Export object-graph cleanup remains open too: export / backup entry points should use injected exporters instead of constructing them ad hoc, and any remaining current-tree accounting-export dead paths should be deleted or normalized here.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/data/export/*`
- `app/src/main/java/com/yourname/expensetracker/data/repository/AccountingExportRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/util/*Money*`
- `app/src/test/java/com/yourname/expensetracker/**/*Export*`
- `app/src/test/java/com/yourname/expensetracker/**/*Backup*`

### Approach
- Treat export correctness as a formatter contract: date, money, and escaping rules should be shared and testable rather than embedded in each exporter.
- Make all export entry points use one dependency-injected export path so repository and UI code cannot drift again.
- Replace sentinel / stringly result handling in backup flows with explicit result models before any restart / import behavior changes.
- Sweep low-severity polish items (date shapes, mileage-rate presentation) while the export models are already being touched.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Export*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Backup*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Accounting*"`

### Done when
- Exporters share one date / money / escaping contract.
- Backup flows return explicit typed outcomes instead of sentinel restarts.
- UI and repository export paths cannot diverge on object construction or formatting rules.

---

## Batch 7: Savings / Investment / Tax / Financial-Health Follow-through (owns open B.8 backlog + finance D entries)
### Issues
1. [B.8][MEDIUM][sources: B03/B45/B48] Savings and sweep heuristics still have modeling gaps: WEEK / QUARTER horizons still scale a month-only simulation, `monthlyDiscretionary` still divides by a fixed `3.0`, sweep-risk still includes `WITHDRAWAL`, null Monte Carlo still falls back to a hardcoded risk buffer, and the last-goal remainder path can still bypass `MAX_SINGLE_ALLOCATION_PERCENT`.
2. [B.8][MEDIUM][sources: B41/B46] Recurring income and investment tracking still needs cleanup: `RecurringIncomeTracker` still uses raw `amount`, month-start milliseconds can still drift, blank merchants still pollute grouped income, day change is still based on the previous snapshot instead of previous day's close, and some low-severity defaults / invariants on planned-expense and savings-goal models remain weak.
3. [B.8][MEDIUM][sources: B41/B03] Financial-health scoring still has unresolved semantic gaps: bill reliability still proxies recurring-pattern confidence instead of payment behavior, variance thresholds are still dimensionally wrong, week math still needs `TimePeriodUtils`, trend comparison still needs to exclude the current period, and fallback / empty-list handling still lets synthetic or unreachable scores leak into user-visible outputs.
4. [D.3][related finance carry-over] Additional savings / health quick wins belong here too: `goal_crusher` should select the best normalized goal, unlocked timestamps should persist first unlock, zero-target division should be guarded, `calculateBudgetHealthScore()` should either use period expenses or drop the parameter, and `calculateTodayScore()` should trust supplied streak inputs instead of mutating them locally.
5. [D.3][investment / history carry-over] The same batch should also absorb remaining current-tree finance performance fixes: batched portfolio-history loading, active-holdings-only reporting, weighted or explicit rate presentation, and any remaining savings-rate percent-vs-fraction edge cases still visible after the high-severity fixes.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/domain/savings/*`
- `app/src/main/java/com/yourname/expensetracker/domain/investment/*`
- `app/src/main/java/com/yourname/expensetracker/domain/tax/*`
- `app/src/main/java/com/yourname/expensetracker/domain/health/*`
- `app/src/main/java/com/yourname/expensetracker/data/repository/InvestmentTracker.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/RecurringIncomeTracker.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/InvestmentValueDao.kt`
- `app/src/test/java/com/yourname/expensetracker/**/*Savings*`
- `app/src/test/java/com/yourname/expensetracker/**/*Investment*`
- `app/src/test/java/com/yourname/expensetracker/**/*Health*`

### Approach
- Align all savings and sweep heuristics to the real modeled horizon instead of scaling a monthly answer into other periods.
- Treat recurring-income and investment fixes as data-contract fixes: correct merchant grouping, correct day-close comparison, and explicit invariants on low-level models.
- Rework health scoring around explicit period inputs, payment behavior, and explicit fallback flags so synthetic scores are never mistaken for real ones.
- Absorb the remaining finance quick wins while the same calculators and models are already under test.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Savings*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Investment*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*FinancialHealth*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Tax*"`

### Done when
- Savings and sweep heuristics stop scaling a month-only answer into unrelated horizons.
- Recurring income and investment histories use correct grouping, correct day-change semantics, and explicit invariants.
- Financial-health outputs stop relying on synthetic or dimensionally invalid fallback behavior.

---

## Batch 8: Location / Geocoding / Map / Price-Protection Behavior (owns open B.5 backlog + map D entries)
### Issues
1. [B.5][MEDIUM][sources: B30/B32/B42] Geocoding and resolver behavior still has ordering and fallback gaps: `getLastKnownLocation()` still ignores cached last location, composite lookup still treats unexpected exceptions as terminal `Unknown`, Nominatim ignores its requested limit, and `LocationResolver` still fetches device location before cheaper correction / cache paths.
2. [B.5][MEDIUM][sources: B42/B32] Spatial bucketing still drifts between layers: repository and DAO still disagree on negative-coordinate area keys, grid bucketing still truncates instead of flooring, and cache hits still mutate `lastResolvedAt`, turning TTL into time-since-last-access rather than time-since-resolution.
3. [B.5][MEDIUM][sources: B42/B44 + related D.2] `PriceProtectionTracker` remains semantically weak: eligibility still uses the wrong time source for imported receipts, generated deals / coupons still surface heuristic output as if it were real data, and candidate loading still scans too much data before trimming results.
4. [B.5][LOW][sources: B18/B42] Map UI behavior still needs cleanup: stale date-range chips, recompute races, aggressive auto-centering, first-expense area labels, one-part address destination loss, and remaining plaintext merchant logging.
5. [D.3][related location carry-over] Related current-tree location quick wins should be closed in this pass instead of fragmented later: fallback area-label selection, safe destination hint fallback, and any remaining location log-sanitization carry-overs that touch the same resolver / map stack.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/data/location/*`
- `app/src/main/java/com/yourname/expensetracker/domain/location/*`
- `app/src/main/java/com/yourname/expensetracker/domain/map/*`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/map/*`
- `app/src/main/java/com/yourname/expensetracker/domain/priceprotection/*`
- `app/src/test/java/com/yourname/expensetracker/**/*Location*`
- `app/src/test/java/com/yourname/expensetracker/**/*Map*`
- `app/src/test/java/com/yourname/expensetracker/**/*PriceProtection*`

### Approach
- Re-order resolver logic so cache / correction paths are tried before live device lookups, and so fallback behavior preserves retry semantics instead of collapsing them.
- Make area-key computation single-source-of-truth across DAO and repository code.
- Demote heuristic price-protection output behind explicit debug / provisional semantics unless backed by real data, and fix imported-receipt eligibility while doing so.
- Finish the map UI behavior sweep while the same view models and resolver outputs are already under test.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Location*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Map*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*PriceProtection*"`

### Done when
- Resolver ordering and fallback semantics are explicit and deterministic.
- Spatial bucketing no longer disagrees across repository and DAO layers.
- Price-protection output is honest about data provenance and uses the correct receipt timestamps.
- Map screens stop fighting the user with stale state or aggressive recentering.

---

## Batch 9: Categorization / Intelligence / Dedupe Convergence (owns open B.10 backlog + dedupe D/C entries)
### Issues
1. [B.10][MEDIUM][sources: B38/B41] Merchant and categorization normalization still has several gaps: single-character business-prefix stripping is too aggressive, legal-suffix detection misses short tokens, Greeklish alias comparison still mixes normalized and raw forms, seeded merchant dictionaries still do not backfill existing installs, and merchant approval / rejection history still uses inconsistent cache keys.
2. [B.10][MEDIUM][sources: B41/B42 + related D.2/D.3] Duplicate-detection follow-through is still incomplete: cross-source duplicate checks still do not compare enough real transaction data, the 24-hour policy remains overly broad, candidate confidence still ignores time distance and merchant similarity, and remaining dedupe-related carry-overs should be unified here instead of leaving multiple partial policies in place.
3. [B.10][MEDIUM][sources: B41/B42/B38] Classifier / feature-extraction state is still inconsistent: retraining still leaves old vocabulary behind, notification features still use wall-clock time instead of source timestamps, cached categories still stay stale until restart, and the feature pipeline still extracts fields it never actually uses.
4. [B.10][LOW][sources: B38/B41] Low-severity challenge and keyword cleanup is still open: duration multiplication can still overflow, days remaining can go negative, challenge IDs should stop using wall-clock timestamps, duplicate keywords like `roasters` still need cleanup, punctuation-edge keywords still miss, fuzzy prefilters remain too strict, and BK-tree candidate selection still resolves equal-distance matches suboptimally.
5. [D.2/D.3][related categorization carry-over] The same batch should absorb remaining merchant / threshold / exchange-rate / utility issues that touch the same classifiers and normalizers: finite exchange-rate validation, `AppConfig` user-agent cleanup, `StringDistanceUtils` reuse, canonical long-name persistence, and any remaining current-tree anomaly-threshold or dedupe-utility carry-overs.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/domain/categorization/*`
- `app/src/main/java/com/yourname/expensetracker/domain/classification/*`
- `app/src/main/java/com/yourname/expensetracker/domain/deduplication/*`
- `app/src/main/java/com/yourname/expensetracker/domain/merchant/*`
- `app/src/main/java/com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/config/AppConfig.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/util/StringDistanceUtils.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/**/*Categor*`
- `app/src/test/java/com/yourname/expensetracker/domain/**/*Duplicate*`
- `app/src/test/java/com/yourname/expensetracker/domain/**/*Challenge*`

### Approach
- Finish normalization first: merchant keys, alias caches, Greeklish handling, and seeded dictionaries must agree before dedupe or classification tuning can be trusted.
- Collapse remaining dedupe policy fragments into one scoring and candidate-ranking contract that incorporates real transaction data, time distance, and merchant similarity.
- Make classifier state explicit: clear vocabulary on retrain, invalidate category caches on mutation, and either consume extracted features or stop extracting them.
- Sweep the low-severity challenge / keyword bugs while the same categorization and merchant utilities are already under test.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Categor*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Duplicate*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Merchant*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*SpendingChallengeManager*"`

### Done when
- Merchant normalization, seeded dictionaries, and correction-history caches all use one consistent key strategy.
- Duplicate detection uses real transaction comparisons and a single candidate-ranking policy.
- Classifier state no longer depends on stale cached categories or retained vocabulary.
- Challenge / keyword low-severity carry-overs are closed in the same engine pass.

---

## Batch 10: Email / Parser / Import Utility Hardening (owns open B.11 backlog + parsing D entries)
### Issues
1. [B.11][MEDIUM][sources: B31/B13] Email and receipt ingestion still has path-splitting problems: seeded merchant mappings still miss fuzzy fallback due to normalization drift, `ProcessReceiptUseCase` is still bypassed, and `EmailReceiptIngestionService` can still write `ScannedReceipt` and `EmailReceiptSource` in separate non-atomic steps.
2. [B.11][MEDIUM][sources: B43/B44/B31-missed] Parser correctness still has open edge cases: `CustomSplitParser` still validates raw doubles instead of cents / basis points, sub-cent split precision is still accepted, `GenericTransactionParser` still uses lenient calendar parsing, `GreekBankParser` still misses Latin direction codes, and Apple / Uber currency detection still depends on raw substring checks.
3. [B.11][MEDIUM][sources: B44/B31-missed] Amount and date parsing still needs a consolidated utility fix: SMS / Revolut grouped-amount regexes are still too narrow, Uber year inference should come from `receivedAt`, and related import utilities like clipboard amount parsing, comma-group validation, locale-root currency normalization, merchant stop-word stripping, and fixed-locale money formatting should be closed here as one parsing-contract sweep.
4. [B.11][MEDIUM][sources: B31 + related D.3/D.4] Formatter and input infrastructure still needs cleanup: `DateFormatterUtils` caches never evict and ignore locale in cache keys, `StringDistanceUtils` recompiles regexes unnecessarily, `AndroidSpeechInputGateway` still needs full permission / startup / error hardening, and haptic feedback should not rely on unsupported APIs on old SDKs.
5. [D.3][related synthesis / import carry-over] This batch should also absorb parser-adjacent current-tree carry-overs that touch the same utilities: canonical merchant-key grouping for recurring expenses, non-finite synthesis guards, and any remaining import-format utility issues not already owned by Batch 6.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/data/email/*`
- `app/src/main/java/com/yourname/expensetracker/domain/parser/*`
- `app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/*`
- `app/src/main/java/com/yourname/expensetracker/data/speech/AndroidSpeechInputGateway.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/util/AmountUtils.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/util/CurrencyNormalizer.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/util/DateFormatterUtils.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/util/MerchantCleaner.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/util/Money.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/util/HapticFeedback.kt`
- `app/src/test/java/com/yourname/expensetracker/**/*Parser*`
- `app/src/test/java/com/yourname/expensetracker/**/*Email*`

### Approach
- Unify parser utilities first so email, bank, SMS, clipboard, and generic import paths all reuse the same amount / date / currency normalization rules.
- Collapse the email receipt path back onto shared receipt-processing behavior so parser fixes do not drift between email and non-email ingestion.
- Replace lenient double-based split validation with cent- or basis-point-safe validation in any parser still storing money-like data.
- Treat speech / haptics / formatter utilities as part of the same user-input contract so fallback behavior is explicit on unsupported devices.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Parser*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Email*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*AmountUtils*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Speech*"`

### Done when
- Email and non-email receipt ingestion stop drifting on parsing and persistence behavior.
- Parser utilities handle grouped amounts, strict dates, and locale-root currency normalization consistently.
- Split and import utility validation stops relying on raw floating-point tolerances.
- Speech, haptics, and formatter utilities degrade safely on unsupported paths.

---

## Batch 11: Groups / Shared Expenses / Split Semantics Convergence (owns open B.12 backlog + group D/C entries)
### Issues
1. [B.12][MEDIUM][sources: B40/B11/B33-missed] Group validation and access-path semantics are still fragmented: add-member flows can still bypass coordinator validation, group-creation / archive races still exist above DB invariants, and the group subsystem still has parallel entity-repository and domain-port access paths that can drift.
2. [B.12][MEDIUM][sources: B40/B43] Shared-budget offset calculations still diverge from authoritative split semantics: pending reimbursement math uses mismatched concepts, `isExpenseFullySettled()` still falls back incorrectly, equal-split budget math still uses naive floating division, custom-percent and malformed-payload handling still diverges, and `calculateEffectiveBudgetSpend()` still accepts an unused `userId`.
3. [B.12][MEDIUM][sources: B40/B43/B12] Input and recurrence behavior still needs cleanup: blank / non-finite / non-positive inputs are still not rejected everywhere, `customSplitsJson` still is not actually canonical JSON, recurrence semantics still drift across manager / repository / reminders, and synthesis / block-party budget-limit inputs can still disagree on overall-vs-category budget resolution.
4. [B.12][LOW][sources: B40] Low-severity group cleanups remain open too: case-sensitive current-user detection, wall-clock timestamps instead of injected time, hardcoded `Dispatchers.IO`, personal-spend summation using raw `amount`, and direct data-layer imports leaking into domain code.
5. [Section C][sources: C.2 step 7 + related D.2/D.3 carry-over] This batch should explicitly close the remaining split-resolution dependency: one authoritative parser and one authoritative share-calculation pipeline should feed group creation, settlement, budget offsets, and any UI preview logic.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/domain/groups/*`
- `app/src/main/java/com/yourname/expensetracker/domain/groups/usecase/*`
- `app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/split/*`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/groups/*`
- `app/src/test/java/com/yourname/expensetracker/domain/**/*Group*`
- `app/src/test/java/com/yourname/expensetracker/domain/**/*Split*`

### Approach
- Collapse all group writes back behind one coordinator / domain-service path before adjusting share math.
- Make split parsing and share calculation authoritative in one place, then route settlement, budget-offset, and UI preview code through that one implementation.
- Replace stringly or pseudo-JSON split payloads with one canonical representation before tightening recurrence semantics.
- Sweep low-severity time / dispatcher / boundary leaks while the group stack is already being refactored.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Group*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*Split*"`
- `./gradlew.bat :app:testDebugUnitTest --tests "*SharedExpense*"`

### Done when
- Group creation and member-add flows cannot bypass coordinator validation.
- Budget-offset and settlement paths use the same authoritative split semantics.
- Split payloads have one canonical representation and recurrence semantics stop drifting.
- Remaining group low-severity boundary leaks are closed in the same pass.

---

## Batch 12: Cross-Cutting DAO / Domain Model / Dependency Closeout (owns Section C + unassigned D entries; likely migration-risk lane)
### Issues
1. [Section C][sources: C.2 + C.3] Remaining dependency entries must be converted into explicit closeout work instead of staying as advisory notes: sequential dependencies around period math, dedupe policy, domain / data boundaries, time injection, cancellation handling, split-resolution logic, and AI routing still need owning implementations or explicit verification closures; independent groups like analytics-engine consistency, financial-health KPI duplication, monthly-sweep dead widgets, merchant-analytics inconsistency, parser drift, and recommendation JSON-order dedupe also still need named owners.
2. [D.2/D.3][DAO / query carry-over] A large cross-cutting DAO backlog still remains outside the resolved B.4 lane: deterministic ordering for `LIMIT 1` readers, search / alias / fuzzy lookup indexing, `WarrantyDao` protected-value filters, per-category N+1 removal, `COALESCE` aggregation semantics, batched portfolio-history reads, mileage / budget-forecast / health-score indexing, `AiChatRepositoryImpl` transactional append, `ReturnWindowDao` one-to-one enforcement, `EmailReceiptSource.fingerprint` uniqueness, and other current-tree query / index / atomicity fixes that were never folded into B.4.
3. [B46/B47/D.2/D.3][domain boundary / model cleanup] Remaining domain / data boundary work is still open: `BlockPartyDay`, `DashboardExpenseMapper`, `DomainTransactionFilter`, `DashboardWidgetUiMapper`, duplicated `CategoryBreakdown` / `PeriodRange` model families, `FinancialForecast` / `MonteCarloBudgetImpact` UI-text leakage, `WidgetStyleConfig` validation, `BudgetStatusSnapshot` / `SpendingSummary` numeric-type drift, `DomainExpenseSummary.categoryName` semantics, and direct `R` access from domain generators.
4. [D.3][model invariant carry-over] Remaining low-level model invariants still need explicit guards: `PeriodRange end >= start`, non-negative planned expenses, non-zero / finite capture input amounts, value-object validation for notification / warranty / recurring-pattern models, `UpcomingItem.Recurring.id` stability, safe transfer-direction parsing, and removal of wall-clock defaults where value objects should stay pure.
5. [D.3/D.4][application / DI / lifecycle carry-over] Cross-cutting infrastructure cleanup is still open too: `ExpenseTrackerApp` should use injected application scope, field-injected singletons should become lazy or provider-backed, modules that provide singletons but still construct implementations manually should be normalized, `LifecycleObserver.onStop()` should stop canceling long-lived singletons by default, `SavingsModule` should depend on domain abstractions, and low-level utility safety like `floorMod` hash handling or BK-tree mutex reads should be closed here.
6. [verification lane] Section D contains likely stale duplicates of already-closed final-review work. This batch must explicitly verify current source state, annotate / close stale carry-overs, and only implement the items that still reproduce in the current tree.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/*`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/*`
- `app/src/main/java/com/yourname/expensetracker/data/repository/*`
- `app/src/main/java/com/yourname/expensetracker/domain/model/*`
- `app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/*`
- `app/src/main/java/com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/mappers/DashboardWidgetUiMapper.kt`
- `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt`
- `app/src/main/java/com/yourname/expensetracker/di/*`
- `app/src/androidTest/java/com/yourname/expensetracker/data/database/*Migration*`
- `app/src/test/java/com/yourname/expensetracker/domain/model/*`
- `app/src/test/java/com/yourname/expensetracker/data/database/*`

### Approach
- Treat Section C as an execution contract: each dependency entry must either become code in this or an earlier batch, or be explicitly closed as no longer applicable after current-tree verification.
- Split this batch into micro-batches during execution if schema changes emerge; keep new migrations isolated from pure refactors and query fixes.
- Consolidate remaining DAO / query / index work by root table or repository so one migration / DAO touch can close multiple D-carry-over items safely.
- Move boundary and invariant cleanup into domain-safe DTOs / value objects before tightening persistence or mapper assumptions.
- End with a registry sweep that updates stale duplicate entries instead of silently leaving them open after code has already addressed the root cause.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest`
- `./gradlew.bat :app:lintDebug`
- `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.DatabaseMigrationTest,com.yourname.expensetracker.data.database.MigrationContractTest`

### Done when
- Every remaining Section C dependency entry has a concrete owning implementation or an explicit verified closeout.
- Remaining DAO / query / index carry-overs are either fixed or explicitly ruled out as stale duplicates.
- Domain models stop leaking persistence types, UI text, or wall-clock defaults across boundaries.
- Any new migration work is isolated, validated, and documented instead of mixing with no-schema refactors.

---

## Cross-Batch Risks
- **Section D contains stale duplicate carry-overs.** Each batch should verify current-tree behavior before coding so already-fixed roots are closed by annotation rather than reimplemented.
- **Batch 12 is the likely migration-risk lane.** If additional schema / index / uniqueness work is still required, isolate it into micro-batches and run migration tests before merging.
- **Batches 3, 7, and 11 share financial semantics.** Period math, effective-amount rules, split semantics, and budget resolution must not diverge again while fixes land in parallel.
- **Batches 2, 4, and 10 share parser / sanitization contracts.** Provider parsing, receipt parsing, and import utilities should reuse common helpers instead of reintroducing variant behavior.
- **Batch 1 depends on clear currency policy.** Decide early whether UI should render home currency, transaction currency, or context-dependent currency before sweeping remaining hardcoded surfaces.
- **Section C items are not optional documentation.** They should be treated as cross-batch acceptance gates, not just notes.

## Final Completion Criteria
- [ ] The 2 remaining B.9 HIGH issues are explicitly assigned to Batch 1 and closed there.
- [ ] All remaining open B.1-B.12 MEDIUM / LOW registry items are assigned to Batches 1-11.
- [ ] All still-relevant Section C dependency entries are assigned explicit ownership and closeout behavior, primarily in Batch 12 plus earlier owning batches.
- [ ] All Section D quick wins are either assigned to an owning batch or explicitly closed as stale duplicates after current-tree verification.
- [ ] Any newly discovered migration / schema risk is isolated to Batch 12 micro-batches and validated with migration tests.
- [ ] The plan is saved at `docs/plans/PLAN-REMAINING-PHASE2-FIXES-V2.md`.
