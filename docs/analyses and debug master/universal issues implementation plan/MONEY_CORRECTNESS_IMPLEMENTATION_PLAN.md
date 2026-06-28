# Money Correctness Implementation Plan

Last updated: 2026-06-16  
Scope: MIT-050, MIT-051, MIT-052, MIT-053, MIT-054, MIT-068, MIT-076  
Goal: prevent mixed-currency/raw amount bugs across dashboard, analytics, forecast, cashflow, recurring, budget, export/import-adjacent paths, and legacy recurring data.

---

## 1. Executive Summary

Do **not** fix money issues screen-by-screen only.

The bugs share the same root causes:

- raw `amount`, `effectiveAmount`, or `Double` values are being summed where normalized money is required,
- dashboard DTOs lose ownership/shared-expense flags,
- recurring forecast/cashflow lacks explicit income/expense direction,
- forecast quality states do not clearly distinguish exact, estimated, stale, unsupported, and no-baseline cases,
- calendar/date iteration can be wrong around DST/week/year boundaries,
- legacy recurring/planned data may contain raw `Double`, default `EUR`, or old occurrence keys.

The fix should create a **money boundary architecture**:

> Raw transaction amounts may enter the system, but all aggregation, dashboard, budget, forecast, export/accounting, and analytics calculations must use typed, normalized, quality-aware money values.

---

## 2. Master Issues Covered

| MIT | Issue | Covered Here |
|---|---|
| MIT-050 | Fix dashboard normalized money calculations | Yes |
| MIT-051 | Propagate shared-expense flags into dashboard income logic | Yes |
| MIT-052 | Fix runway and aggregate count correctness | Yes |
| MIT-053 | Fix recurring forecast/cashflow currency and direction | Yes |
| MIT-054 | Fix budget/stress forecast quality states and rate basis | Yes |
| MIT-068 | Forecast calendar/risk-threshold hardening | Yes |
| MIT-076 | Recurring legacy schema and money defaults audit | Yes |

Related but not owned:

| MIT | Relationship |
|---|---|
| MIT-033 | DB uniqueness for recurring linked actuals supports forecast correctness |
| MIT-047/MIT-048/MIT-080 | Import/export field contract must preserve money fields |
| MIT-055/MIT-072 | Export/accounting schema must expose money conversion meaning |
| MIT-078 | Migration framework required for legacy recurring backfill |
| MIT-003/MIT-017 | Static guards and CI enforce money boundaries |

---

## 3. Affected Pipelines

| Pipeline | Impact |
|---|---|
| P4 | Recurring actual links, reminders, planned rows |
| P5 | Dashboard, Block Party, analytics, runway, aggregate counts |
| P6 | Budget, forecast, cashflow, stress forecast |
| P12 | Export/accounting money semantics |
| P13 | Legacy schema/migrations/defaults |
| P18 | Import field defaults and currency provenance |

---

## 4. Current Problem Summary

Known findings:

- Block Party actuals use raw `effectiveAmount` instead of normalized daily spending.
- `DashboardExpense` loses `isSharedExpense`, allowing shared repayment deposits to inflate income.
- Runway can show `NO_INCOME` even when budget-backed runway exists.
- `MoneyAggregateBuilder` count mismatch can undercount failed transactions.
- Recurring forecast/cashflow paths have mixed-currency gaps.
- Recurring income is unsupported or misclassified as expense.
- `CashFlowCalculator.isIncomePattern()` effectively returns false.
- Stress forecast may be shown like balance forecast when it is only net-cashflow estimate.
- Stale stress patterns are logged but not surfaced.
- Budget snapshot rate basis is unclear.
- No-baseline pace uses `0f`, which can be displayed as real 0%.
- Risk thresholds are constants/TODOs.
- Cashflow day iteration uses calendar logic vulnerable to DST/date bucket issues.
- `WEEK_OF_YEAR` usage needs verification.
- Legacy recurring occurrence keys/default currency/raw `Double` fields need audit/backfill.

---

## 5. Architecture Decision

### Decision 1 — Raw amounts are never aggregate inputs

Raw values may be stored as source data:

- original transaction amount,
- original currency,
- merchant/source-provided amount,
- imported CSV amount,
- bank statement amount.

But raw values must not be used for:

- dashboard totals,
- Block Party actuals,
- income/spending charts,
- budget pace,
- cashflow,
- forecast,
- accounting totals,
- runway,
- stress forecast,
- recurring projection totals.

All aggregation must use a typed normalized value.

---

### Decision 2 — Every aggregate has a reporting currency

Every query/aggregate must declare:

```text
reportingCurrency
rateBasis
conversionQuality
```

Examples:

- dashboard monthly totals in current home currency,
- accounting export in configured report currency,
- budget snapshot in budget currency,
- forecast in forecast reporting currency.

---

### Decision 3 — Money quality is part of the result

Calculations must not silently return precise-looking numbers when data is estimated or incomplete.

Every money aggregate should carry quality:

```text
EXACT
NORMALIZED
ESTIMATED_RATE
STALE_RATE
MISSING_RATE
PARTIAL
UNSUPPORTED
NO_BASELINE
```

The UI should show this as status/warning/tooltip, not as fake exact values.

---

### Decision 4 — Recurring forecast requires explicit direction

Recurring patterns/occurrences must carry or derive:

```text
INCOME
EXPENSE
TRANSFER_IN
TRANSFER_OUT
NEUTRAL/UNSUPPORTED
```

If direction cannot be determined, forecast should surface `UNSUPPORTED_RECURRING_DIRECTION`, not treat it as expense.

---

### Decision 5 — Date buckets use `LocalDate` and explicit `ZoneId`

No business day loop should use raw milliseconds or mutable `Calendar` unless wrapped by a tested date utility.

Use:

```text
LocalDate
ZoneId
YearMonth
WeekFields
```

with documented inclusive/exclusive boundaries.

---

## 6. Non-Negotiable Invariants

After this plan:

- [ ] No dashboard/analytics/forecast code sums raw mixed-currency amounts.
- [ ] Block Party actuals use normalized `dailySpending` or equivalent typed aggregate.
- [ ] Shared repayment deposits do not inflate income.
- [ ] `DashboardExpense` carries ownership/shared flags needed by income logic.
- [ ] Runway status cannot show `NO_INCOME` when budget-backed runway exists.
- [ ] Aggregate counts count included, excluded, failed, skipped, and partial rows correctly.
- [ ] Recurring income is either supported or explicitly marked unsupported.
- [ ] Forecast/cashflow never silently mixes currencies.
- [ ] Stress forecast clearly labels net-cashflow estimate vs real balance projection.
- [ ] Stale/missing rate and stale-pattern warnings reach result/UI.
- [ ] No-baseline pace is nullable/`N/A`, not `0f`.
- [ ] Forecast day/week/month loops are DST/week/year safe.
- [ ] Legacy recurring raw `Double` / default `EUR` risks are audited and migrated or documented.
- [ ] Static guards reject raw money sums and hardcoded fallback currency in calculation/import paths.

---

# 7. Target Money Model

## 7.1 Core Types

Recommended conceptual types:

### `OriginalMoney`

Represents source/original amount.

Fields:

- amount decimal/minor units,
- currency,
- source,
- timestamp,
- provenance.

Use for display/source detail only, not aggregation.

---

### `NormalizedMoney`

Represents amount converted to reporting currency.

Fields:

- amount decimal/minor units,
- reporting currency,
- original amount/currency,
- rate used,
- rate timestamp,
- rate source,
- conversion status,
- quality flags.

---

### `MoneyAggregate`

Represents aggregate totals.

Fields:

- total normalized money,
- reporting currency,
- included count,
- excluded count,
- failed count,
- missing-rate count,
- stale-rate count,
- partial count,
- quality,
- warnings.

---

### `MoneyCalculationContext`

Required for every aggregate.

Fields:

- reporting currency,
- user home currency at calculation time,
- date range,
- rate basis,
- timezone,
- inclusion policy,
- ownership/shared policy,
- forecast mode if applicable.

---

### `MoneyQualityWarning`

Examples:

- `MISSING_RATE`
- `STALE_RATE`
- `MIXED_CURRENCY_INPUT`
- `UNSUPPORTED_RECURRING_INCOME`
- `NO_BASELINE`
- `NET_CASHFLOW_ESTIMATE_ONLY`
- `LEGACY_DEFAULT_CURRENCY`
- `PARTIAL_DATA`

---

## 7.2 Rate Basis Modes

Define explicitly:

| Rate Basis | Use |
|---|---|
| `TRANSACTION_DATE` | Historical spending/dashboard/accounting |
| `SNAPSHOT_DATE` | Budget snapshot/report at a given date |
| `LATEST_AVAILABLE` | Future forecast estimate |
| `USER_FIXED` | User-provided/manual forecast assumption |
| `SOURCE_PROVIDED` | Bank/import-provided rate if trusted |
| `UNKNOWN` | Must surface warning/unsupported |

---

## 7.3 Inclusion Policies

Aggregates should require explicit inclusion rules:

```text
includeSharedRepayments: yes/no
includeNotMine: yes/no
includeTransfers: yes/no
includePending: yes/no
includeFailed: yes/no
includeRefundsAsNegativeExpense: yes/no
includeIncome: yes/no
```

No dashboard or forecast should infer these inconsistently.

---

# 8. Implementation Phases

---

## Phase 0 — Money Boundary Inventory

### Goal

Find every raw money calculation path.

### Tasks

- [ ] Inventory all uses of `amount`, `effectiveAmount`, `baseAmount`, `originalAmount`.
- [ ] Inventory all uses of `Double` for money.
- [ ] Inventory all `sumOf`, `average`, `reduce`, and manual aggregation over expense amounts.
- [ ] Inventory hardcoded currency fallbacks like `EUR`.
- [ ] Inventory dashboard mappers and DTOs.
- [ ] Inventory forecast/cashflow calculators.
- [ ] Inventory recurring/planned/manual money entities.
- [ ] Inventory import/export money field mappings.
- [ ] Inventory exchange-rate lookup paths.
- [ ] Inventory date bucket loops.
- [ ] Create `docs/money/MONEY_BOUNDARY_INVENTORY.md`.

### Useful searches

```bash
rg "effectiveAmount|originalAmount|baseAmount|amount" app/src/main/java
rg "sumOf|average|fold|reduce" app/src/main/java
rg "Double|Float" app/src/main/java | rg "amount|money|price|rate|budget|forecast"
rg "\"EUR\"|Currency\\.EUR|defaultCurrency" app/src/main/java
rg "Calendar|WEEK_OF_YEAR|DAY_OF_YEAR|currentTimeMillis" app/src/main/java
rg "dailySpending|Block Party|Runway|CashFlow|Forecast|Recurring" app/src/main/java
```

### Acceptance Criteria

- [ ] Every raw money aggregate is known.
- [ ] Every hardcoded currency fallback is known.
- [ ] Every forecast date loop is known.
- [ ] Every DTO that drops money/ownership metadata is known.

---

## Phase 1 — Money Calculation Policy

### Goal

Document the rules before changing calculations.

### Tasks

- [ ] Create `docs/money/MONEY_CALCULATION_POLICY.md`.
- [ ] Define raw vs normalized money.
- [ ] Define aggregation rules.
- [ ] Define rate basis rules.
- [ ] Define dashboard inclusion policy.
- [ ] Define shared repayment policy.
- [ ] Define recurring income/expense direction policy.
- [ ] Define budget/forecast quality-state policy.
- [ ] Define calendar/date-bucket policy.
- [ ] Define legacy recurring/default currency policy.

### Acceptance Criteria

- [ ] Developers can tell which money value is legal for each calculation.
- [ ] UI can tell whether a result is exact, estimated, partial, or unsupported.

---

## Phase 2 — Static Money Guard

### Goal

Prevent recurrence of raw mixed-currency bugs.

### Tasks

