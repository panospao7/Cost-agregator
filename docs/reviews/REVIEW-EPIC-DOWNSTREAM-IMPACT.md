# Epic Downstream Impact Review

## Purpose

This review consolidates the downstream Phase B impact of completed universal epics **A.1-A.8**.

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

## 2. Likely resolved — verify in pipeline

### A.1 / A.2
- **B.2/B.7** business-expense ownership semantics moved from raw `amount` toward `effectiveAmount` — **LIKELY RESOLVED**, but surrounding pipeline semantics still need verification
- **B.8** shared-expense tax deduction overstatement — **LIKELY RESOLVED BY A.1**, verify during B.8
- **B.8** recurring-income raw-amount overstatement — **LIKELY RESOLVED BY A.1**, verify during B.8
- **B.10** challenge spend ownership semantics — **LIKELY RESOLVED BY A.1**, verify during B.10
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

## 4. Unaffected by epic work

- **A.6** did not clearly resolve any explicit Phase B registry issue; impact was limited to local numeric-fidelity cleanup
- Most **B.1** privacy/routing issues were unaffected by A.1-A.8
- Most **B.3** OCR/business-rule issues were unaffected by A.7/A.8 thread-safety fixes
- **A.9-style truncation issues** remain open and are the active current epic
- Most **B.5**, **B.11**, and **B.12** structural issues remain open except for the specific direct matches called out above

## 5. Potentially worsened / monitor

- **A.2** legacy dashboard health widget path may have regressed because one compatibility path now passes `expenses = emptyList()` rather than reconstructed expenses. Treat as **monitor**, not a registry rewrite yet.
- **A.1** cash-flow semantics may have been affected if a path that intentionally used raw account movement was changed to `effectiveAmount`. Treat as **monitor**, not a registry rewrite yet.

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
