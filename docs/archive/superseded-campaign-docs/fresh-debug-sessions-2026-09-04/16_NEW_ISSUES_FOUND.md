# New Issues & Regressions Found — 2026-09-04 (Consolidated Register)

Everything this session found that was **not previously tracked**. All verdicts spot-verified against `d1fa9c68`. Full per-issue detail lives in the per-pipeline docs (§4) and `15_REGRESSION_SCAN_RECENT_REFACTORS.md`.

Severity: CRITICAL = data loss / privacy leak / guaranteed failure. MAJOR = wrong behavior or a real regression. MINOR = real but low-risk.

---

## 1. Systemic finding (highest priority)

### SYS-2026-01 — `e.message` persisted into durable diagnostics (CRITICAL-class privacy cluster, 11+ sites)
The repo's own law (AGENTS.md privacy rules, `SENSITIVE_DIAGNOSTICS_POLICY.md`) forbids persisting exception messages; the PII guard reports 0 violations because it only scans `e.message` adjacent to log/print calls — **data-flow into persisted events/journals is outside its detection scope**.

| Site | Evidence |
|---|---|
| Post-commit side-effect failure events | `TransactionSideEffectPlanner.kt:186,222,258,303,340,381(,418,455,489,618)` → `PostCommitActionRunnerImpl.kt:95,103` → `TransactionSideEffectFailureEventWriter.kt:59` (`reason = reason.take(200)` persisted into `TransactionLifecycleEvent`) |
| Restore journal + OperationRun ledger (`resetDatabase`) | `DatabaseBackupRepositoryImpl.kt:2388,2438,2450` |
| Raw-notification persisted JSON (also malformed JSON) | `NotificationCaptureService.kt:558` — `"{\"error\": \"${e.message}\"}"` |
| Dead code (still law-relevant) | `AccountingExportRepository.exportExpenses` returns raw `e.message` (`:206-211`) |
| Durable audit reason | P2 findings: `NEW-P2-2026-002`, `NEW-P1-2026-002` |

Origin: pre-existing (oldest 2026-05-17), **missed by the June PII burn-down** (`92a6ebf7` sanitized the restore path but not the reset path in the same file). Fix pattern exists in-repo: `92a6ebf7`'s typed constants + class-name-only capture (`TransactionSideEffectFailureEventWriter` already stores `reasonClass` correctly at `:57` — the uncontrolled `reason` string is the leak).

### SYS-2026-02 — "Fix in the sibling file" pattern (MAJOR, process)
The double-escape bug fixed in Amazon parser → then found still live in Uber parser (fixed `25c636e1`) → **still live in Apple parser** (`NEW-P11-2026-001`). Same class: negative-number CSV fix fixed in one sanitizer path, formula injection survived elsewhere until `25c636e1`. There is no guard/test forcing sibling parsers to share the escaping implementation.

### SYS-2026-03 — Fix-in-dead-path confirmed twice (MAJOR, process)
`ProcessReceiptUseCase` (`?: "EUR"`, zero production callers — the "fixed" OCR currency path) and `AccountingExportRepository.exportExpenses` (zero callers) are dead code whose fixes don't protect production. The in-repo detection (`NEW-P5-012` claimed fixed by a commit whose diff never touched the file) shows false fix-claims survive because no check ties claims to diffs.

---

## 2. MAJOR issues

