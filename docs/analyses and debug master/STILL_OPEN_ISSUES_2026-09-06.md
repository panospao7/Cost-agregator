# Still-Open Issues — Definitive List (post cross-validation)

> **Generated:** 2026-09-06 · Basis: full cross-validation of all trackers against HEAD `d1fa9c68` (2026-09-03) + uncommitted working-tree changes. See `CROSS_VALIDATION_REPORT_2026-09-06.md` for method and full fixed-issue evidence.
> **Status semantics:** every item below was **code-verified** on the date above. "PARTIAL" = primary fix landed, documented residual remains. "OPEN" = no fix in code. Unit tests were **not executed** (per repo Gradle-coordination rules); test references are existence checks only.
> **Count:** 7 OPEN + 31 PARTIAL + 1 STALE/obsolete + at-risk uncommitted work.

---

## 1. OPEN issues (no fix in code)

| # | ID | Sev | Area | What is missing | Evidence |
|---|---|---|---|---|---|
| O-1 | P5-CURRENT-009 | — | Dashboard block-party | "Actual" column and UNDER/OVER_BUDGET status sum raw `effectiveAmount` across currencies; caller passes raw (unnormalized) `ctx.expenseEntities` | `SynthesisEngine.kt:560-568`; caller `ComputeDashboardWidgetsUseCase.kt:679`; normalized fallback exists (:662-665) but raw sum takes precedence (:566-568) |
| O-2 | NEW-P6-015 | P3 | Cashflow | `isIncomePattern()` hardcoded `return false` (TODO) → recurring income patterns counted as expenses; no income rule source in pattern provider | `CashFlowCalculator.kt:647-651,386-389`; `MergedRecurringPatternsProvider.kt:23-24` |
| O-3 | P7-P1-05 | P1 | Backup/restore | Post-restore **semantic-equivalence verification** (dashboard/analytics outputs equal pre-restore). Counts are captured (`liveCountsBeforeCopy`) but never compared | `BackupVerifier.kt:20-26` explicit TODO; `DatabaseBackupRepositoryImpl.kt:593-606` |
| O-4 | P8-P1-12 | P1 | Privacy UX | Unified privacy-denied UI model; only `AssistantViewModel` handles `PrivacyDenied` today | grep of `ui/` — no shared denied-state model |
| O-5 | P10-P1-02 | P1 | Bank connections | No OAuth state/PKCE; `initiateConnection` returns bare demo URL; entity has no OAuth session fields. Possibly intentionally stubbed (`isStubMode = BuildConfig.DEBUG`) — decide and mark | `BankApiIntegration.kt:128`; `BankConnection.kt:31-59` |
| O-6 | P10-P1-09 | P1 | Bank sync | Per-transaction commit loop, no outer sync transaction / per-item import rows. Mitigated by STRICT_EXTERNAL_ID idempotent keys + durable run ledger (retries safe), but partial-failure semantics remain | `BankApiIntegration.kt:229-381,527-534` |
| O-7 | P12-P1-04 | P1 | Export | Snapshot-consistent export — `export_snapshot_rows` "NOT yet implemented (P12-P1-04 / PR-SNAP)"; `rowCount` header is a separate query vs streamed rows | `ExportDataRepository.kt:29-42`; `ExportOptionsViewModel.kt:159-169,678-686` |

---

## 2. PARTIAL fixes (residual documented)

### 2.1 Universal (3)

- **U-MONEY-01** — merged recurring patterns retain original currency (TODO P2-20, `ForecastInputAssembler.kt:231-234`; pass-through at :252-254); conversion-failure fallback to raw amounts in block-party day buckets (`SynthesisEngine.kt:333,345,478,554`). *Next action:* merge patterns in home currency; drop raw fallbacks.
- **U-BARRIER-02** — write barrier is call-site-based (worker checkpoint `WorkerExecutionGuard.kt:270-283`, CI gate `WriteBarrierArchitectureGuardTest`), not a Room-level interceptor; exempt sites bypass by design. Concrete gap: `refreshToken`'s `updateToken` write (`BankApiIntegration.kt:428`) relies on an entry check only.
- **U-PRIVACY-03** — retention purge (10 targets) and export anonymization (9 sinks) landed; remainder = post-restore semantic equivalence (= O-3).

### 2.2 Pipeline 1 — Notification capture (1)

- **NEW-P1-011** (P3) — service consolidated onto `domain/common/Hashing.kt`, but `RawNotificationFingerprint.kt:26` keeps a duplicate SHA-256/hex impl, documented as pinned to the MIGRATION_104_105 backfill format. *Next action:* accept + document, or extract shared hex-format helper.