- [ ] Extend `verify_money_boundaries.py`.
- [ ] Block `sumOf { it.effectiveAmount }` outside allowlisted normalized contexts.
- [ ] Block summing raw `amount`/`Double` in dashboard/analytics/forecast.
- [ ] Block hardcoded fallback `EUR` in import/recurring/forecast paths unless explicitly documented.
- [ ] Block `Calendar`/`WEEK_OF_YEAR` in forecast/cashflow date loops unless allowlisted.
- [ ] Block new money DTOs that omit required currency/quality metadata if guardable.
- [ ] Add fixtures/tests for each rule.
- [ ] Require owner/reason/expiry for allowlist.

### Acceptance Criteria

- [ ] Block Party-style raw aggregate fixture fails.
- [ ] Hardcoded EUR fallback fixture fails.
- [ ] Calendar date-loop fixture fails.
- [ ] Normalized aggregate fixture passes.

---

# 9. Dashboard / Analytics Fixes

---

## Phase 3 — Block Party Normalized Actuals

### Problem

Block Party actuals use raw `effectiveAmount` over normalized daily history.

### Tasks

- [ ] Identify Block Party calculation source.
- [ ] Replace raw `effectiveAmount` sum with normalized `dailySpending`.
- [ ] Ensure `dailySpending` has reporting currency and rate quality.
- [ ] Define behavior for missing/stale rates.
- [ ] Add mixed-currency tests:
  - EUR + USD,
  - missing rate,
  - stale rate,
  - refund,
  - shared repayment.
- [ ] Add static guard.

### Acceptance Criteria

- [ ] Block Party actuals are currency-normalized.
- [ ] Quality warnings are surfaced.
- [ ] No raw `effectiveAmount` aggregate remains.

---

## Phase 4 — Dashboard Shared/Ownership Flags

### Problem

`DashboardExpense` loses `isSharedExpense`, allowing shared repayment deposits to enter income.

### Tasks

- [ ] Add required fields to dashboard DTO:
  - `isSharedExpense`,
  - `isNotMine` if applicable,
  - `transactionType`,
  - `source`,
  - `isRepayment` or derived repayment classification,
  - ownership/share amount if needed.
- [ ] Update mappers.
- [ ] Update dashboard income logic.
- [ ] Define shared repayment behavior:
  - excluded from income by default,
  - optionally shown as reimbursement/offset category.
- [ ] Add tests:
  - shared repayment deposit,
  - salary income,
  - refund,
  - transfer,
  - not-mine expense,
  - group split.
- [ ] Ensure UI labels are clear.

### Acceptance Criteria

- [ ] Shared repayments do not inflate income.
- [ ] Dashboard mapper preserves ownership metadata.
- [ ] Income logic distinguishes salary/income from reimbursement/transfer/refund.

---

## Phase 5 — Runway and Aggregate Counts

### Problems

- Runway can show `NO_INCOME` even with budget-backed runway.
- `MoneyAggregateBuilder` count mismatch can undercount failed transactions.

### Tasks

- [ ] Fix runway branch order.
- [ ] Define runway source priority:
  1. real income data,
  2. budget-backed runway,
  3. forecast estimate,
  4. no income/no baseline.
- [ ] Add typed runway status:
  - `REAL_INCOME_BASED`,
  - `BUDGET_BACKED`,
  - `FORECAST_ESTIMATE`,
  - `NO_INCOME`,
  - `NO_BASELINE`,
  - `INSUFFICIENT_DATA`.
- [ ] Update `MoneyAggregateBuilder` counters:
  - included,
  - excluded,
  - failed,
  - skipped,
  - duplicate,
  - missing rate,
  - stale rate,
  - partial.
- [ ] Add tests for count consistency and status branches.

### Acceptance Criteria

- [ ] Budget-backed runway never displays `NO_INCOME`.
- [ ] Aggregate counts match input row classifications.
- [ ] Failed rows are not silently undercounted.

---

# 10. Recurring / Forecast / Cashflow Fixes

---

## Phase 6 — Recurring Direction Model

### Problem

Recurring income is unsupported/misclassified.

### Tasks

- [ ] Add or derive recurring direction:
  - income,
  - expense,
  - transfer,
  - neutral/unsupported.
- [ ] Update recurring pattern/occurrence DTOs.
- [ ] Update `CashFlowCalculator.isIncomePattern()`.
- [ ] Update forecast synthesis to respect direction.
- [ ] If legacy row lacks direction:
  - infer safely where possible,
  - otherwise mark unsupported with quality warning.
