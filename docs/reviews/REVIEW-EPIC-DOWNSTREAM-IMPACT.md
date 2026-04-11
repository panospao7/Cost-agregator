# Epic Downstream Impact Review

## Purpose

This review consolidates the downstream Phase B impact of completed universal epics **A.1-A.10**.

It uses a **conservative annotation policy**:

- **RESOLVED BY EPIC** = direct, high-confidence fix match
- **LIKELY RESOLVED — VERIFY IN PIPELINE** = strong indirect evidence, but pipeline re-check still required
- **PARTIALLY IMPROVED — REMAINS OPEN** = one sub-problem improved but the broader pipeline issue still stands
- **UNAFFECTED** = no meaningful epic impact found
- **POTENTIALLY WORSENED / MONITOR** = plausible negative downstream effect; do not change registry unless evidence becomes firmer

## 1. High-confidence downstream issues resolved by epics

### A.3
- **B.1/B.6** proactive briefing day-key mismatch and worker event-time consistency — **RESOLVED BY A.3**
- **B.9/B.47** `DomainTransactionFilter.correlationId` nondeterministic default — **RESOLVED BY A.3**
- **B.10** challenge ID/timestamp nondeterministic default — **RESOLVED BY A.3**
- **B.12** group-expense timestamp defaults — **RESOLVED BY A.3**

### A.4
- **B.1/B.6/B.10** duplicate-policy drift across notification/statement/review flows — **RESOLVED BY A.4**
- **B.10** cross-source dedupe window/scoring drift — **RESOLVED BY A.4**
- **B.1** AI dedupe candidate transaction-type filtering — **RESOLVED BY A.4**

### A.5
- **B.2** `BudgetCalculator.calculatePeriodRange()` rolling-window / `+30 days` drift — **RESOLVED BY A.5**
- **B.2/B.32/B.48** stale dashboard expense and budget-status rollover windows — **RESOLVED BY A.5**
- **B.2/B.4** historical spending distribution DST/week-boundary drift — **RESOLVED BY A.5**
- **B.2/B.36** month-end analytics last-second drop — **RESOLVED BY A.5**
- **B.9** transaction filter date-chip/state bug — **RESOLVED BY A.5**

### A.7 / A.8
- **B.2** `BudgetMonitor` swallowed cancellation and unsynchronized singleton state — **RESOLVED BY A.7/A.8**
- **B.1/B.9** `SuggestCategoryFallbackUseCase` / `SuggestReceiptExtractionUseCase` swallowing `CancellationException` — **RESOLVED BY A.7**
- **B.6** `AnomalyAlertOrchestrator` swallowed cancellation — **RESOLVED BY A.7**
- **B.3** `WarrantyTextExtractor` shared formatter thread-safety bug — **RESOLVED BY A.8**
- **B.7** `AccountingExporters` shared formatter race — **RESOLVED BY A.8**
- **B.6** `RecommendationStateManager` stale refresh overwrite / wrong-user clear — **RESOLVED BY A.8**
- **B.6** `ServiceDiagnostics` unsynchronized counters — **RESOLVED BY A.8**

### A.9
- **B.2/B.7/B.8** hidden truncation in budgeting, cashflow, export, carbon, tax, and reporting readers — **RESOLVED BY A.9**
- **B.2** `SharedBudgetManager` truncation slice — **RESOLVED BY A.9**
- **B.7** repository-backed export truncation / repo-vs-UI divergence — **RESOLVED BY A.9**

### A.10
- **B.2/B.5/B.8/B.9/B.10** transaction-type blindness across spend-facing analytics/reporting — **RESOLVED BY A.10** where rows specifically concerned deposits/transfers/withdrawals being treated as spending
- **B.2** business-report spend semantics — **RESOLVED BY A.10**
- **B.5** map heatmap spend-input pollution — **RESOLVED BY A.10**
- **B.8** shared-expense deductible overstatement on the main tax/reporting spend path — **RESOLVED BY A.10**
- **B.8** `FinancialHealthCalculator` non-spend rows counted as spending — **RESOLVED BY A.10**
- **B.8** `RecurringIncomeTracker` spending-side ratio transaction-type blindness — **RESOLVED BY A.10**
- **B.9/B.10** challenge / UI / analytics transaction-type semantics called out in downstream audits — **RESOLVED BY A.10** only for the exact spend-filter defects, not broader pipeline logic

## 2. Likely resolved — verify in pipeline

### A.1 / A.2
- **B.9/B.46/B.47** dashboard/block-party boundary inflation from `DashboardExpense -> Expense` round-trip — **LIKELY RESOLVED BY A.2**, verify during B.9

## 3. Partially improved — remains open