| ID | Description | Evidence | Origin |
|---|---|---|---|
| NEW-P1-2026-001 | `"pos"` substring in notification filter matches inside "de**pos**it", defeating the deposit-deny rule → salary-deposit notifications captured as raw text | `NotificationFilter.kt:103` vs deny rules `:236-250` | pre-existing |
| NEW-P3-2026-001 | Stale receipt snapshot read outside transaction, full-row rewrite inside → lost-update TOCTOU on DIRECT_SAVE/unlink (concurrent matchStatus/suggestedExpenseId silently lost) | `ReceiptLinkService.kt:168→252, 382→411` | pre-existing (U-PR2 covered only TransactionLifecycleCoordinator) |
| NEW-P4-2026-001 | `calculatePlannedVsActualReport` sums raw Doubles without currency grouping → mixed-currency drift after a rule's currency changes | `RecurringLifecycleCoordinator.kt:1180-1247` | pre-existing |
| NEW-P7-2026-001 | `resetDatabase()` persists raw `e.message` into on-disk restore journal and OperationRun ledger | `DatabaseBackupRepositoryImpl.kt:2388/2438/2450` | pre-existing (part of SYS-2026-01) |
| NEW-P10-2026-001 | **Regression:** `refresh()` awaits an infinite `collect` → `finally` never runs → `isLoading` stuck true, permanent `CircularProgressIndicator`, collector leak. Latent (no UI call site today) | `BankConnectionsViewModel.kt:75-87`; introduced by `d40c230b` (CI PR A) | **regression** |
| NEW-P10-2026-002 | `BankConnectionDao.updateSyncStatus` has zero callers; `lastSync` never persisted → `shouldSync()` always true → sync-frequency gating is dead code | `BankConnectionDao.kt:42-43`; `BankApiIntegration.kt:461-474` | pre-existing |
| NEW-P11-2026-001 | AppleReceiptParser ORDER_ID/ITEM patterns are double-escaped in Kotlin raw strings → never match → `orderNumber` always null → same-hour same-amount Apple receipts falsely dedup (data-loss vector) | `AppleReceiptParser.kt:37-41,55` (`\\.`/`\\s` inside `"""…"""`) | pre-existing (sibling of fixed Uber/Amazon bugs) |
| NEW-P11-2026-002 | `canParse` too broad: generic subject "receipt"/"invoice" + body "app store" routes non-Apple mail to Apple parsing; confidence 0.85 > 0.75 auto-creates wrong-merchant/currency expenses | `AppleReceiptParser.kt:78-91` | pre-existing |
| NEW-REG-2026-001 | Guard fail-open: raw-runCatching allowlist expiry test only asserts non-null (`assertNotNull`), never that it is in the past; all 12 entries expire 2026-10-01 and will silently pass; per-file matching admits new raw `runCatching` in those files | `CancellationSafetyArchitectureGuardTest.kt:406-413` vs `:352-357` | **regression-class** (guard bug from `f652218f`/`65c265fb` wave) |
| NEW-REG-2026-002 | PII guard detection blind spot: `verify_pii_logging_boundaries.py` Rule 1 only flags `e.message` adjacent to log/print; data-flow into events/journals/outcomes unscanned; allowlist `[]` makes "0 violations" a false comfort | `scripts/verify_pii_logging_boundaries.py` (Rule 1) | pre-existing (process) |

## 3. MINOR issues