- [ ] Add tests:
  - salary recurring income,
  - rent recurring expense,
  - transfer between accounts,
  - refund-like recurring,
  - unknown legacy row.

### Acceptance Criteria

- [ ] Recurring income increases cashflow.
- [ ] Recurring expense decreases cashflow.
- [ ] Unknown direction is not treated as expense silently.

---

## Phase 7 — Recurring Currency Normalization

### Problem

Recurring forecast/cashflow can mix currencies.

### Tasks

- [ ] Normalize recurring pattern/occurrence amounts before forecast synthesis.
- [ ] Require reporting currency in forecast context.
- [ ] Define rate basis for future recurring rows:
  - latest available rate,
  - user fixed assumption,
  - source-provided rate,
  - unsupported if missing.
- [ ] Surface stale/missing rate warnings.
- [ ] Add mixed-currency recurring tests.
- [ ] Ensure generated planned rows carry conversion status.

### Acceptance Criteria

- [ ] Forecast/cashflow totals are in one reporting currency.
- [ ] Missing/stale rates produce quality warnings.
- [ ] Raw recurring `Double` values are not directly summed.

---

## Phase 8 — Recurring Legacy Schema and Defaults Audit

### Problem

Legacy recurring/planned/manual entities may contain old `occurrenceKey`, raw `Double`, or default `EUR`.

### Tasks

- [ ] Audit recurring/planned/manual tables/entities.
- [ ] Identify raw `Double` money fields.
- [ ] Identify default currency values.
- [ ] Identify old `occurrenceKey` formats.
- [ ] Determine whether existing installs can contain legacy keys.
- [ ] Add migration/backfill if needed.
- [ ] Add compatibility resolver if migration is unsafe.
- [ ] Add tests with legacy rows:
  - old occurrence key,
  - missing currency,
  - default EUR row,
  - raw Double amount,
  - mixed-currency legacy projection.
- [ ] Document remaining legacy behavior.

### Acceptance Criteria

- [ ] Legacy recurring rows cannot collide or corrupt forecast.
- [ ] Legacy money defaults cannot silently introduce EUR into normalized projections.
- [ ] Migration/backfill or documented unsupported policy exists.

---

# 11. Budget / Stress Forecast / Quality States

---

## Phase 9 — Forecast Quality-State Model

### Problems

- Stale stress patterns are only logged.
- Stress forecast can be mislabeled as balance forecast.
- No-baseline pace uses `0f`.

### Tasks

- [ ] Define forecast quality result model:
  - value,
  - status,
  - warnings,
  - confidence,
  - unsupported reasons.
- [ ] Surface stale stress patterns to result/UI without PII.
- [ ] Label stress forecast:
  - `NET_CASHFLOW_ESTIMATE`,
  - `BALANCE_PROJECTION`,
  - `BUDGET_SCENARIO`,
  - `INSUFFICIENT_DATA`.
- [ ] Make no-baseline pace nullable or `N/A`.
- [ ] Update UI to avoid rendering `0f` as real 0%.
- [ ] Add tests for no baseline/stale/unsupported states.

### Acceptance Criteria

- [ ] UI does not present unknown/estimated values as exact.
- [ ] Stress forecast label matches data source.

---

## Phase 10 — Budget Snapshot Rate Basis

### Problem

Budget snapshot rate basis is unclear.

### Tasks

- [ ] Define budget snapshot reporting currency.
- [ ] Define rate basis:
  - transaction date,
  - snapshot date,
  - budget creation date,
  - latest available.
- [ ] Store or expose rate basis in snapshot metadata.
- [ ] Surface stale/missing rate warnings.
- [ ] Add tests:
  - budget in home currency,
  - budget with foreign-currency spending,
  - stale exchange rate,
  - missing exchange rate,
  - home currency change.

### Acceptance Criteria

- [ ] Budget snapshot totals have explicit rate basis.
- [ ] Missing/stale rates do not produce silent exact totals.

---

## Phase 11 — Risk Threshold Configuration

### Problem

Stress/risk thresholds are constants/TODOs.