### A.1 / A.2
- **B.2** `SharedBudgetManager` improved only for `amount` vs `effectiveAmount`; window/category/member semantics remain open
- **B.7** export/accountant reporting improved on ownership-adjusted totals only; truncation/currency/type/export-format issues remain open
- **B.9/B.46** block-party/dashboard boundary cleanup improved, but raw-amount sorting and legacy widget issues remain open

### A.3
- **B.10** feature extraction reproducibility improved by removing direct wall-clock dependence in part, but true event-time semantics remain open

### A.4
- **B.1** AI dedupe pipeline improved candidate quality, but single-candidate skip remains open
- **B.6** notification dedupe improved in main paths, but oversized-amount fallback still bypasses semantic duplicate checks

### A.5
- **B.2/B.6** broader budget/forecast coherence improved at the canonical period layer, but several forecast/autopilot/shared-budget issues remain open

### A.7 / A.8
- **B.6** `DailyBriefingWorker` cancellation handling improved, but timeout / non-cancellation failure semantics remain open
- **B.1** `HybridReceiptAssistService` metadata contamination improved, but full metadata-accuracy issue still needs pipeline verification

### A.9
- **B.2** shared-budget / forecast / autopilot semantic backlog is narrower after truncation fixes, but non-truncation logic issues remain open
- **B.7/B.8** reporting/tax paths improved on completeness, but currency, formatting, progressive-tax, and business-only VAT semantics still remain in places

### A.10
- **B.1/B.6** dedupe/notification families improved indirectly only where transaction-type candidate quality mattered; the core pipelines remain open
- **B.3** receipt-matching and warranty internals improved on spend semantics/thread safety in slices, but OCR/parser/vision/privacy backlog remains open
- **B.5** heatmap negative/refund normalization bug is narrower after removing non-spend inputs, but still remains open
- **B.6** notification oversize-fallback and recommendation-ordering families remain open
- **B.7** export/reporting improved on spend semantics, but export schema/format/currency issues remain open
- **B.8** VAT/business-only semantics and broader tax/savings/investment defects remain open
- **B.10** feature extraction reproducibility improved, but true event-time semantics and wider challenge/model issues remain open
- **B.11** email duplicate-key/type semantics improved, but the pipeline still remains open overall

## 4. Unaffected by epic work

- **A.6** did not clearly resolve any explicit Phase B registry issue; impact was limited to local numeric-fidelity cleanup
- Most **B.1** privacy/routing issues remain unaffected even after A.10
- Most **B.3** OCR/business-rule/privacy issues remain unaffected except the exact resolved slices called out above
- Most **B.4** schema/integrity/concurrency issues remain unaffected
- Most **B.5** structural geocoder/cache/bucketing issues remain unaffected
- Most **B.6** notification/privacy/persistence/deep-link issues remain unaffected
- Most **B.7** export schema/format/currency issues remain unaffected
- Most **B.8** tax/savings/investment structural issues remain unaffected
- Most **B.11** parsing/email-specific issues remain unaffected
- Most **B.12** group/share semantics remain unaffected except the timestamp-default slice

## 5. Potentially worsened / monitor

- **A.2** legacy dashboard health widget path may have regressed because one compatibility path now passes `expenses = emptyList()` rather than reconstructed expenses. Treat as **monitor**, not a registry rewrite yet.
- **A.10** sweep/savings semantics now more clearly diverge where downstream code still treats `WITHDRAWAL` as spending while the canonical domain rule is purchase-only. Treat as **monitor** until Phase B.8 addresses it.

## 6. Recommended registry annotation policy

When updating `MASTER-ISSUE-REGISTRY.md`:

1. Use **`[RESOLVED BY A.x]`** only for direct, high-confidence matches.
2. Use **`[LIKELY RESOLVED BY A.x — VERIFY IN B.n]`** for strong but indirect downstream improvements.
3. Use **`[PARTIALLY IMPROVED BY A.x — REMAINS OPEN]`** where one sub-problem is fixed but the pipeline issue is broader.
4. Leave unaffected rows unchanged.
5. Keep potential regressions in this review doc unless direct evidence becomes strong enough to justify a registry note.

## 7. Next-use guidance

- During each Phase B pipeline plan, re-check all rows marked **LIKELY RESOLVED** before spending implementation effort.
- If a future pipeline review confirms the behavior is gone, promote the row to **`[RESOLVED BY A.x]`** or mark it obsolete with explicit evidence.
- If a future pipeline review shows only a slice was fixed, retain the **PARTIALLY IMPROVED** label and scope the remaining work narrowly.
- Phase B planning should now assume A.1-A.10 are committed and available as the new baseline; do not reopen A-epic fixes unless a concrete regression is found.