| ID | Description | Evidence |
|---|---|---|
| NEW-P1-2026-002 | `e.message` into persisted extrasJson (notification capture) | NotificationCaptureService (see SYS-2026-01) |
| NEW-P1-2026-003 | CE swallowed in `sanitizePendingReviewText` | NotificationCaptureService |
| NEW-P1-2026-004 | AI_AUTO_ACCEPT audit correlation uses a random UUID (uncorrelatable) | NotificationCaptureService |
| NEW-P2-2026-001 | Plain `runCatching` (no CE rethrow) on 5 best-effort event inserts, inconsistent with rethrowing siblings | `TransactionLifecycleCoordinator.kt:321,343,515,652,691` vs `:392-395` |
| NEW-P2-2026-002 | `e.message` in durable audit reason | P2 audit path |
| NEW-P2-2026-003 | `resolveHomeCurrency` CE swallow + `e.message` | P2 |
| NEW-P3-2026-002 | Dead `ReceiptSideEffectDispatcher` + stale KDoc; STORE_RAW hardcode | ReceiptSideEffectDispatcher |
| NEW-P3-2026-003 | EUR (`:26` dead use case) vs XXX (`ReceiptRepository.kt:427-432` production) fallback inconsistency | ProcessReceiptUseCase |
| NEW-P4-2026-002 | Expense-snapshot TOCTOU residual | RecurringLifecycleCoordinator |
| NEW-P4-2026-003 | RULE_*/materializer events written via direct `lifecycleEventDao.insert` vs LEGAL_PATHS "must use RecurringLifecycleEventWriter" | RecurringLifecycleCoordinator |
| NEW-P5-2026-001 | `Duration.toDays()` truncation inflates avgPerDay up to ~3% across DST months | `TotalsAggregationEngine.kt:553,609` (introduced `8c2289ef`, pre-T) |
| NEW-P5-2026-002 | Dead app-calendar `weekKey()` conflicts with ISO week keys | `TotalsAggregationEngine.kt:513-515` |
| NEW-P5-2026-003 | runway NO_INCOME label contradicts positive budget-derived runway | `ComputeDashboardWidgetsUseCase.kt:623-627` |
| NEW-P6-2026-001 | `System.currentTimeMillis()` duration diagnostics | `FinancialStressForecastEngine.kt:91,160` |
| NEW-P6-2026-002 | Calendar debt (~46-51 sites) in SynthesisEngine forecast day math (DST-safe today, unmigrated; closes when `gr-00-local` sweep lands) | `SynthesisEngine.kt` |
| NEW-P6-2026-003 | Silent drop in `monthlyRecurringTotal`, no failure counter | `SynthesisEngine.kt:277-286` |
| NEW-P6-2026-004 | `?: raw` mixed-currency silent fallbacks in block-party path | `SynthesisEngine.kt:478,554` |
| NEW-P7-2026-002 | Stale BAK-N1 KDoc | DatabaseBackupRepositoryImpl |
| NEW-P7-2026-003 | Internal test constructor re-wires stale Room opener | test file |
| NEW-P8-2026-001 | Retention checkpoint cleared before transient-retry throw | `DataRetentionWorker.kt:69-115` area |
| NEW-P8-2026-002 | `e.message` into persisted extrasJson under STORE_RAW (part of SYS-2026-01) | `NotificationCaptureService.kt:558` |
| NEW-P8-2026-003 | Stale retention-gap header | docs/comment |
| NEW-P9-2026-001 | Daily briefing artifact gated on notification permission (permission gating a non-notification artifact) | briefing worker |
| NEW-P9-2026-002 | Worker start failures always classified Retry | worker guard path |
| NEW-P9-2026-003 | Notification-permission revocation cancels bill reminder permanently | BillReminderWorker |
| NEW-P9-2026-004 | Briefing `notificationsSent` over-count | DailyBriefingWorker |
| NEW-P10-2026-003 | `TOKEN_REAUTH_REQUIRED` durable signal exists but is discarded at coordinator level → still no re-auth prompt (NEW-P10-002 residual) | `BankConnectionLifecycleCoordinator.kt:43-45` |
| NEW-P10-2026-004 | Stale contradicting comments | BankConnectionsViewModel |
| NEW-P11-2026-003 | Silent USD/EUR parser defaults | AppleReceiptParser |
| NEW-P12-2026-001 | Raw `e.message` in dead AccountingExportRepository (SYS-2026-01 member) | `AccountingExportRepository:206-211` |
| NEW-P12-2026-002 | CancellationException swallowed in ExportOptionsViewModel | `ExportOptionsViewModel.kt:363-368` |
| NEW-REG-2026-003 | Cancellation cause chain discarded (`throw CancellationException("Sync cancelled")`) | `BankConnectionsViewModel.kt:47,67` |
| NEW-REG-2026-004 | CashFlowCalculator emits one extra partial day for non-day-aligned windows (behavior delta, arguably a fix) | `CashFlowCalculator.kt:144,275` (`1364aec1`) |
| NEW-REG-2026-005 | Pre-existing DST/millis leftovers: dashboard daily buckets `dayIndex*DAY_IN_MILLIS`, streak `± DAY_MS`, receipt matcher `lookbackDays*86400000`, TotalsAggregation fixed-millis dayEnd | `ComputeDashboardWidgetsUseCase.kt:672`, `SpendingChallengeManager.kt:55,68-73`, `ReceiptTransactionMatcher.kt:84-87`, `TotalsAggregationEngine.kt:568` |

**Totals: 1 systemic privacy cluster (11+ sites) + 2 process patterns + 10 MAJOR + ~35 MINOR.**

## 4. What was checked and found CLEAN

- T1–T4C time migration: 0 real regressions (see doc 15) — the `getEndOfDay` flip fear was false; all consumers verified.
- `bb2a6f18` gate-repair commit: no test/guard weakening found.
- MIT-031/041 atomicity wrappers: no dangerous `withTransaction` nesting; allowlist changes only shrink.
- PR18–24 cancellation rethrow: no caller found that relied on the old swallowing behavior for control flow (one MINOR exception: NEW-REG-2026-002-CE → logged as CE-into-bare-catch above).
- Worker guard contract (P9): sound at HEAD, including the timeout-retry gap the May audit flagged (closed by `7076d730`).