### 2.3 Pipeline 2 — Transaction lifecycle (4)

- **NEW-P2-005** — `DefaultExpenseCategoryAssignmentService.kt:29-44` is transactional/barrier-checked/audited, but still bypasses coordinator lifecycle: no before/after snapshots, no planner dispatch (no budget-check/anomaly side effects). Consumed by `ReceiptLinkService.kt:296`.
- **NEW-P2-006** — audited `deleteAllNotifications` exists; deprecated event-less `deleteAll()` (`NotificationRepository.kt:251-258`) still wipes notifications+expenses with no audit event. No production callers — delete it.
- **NEW-P2-008** — `updateMerchantForMerchant` (leaves dedupeKey stale) still exists at `ExpenseDao.kt:337`; `@RestrictedExpenseDaoMutation`, zero production callers — delete it.
- **NEW-P2-016** — timeout-protected `resolveHomeCurrency()` (`CurrencySettingsRepository.kt:70-85`) exists, but hot paths still call raw `homeCurrency().first()` with no timeout: `TransactionLifecycleCoordinator.kt:470` (create) and `:868` (update), plus ~15 sites (e.g. `ExecuteFinancialQueryUseCase.kt:67`, `BudgetAutopilotEngine.kt:232`).

### 2.4 Pipeline 3 — Receipts (2)

- **P3-P1-05** — `ReceiptRepository.insertReceipt` (`ReceiptRepository.kt:506-519`) only WARNING-deprecated; production caller `WarrantyTrackerRepository.kt:725`. (`BankStatementLifecycleProcessor` bypass is intentional + barrier-guarded.)
- **P3-P1-07** — `ProcessReceiptUseCase.kt:26` retains `?: "EUR"` null-fallback; rest of codebase migrated to fail-closed "XXX".

### 2.5 Pipeline 4 — Recurring rules (0 partial; hygiene)

- No partials. **P4-P1-05** marked STALE (occurrence key already embeds `sourceType|sourceId`, `RecurringOccurrenceExpander.kt:147-154` — the deferred design is moot). **Hygiene:** `RecurringLifecycleFixesTest.kt` is an empty `@Ignore` stub referencing removed APIs — the old regression suite is dead; replace with current-API tests.

### 2.6 Pipeline 5 — Dashboard/synthesis (4)

- **NEW-P5-005** — see U-MONEY-01.
- **NEW-P5-009** — `MoneyAggregateBuilder.kt:42-49` warns on count/bucket mismatch but still defaults missing counts to 0.
- **NEW-P5-012** — `StaleRatePolicy` policy-based, but `CurrencyConverter.kt:86-87` hard-codes 24h with explicit TODO (not AppConfig-configurable).
- **NEW-P5-013** — unknown bucket type still returns `MoneyAggregate.empty` (now with warning) — fail-loud, not fail-correct.

### 2.7 Pipeline 6 — Budget/forecast (3)

- **P6-P1-13** — balance-provider abstraction + honest `NET_CASHFLOW_ESTIMATE` labeling exist, but the only implementation is still a 90-day net-cashflow estimate; no real balance source.
- **NEW-P6-010** — thresholds are named constants with TODO for AppConfig (`FinancialStressForecastEngine.kt:76-81`) — still not tunable.
- **NEW-P6-013** — canonical `SpendingPaceCalculator` returns NO_BASELINE sentinel, but the forecast-pipeline copy `ForecastInputAssembler.kt:361-365` still emits `pacePercentage = 0f`.

### 2.8 Pipeline 7 — Backup/export/restore (0 partial)

- Only O-3 open. Everything else verified fixed.

### 2.9 Pipeline 8 — Privacy/retention/cloud-AI (1 + debt)

- **NEW-P8-006** — only *transient* retention-purge failures trigger retry (`DataRetentionWorker.kt:231-235`); permanent failures still return `Result.success`.
- **Debt (not a tracked ID):** `RawPersistencePolicyResolver` implements the per-source matrix but has **no production callers** — each write site implements the logic inline (`NotificationProcessingPipeline.kt:816-824`, `BankStatementLifecycleProcessor.kt:286-288`). Consolidate or delete to prevent drift.

### 2.10 Pipeline 9 — Workers (0 partial)

- 27/27 fixed. Registry only needs its NEW-P9-008 row corrected (PARTIAL → FIXED).

### 2.11 Pipeline 10 — Bank sync (3)