### Tasks

- [ ] Inventory all hardcoded risk thresholds.
- [ ] Decide product approach:
  - fixed documented constants,
  - user settings,
  - remote/config file,
  - per-budget category thresholds.
- [ ] Create `RiskThresholdConfig`.
- [ ] Add defaults with documentation.
- [ ] Add tests for threshold boundaries.
- [ ] Surface config version in forecast diagnostics if useful.

### Acceptance Criteria

- [ ] Thresholds are not unexplained TODO constants.
- [ ] Boundary behavior is tested.

---

# 12. Calendar / Date-Bucket Correctness

---

## Phase 12 — Replace Fragile Date Loops

### Problems

- Cashflow daily loop uses `Calendar`.
- DST/date buckets may be wrong.
- `WEEK_OF_YEAR` issue is unverified.

### Tasks

- [ ] Replace business date loops with `LocalDate` / `ZoneId`.
- [ ] Use `YearMonth` for monthly budgets.
- [ ] Use `WeekFields` for week calculations with explicit locale/policy.
- [ ] Define inclusive/exclusive date ranges.
- [ ] Run `rg WEEK_OF_YEAR`.
- [ ] Replace or justify every use.
- [ ] Add tests:
  - DST start,
  - DST end,
  - leap day,
  - month boundary,
  - year boundary,
  - ISO week 1,
  - locale-specific week start if supported.

### Acceptance Criteria

- [ ] Forecast/cashflow buckets are stable across DST/week/year edges.
- [ ] No unverified `WEEK_OF_YEAR` business logic remains.

---

# 13. Import / Export / Accounting Interface

This plan does not own import/export, but money correctness depends on it.

### Required handoff requirements

Import/export plan must preserve or explicitly report:

- original amount,
- original currency,
- base/home/reporting amount,
- conversion rate used,
- conversion status,
- stale/missing rate state,
- transaction type,
- payment method,
- shared/not-mine flags,
- recurring direction if imported/exported.

### Tasks here

- [ ] Define money field meanings for import/export contract.
- [ ] Add tests that exported money fields match money calculation policy.
- [ ] Ensure accounting totals use normalized/reporting currency.
- [ ] Ensure CSV import cannot silently default missing currency to EUR.

### Acceptance Criteria

- [ ] Import/export cannot silently break money correctness.

---

# 14. Testing Strategy

## 14.1 Money Unit Tests

- [ ] same-currency aggregate,
- [ ] mixed-currency aggregate,
- [ ] missing exchange rate,
- [ ] stale exchange rate,
- [ ] refund,
- [ ] transfer,
- [ ] shared repayment,
- [ ] not-mine expense,
- [ ] failed transaction,
- [ ] duplicate/skipped row,
- [ ] no baseline,
- [ ] budget-backed runway.

---

## 14.2 Dashboard Tests

- [ ] Block Party uses normalized daily spending.
- [ ] shared repayment excluded from income.
- [ ] salary included as income.
- [ ] refund handled by policy.
- [ ] transfer excluded or classified correctly.
- [ ] aggregate counts are correct.

---

## 14.3 Forecast / Cashflow Tests

- [ ] recurring salary income,
- [ ] recurring rent expense,
- [ ] recurring transfer,
- [ ] mixed-currency recurring items,
- [ ] missing future rate,
- [ ] stale future rate,
- [ ] unsupported legacy direction,
- [ ] stress forecast net-cashflow label,
- [ ] stale stress pattern warning.

---

## 14.4 Calendar Tests

- [ ] DST spring forward,
- [ ] DST fall back,
- [ ] leap day,
- [ ] month-end,
- [ ] year-end,
- [ ] ISO week-year boundary,
- [ ] configured week start.

---

## 14.5 Legacy Migration/Compatibility Tests

- [ ] old occurrence key backfill,
- [ ] duplicate occurrence key conflict,
- [ ] missing currency legacy recurring row,
- [ ] default EUR legacy row,
- [ ] raw Double legacy projection,
- [ ] mixed-currency legacy forecast.

---

## 14.6 Static Guard Tests

Each guard needs:

- [ ] positive fixture,
- [ ] negative fixture,
- [ ] allowlisted fixture,
- [ ] expired allowlist fixture.

---

# 15. Static Guards Required

- [ ] raw `effectiveAmount` aggregate guard,
- [ ] raw `amount` / `Double` aggregate guard,
- [ ] hardcoded currency fallback guard,
- [ ] dashboard DTO flag-loss guard if feasible,
- [ ] forecast/cashflow `Calendar`/`WEEK_OF_YEAR` guard,
- [ ] recurring raw money field guard for new code,
- [ ] import missing-currency fallback guard,
- [ ] money result without quality metadata guard if feasible.

---

# 16. Rollout PR Plan

## PR 1 — Money Boundary Inventory and Policy

Includes:

- inventory doc,
- money calculation policy,
- rate basis policy,
- shared income policy,
- recurring direction policy.

Acceptance:

- [ ] Calculation rules are documented before code changes.

---

## PR 2 — Money Static Guard

Includes:

- raw sum guard,
- hardcoded currency guard,
- calendar guard,
- fixtures/tests.

Acceptance:

- [ ] New raw mixed-currency sums fail CI.

---

## PR 3 — Dashboard Normalization

Includes:

- Block Party actuals use normalized `dailySpending`,
- mixed-currency tests,
- quality warnings.

Acceptance:

- [ ] MIT-050 can close.

---

## PR 4 — Dashboard Shared/Runway/Aggregate Counts

Includes:

- dashboard DTO shared flags,
- income exclusion rules,
- runway branch/status fix,
- aggregate count fix.

Acceptance:

- [ ] MIT-051 and MIT-052 can close.

---

## PR 5 — Recurring Direction and Currency

Includes:

- recurring direction model,
- `isIncomePattern()` fix,
- normalized recurring forecast/cashflow,
- mixed-currency tests.

Acceptance:

- [ ] MIT-053 can close.

---

## PR 6 — Forecast Quality States and Budget Rate Basis

Includes:

- quality model,
- stale warnings,
- net-cashflow estimate label,
- no-baseline `N/A`,
- budget snapshot rate basis.

Acceptance:

- [ ] MIT-054 can close.

---

## PR 7 — Calendar/Date Bucket Hardening

Includes:

- replace fragile date loops,
- `WEEK_OF_YEAR` audit,
- DST/week/year tests.

Acceptance:

- [ ] MIT-068 can close.

---

## PR 8 — Legacy Recurring Money Audit/Backfill

Includes:

- old occurrence key audit,
- raw Double/default EUR audit,
- migration/backfill or policy,
- legacy tests.

Acceptance:

- [ ] MIT-076 can close.

---

## PR 9 — Import/Export/Accounting Money Interface

Includes:

- handoff contract with import/export plan,
- accounting normalized totals tests,
- missing currency default prevention.

Acceptance:

- [ ] Money correctness is preserved at import/export boundary.

---

## PR 10 — Final Regression Suite and Tracker Update

Includes:

- end-to-end dashboard/forecast mixed-currency tests,
- docs updates,
- tracker closing SHAs.

Acceptance:

- [ ] All money MITs have evidence and tests.

---

# 17. Edge Cases

## Shared repayment looks like income

Expected:

- excluded from income,
- optionally displayed as reimbursement/offset,
- does not improve runway as salary income.

## Missing exchange rate

Expected:

- aggregate is partial/unsupported depending on context,
- warning surfaced,
- no fake exact total.

## Future recurring salary in foreign currency

Expected:

- normalized using explicit future rate basis,
- quality indicates estimate if latest rate used.

## Legacy recurring row has no currency

Expected:

- migrate/backfill if source known,
- otherwise mark unsupported/legacy warning,
- no silent EUR assumption.

## No baseline budget pace

Expected:

- value is `N/A`/null,
- UI does not show 0%.

## DST transition day

Expected:

- one logical `LocalDate` bucket,
- no duplicate/missing day.

---

# 18. Documentation Requirements

Create/update:

