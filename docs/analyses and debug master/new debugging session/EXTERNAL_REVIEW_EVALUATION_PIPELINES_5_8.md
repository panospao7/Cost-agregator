# External Review Evaluation — Pipelines 5-8

> **Evaluated against:** HEAD `6b7132b7` (post all fixes)  
> **External review baseline:** `4113e38f` (pre-our-session)

---

## Pipeline 5 — Currency/Dashboard/Analytics

| Issue | Verdict | Notes |
|-------|---------|-------|
| P5-CURRENT-001: Historical uses midpoint not per-tx | **TRUE** — Our `getHomeCurrencyPurchaseTotalHistorical` uses period midpoint | Design tradeoff: per-tx is expensive. Midpoint is acceptable approximation. |
| P5-CURRENT-002: Historical fallback to latest without warning | **TRUE** — No RATE_BASIS_FALLBACK warning | P2 enhancement |
| P5-CURRENT-003: getRate orders by lastUpdated not validDate | **TRUE** — Could pick old validDate if imported recently | P2 — edge case |
| P5-CURRENT-004: Weekly/daily transaction counts zero | **TRUE** — MoneyAggregateBuilder called without transactionCounts | P2 |
| P5-CURRENT-005: Weekly/daily/monthly use latest-rate | **TRUE** — By design for current-valuation drilldown | Documented as acceptable |
| P5-CURRENT-006: Monthly totals type-agnostic | **TRUE** — Includes deposits/transfers | P1 — needs purchase-only variant |
| P5-CURRENT-007: Category breakdown vs summary FX basis mismatch | **TRUE** — Different rate bases | P1 — architectural |
| P5-CURRENT-008: Spending trend raw-sums | **FALSE (FIXED)** — We added currency conversion in `computeSpendingTrend` | Fixed in commit `d97fb918` |

**Remaining P1 items:** P5-CURRENT-006 (monthly type-agnostic), P5-CURRENT-007 (category/summary FX mismatch)  
**Remaining P2 items:** P5-CURRENT-001/002/003/004 (rate basis, fallback warnings, transaction counts)

---

## Pipeline 6 — Budget/Forecast/Cashflow

| Issue | Verdict | Notes |
|-------|---------|-------|
| P6-CURRENT-001: Budget limit vs spend different FX bases | **TRUE** — Limit uses period-end, spend uses latest | P1 — architectural |
| P6-CURRENT-002: BudgetMonitor adjusted-spend not from repository | **TRUE** — adjustedSpendBreakdown only in ViewModel | P1 |
| P6-CURRENT-003: Budget alert wrong currency | **PARTIALLY FIXED** — We added displayCurrency param but default still uses budget.currency | P2 — needs explicit status.currency pass |

**Remaining P1 items:** P6-CURRENT-001 (FX basis mismatch), P6-CURRENT-002 (adjusted spend not in repository)

---

## Pipeline 7 — Backup/Restore

| Issue | Verdict | Notes |
|-------|---------|-------|
| P7-CURRENT-001: WAL/SHM sidecars not deleted during restore | **TRUE** — restoreCostBackup doesn't delete old sidecars | **P0 — Needs fix** |
| P7-CURRENT-002: Failed rollback exits to NORMAL | **TRUE** — Defeats fail-closed | **P0 — Needs fix** |
| P7-P0-02: Startup recovery resumes writes after failed recovery | **PARTIALLY FIXED** — We made journal atomic + commit(), but next-restart recovery gap remains | P1 |
| P7-P1-01: Stale Room after swap | **TRUE** — Known, mitigated by forced restart | Documented limitation |
| P7-P1-03: Backup no SQLite backup API | **TRUE** — Uses file copy after checkpoint | P2 — acceptable with BACKUP_EXPORTING mode |

**Remaining P0 items:** P7-CURRENT-001 (WAL sidecars), P7-CURRENT-002 (failed rollback)

---

## Pipeline 8 — Privacy/AI/Redaction

| Issue | Verdict | Notes |
|-------|---------|-------|
| P8-CURRENT-001: redactBeforeCloud not authoritative | **FALSE (FIXED)** — We wired EffectiveCloudAiPolicyResolver into CloudAiPrivacyGate | Fixed in commit `d97fb918` |
| P8-CURRENT-002: DO_NOT_STORE falls through | **FALSE (FIXED)** — We separated processing/storage payloads with exhaustive when() | Fixed in commit `621a06b5` |
| P8-CURRENT-003: Redacted storage breaks parsing | **FALSE (FIXED)** — Parser now receives ephemeral text, DB stores sanitized | Fixed in commit `621a06b5` |
| P8-CURRENT-004: Bank statement raw OCR ignores privacy | **FALSE (FIXED)** — We injected RawContentSanitizer into BankStatementLifecycleProcessor | Fixed in commit `621a06b5` |
| P8-P1-06: Retention scope incomplete | **PARTIALLY FIXED** — We added AI artifact + email purge, but chat/diagnostics remain | P2 |
| P8-P1-08: Purpose-aware redaction | **PARTIALLY FIXED** — We added per-purpose rules but no PreparedCloudPayload contract | P2 |

**All P0 privacy items from Pipeline 8: FIXED ✅**

---

## Summary of Remaining Issues (Pipelines 5-8)

### P0 Critical (2 items — Pipeline 7 only):
1. **P7-CURRENT-001**: WAL/SHM sidecars not deleted during restore swap
2. **P7-CURRENT-002**: Failed rollback exits maintenance to NORMAL

### P1 High (4 items):
3. **P5-CURRENT-006**: Monthly totals include deposits/transfers (type-agnostic)
4. **P5-CURRENT-007**: Category breakdown vs summary use different FX bases
5. **P6-CURRENT-001**: Budget limit vs spend different FX bases
6. **P6-CURRENT-002**: BudgetMonitor adjusted-spend not from repository

### P2 Medium (8+ items):
- P5-CURRENT-001/002/003/004: Rate basis, fallback warnings, transaction counts
- P6-CURRENT-003: Budget alert currency
- P7-P0-02: Next-restart recovery gap
- P8-P1-06/08: Retention + redaction completeness
- Various quality/diagnostic gaps