- **P10-P1-03** — durable run ledger exists (`OperationRunRecorder`); **no persisted incremental cursor per connection** (`since` param never stored, `BankApiIntegration.kt:161`).
- **P10-P1-07** — processor re-checks barrier before every write; `refreshToken`'s `updateToken` (:428) does not.
- **P10-P1-08** — dedupe aligned via shared `DuplicateDetectionPolicy` constants but duplicated code; shared `BankTransactionDeduper` still "planned" (`BankStatementLifecycleProcessor.kt:99-101`).
- **Test debt:** no `BankTokenCipher` test for the `KeyInvalidated` path; no determinism test for seeded mock RNG (:566).

### 2.12 Pipeline 11 — Email receipts (3; two fixed only by UNCOMMITTED changes)

- **NEW-P11-002/003** — parser-level `canParse` sender gates landed, but `detectProvider` routes by *body* keywords without sender check (`EmailReceiptIngestionService.kt:333-336`), and the unknown-provider fallback calls parsers directly, bypassing `canParse` (:355-362). Pinned as known-deferred by an uncommitted test.
- **NEW-P11-005** — Amazon double-escaped regexes fixed (committed); **the identical defect in `AppleReceiptParser.kt` is fixed only by the uncommitted working-tree change** (NEW-P11-2026-001/002, +43/-11 src, +281 test lines).

### 2.13 Pipeline 12 — Export/accounting (7, mostly feature-completeness)

- **P12-P0-01** — CSV import pipeline + roundtrip tests exist; wired only into the debug screen, no user-facing entry.
- **P12-P1-02** — dataset-level validation, not tied to the exact streamed rows.
- **P12-P1-03** — multi-currency audit columns present; `conversionStatus` still not exported.
- **P12-P1-05** — encryption fail-closed + privacy-gated; plaintext still the default and `EXPENSE_EXPORT` unconditionally Allowed (`ExportPrivacyGate.kt:41-42`).
- **P12-P1-06** — 26-col CSV / ~25-field JSON; attachments, tags, recurring-series linkage still absent.
- **P12-P1-07** — receipt provenance (source links) exported; no receipt file content.
- **P12-P1-08** — business/tax fields in generic exports; Xero/IIF/FreshBooks fixed headers omit them (`AccountingExporters.kt:54-56,98,150`).

---

## 3. At-risk uncommitted work (commit or lose)

| File(s) | Content | Tag |
|---|---|---|
| `service/NotificationFilter.kt` (+ `NotificationFilterTest.kt`) | bare `"pos"` removed from expense-signal keywords (substring-matched inside "deposited"); replaced with whole-word `\bpos\b` regex. Changes pinned P1-PR3 behavior: "Deposit fee €2.50" now denied | NEW-P1-2026-001 |
| `data/email/provider/AppleReceiptParser.kt` (+ `AppleReceiptParserTest.kt`, `EmailReceiptIngestionServiceTest.kt`) | un-escapes double-escaped raw-string regexes (Apple orderNumber was always null at HEAD); sender-gated `canParse`; residual-routing pin test | NEW-P11-2026-001/002 |

---

## 4. Cross-cutting debt

1. **U-PR1 enforcement** is a unit-test architecture guard (`CancellationSafetyArchitectureGuardTest`, with a must-shrink structured allowlist) — no detekt rule (`SuspendFunctionBroadCatch` absent). Decide whether CI-grade enforcement is required.
2. **No TOCTOU regression test** for the U-PR2 inline pattern (beforeSnapshot-inside-transaction is code-verified only).
3. **Docs:** all 12 pipeline registries + `PIPELINE_ISSUES_MASTER_TRACKER.md` are stale (many statuses wrong in both directions); `MASTER_ISSUE_TRACKER.md` status layer ~2 months stale; ENGINE tracker mostly accurate.

---

## 5. Suggested execution order

1. Commit uncommitted P1/P11 fixes (§3) — zero-risk, prevents loss.
2. O-3 (P7-P1-05) — restore-safety closure; comparator over already-captured counts.
3. U-MONEY-01 cluster (O-1 + partials) — normalize merged patterns, remove raw fallbacks.
4. O-7 (P12-P1-04) — snapshot export (PR-SNAP).
5. Wrong-place remnants: P3-P1-07 EUR, NEW-P2-016 timeout routing.
6. O-2 (NEW-P6-015) income patterns; P10 partials (cursor, token-write barrier re-check, shared deduper); NEW-P8-006; P11 detectProvider sender gate.
7. O-4 (privacy-denied UX), O-5/O-6 decisions, P12 feature partials, debt items §4.