```text
docs/money/MONEY_BOUNDARY_INVENTORY.md
docs/money/MONEY_CALCULATION_POLICY.md
docs/money/MONEY_AGGREGATE_QUALITY_POLICY.md
docs/money/DASHBOARD_MONEY_POLICY.md
docs/money/RECURRING_FORECAST_MONEY_POLICY.md
docs/money/BUDGET_RATE_BASIS_POLICY.md
docs/money/DATE_BUCKET_POLICY.md
docs/money/LEGACY_RECURRING_MONEY_AUDIT.md
docs/testing/MONEY_CORRECTNESS_REGRESSION_TESTS.md
```

Update:

```text
docs/MASTER_ISSUE_TRACKER.md
docs/MASTER_ISSUE_TRACKER_SUPPLEMENT_2.md
```

---

# 19. Metrics

| Metric | Target |
|---|---|
| Raw mixed-currency sums | 0 |
| Hardcoded fallback currency in calculation/import paths | 0 |
| Dashboard DTOs missing ownership flags | 0 |
| Forecast totals without reporting currency | 0 |
| Money aggregates without quality metadata | 0 |
| Calendar date loops in business forecast paths | 0 |
| Unverified `WEEK_OF_YEAR` use | 0 |
| Legacy recurring rows with unsafe default currency | 0 or documented/migrated |
| Money static guard failures on main | 0 |

---

# 20. Definition of Done by MIT

## MIT-050

- [ ] Block Party actuals use normalized `dailySpending`.
- [ ] Mixed-currency dashboard tests pass.
- [ ] Raw money sum guard blocks recurrence.

## MIT-051

- [ ] Dashboard model carries `isSharedExpense` / ownership flags.
- [ ] Shared repayments do not inflate income.
- [ ] Income classification tests pass.

## MIT-052

- [ ] Runway branch order/status fixed.
- [ ] Budget-backed runway is not shown as `NO_INCOME`.
- [ ] Aggregate counts include failed/skipped/partial rows correctly.

## MIT-053

- [ ] Recurring forecast/cashflow normalizes currencies.
- [ ] Recurring income direction is supported or explicitly unsupported.
- [ ] `CashFlowCalculator.isIncomePattern()` behavior is corrected.
- [ ] Mixed-currency recurring tests pass.

## MIT-054

- [ ] Stale stress patterns surface to result/UI.
- [ ] Stress mode labels net-cashflow estimate vs real balance.
- [ ] Budget snapshot rate basis is documented/implemented.
- [ ] No-baseline pace is `N/A`, not `0f`.

## MIT-068

- [ ] Risk thresholds are config/documented constants.
- [ ] `Calendar` day loops replaced or justified.
- [ ] `WEEK_OF_YEAR` audited/fixed.
- [ ] DST/week/year tests pass.

## MIT-076

- [ ] Legacy recurring occurrence keys audited/backfilled or documented.
- [ ] Raw Double/default EUR risks audited.
- [ ] Legacy mixed-currency recurring tests pass.
- [ ] Migration/compatibility behavior is explicit.

---

# 21. Final Completion Checklist

This plan is complete when:

- [ ] Money boundary inventory exists.
- [ ] Money calculation policy exists.
- [ ] Static money guard is blocking in CI.
- [ ] Dashboard uses normalized aggregates.
- [ ] Shared repayment income bug is fixed.
- [ ] Runway and aggregate count bugs are fixed.
- [ ] Recurring direction/currency is fixed.
- [ ] Budget/forecast quality states are implemented.
- [ ] Calendar/date buckets are hardened.
- [ ] Legacy recurring money/defaults are audited and fixed/policy-handled.
- [ ] Import/export money field handoff is documented.
- [ ] Mixed-currency regression tests pass.
- [ ] Master tracker is updated with closing SHAs.

---

# 22. Recommended First Action

Start with:

```text
PR 1 — Money Boundary Inventory and Policy
```

Then:

```text
PR 2 — Money Static Guard
PR 3 — Dashboard Normalization
PR 4 — Dashboard Shared/Runway/Aggregate Counts
PR 5 — Recurring Direction and Currency
```

Do not patch only the visible dashboard bug first.  
Without the money boundary policy and static guard, the same mixed-currency/raw amount bugs will reappear in forecast, import/export, or analytics.