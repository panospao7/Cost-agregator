# Cross-Validation Report — Pipeline & Universal Issue Trackers vs Codebase

> **Generated:** 2026-09-06
> **Verified against:** HEAD `d1fa9c68` (2026-09-03), branch `atomicity-pr21-enforcement-final`, **including uncommitted working-tree changes** (notably `NotificationFilter.kt`, `AppleReceiptParser.kt` + tests).
> **Method:** 13 parallel read-only audits (1 universal + 12 pipeline registries) + 1 master-tracker cross-check. Every verdict below is based on static code evidence (`file:line`), not on the trackers' claims. **Unit tests were NOT executed** — "tests exist" means the test files were located, not that they pass.
> **Registries audited:** `UNIVERSAL_ISSUE_TRACKER.md`, `PIPELINE_1..12_CONSOLIDATED_ISSUES.md`, plus cross-check of `MASTER_ISSUE_TRACKER.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `MASTER_ISSUE_TRACKER_SUPPLEMENT_2.md`, `ENGINE_ISSUES_MASTER_TRACKER.md`, `FIXED_CLAIMS_VALIDATION_AUDIT_v4.md`.

---

## 1. Executive summary

| Verdict | Count | Notes |
|---|---:|---|
| **FIXED** (code-verified) | **233** | Includes 1 verified NOT-A-BUG (U-SIDEEFFECT-01) and 3 items the docs claimed were *not* done but which have since landed (U-MONEY-03, U-PRIVACY-01, U-PRIVACY-02) |
| **PARTIALLY FIXED** | **31** | Primary fix present; a documented residual remains (see §4) |
| **NOT FIXED** | **7** | Genuinely open items (see §3) |
| **STALE / obsolete** | **1** | P4-P1-05 — the deferred "key collision" design is already structurally impossible |

**Total issues audited: 272.**

The dominant error direction across all trackers is **under-reporting**: roughly 70 items still marked OPEN / PARTIAL / TODO in the registries are verifiably fixed in code. The reverse error exists but is smaller and concentrated in one file: `PIPELINE_ISSUES_MASTER_TRACKER.md` claims FIXED for 8 items that are actually only partially fixed (NEW-P1-011, NEW-P2-005/006/008/016, P3-P1-05, P3-P1-07, NEW-P5-005).

**Bottom line:** the codebase is substantially ahead of its documentation. All universal PRs U-PR1…U-PR8 have landed (U-PR5 under the renamed `RawPersistencePolicyResolver` / `CloudAiPrivacyGate` / per-source storage modes). Pipeline 9 (workers) is 27/27 fixed. The remaining genuine work clusters around five themes: mixed-currency residuals, export/restore snapshot guarantees, bank-sync atomicity/OAuth stubs, a few "fix landed in the wrong place" remnants flagged by audit v4, and two uncommitted fix sets that need to be committed.

---

## 2. Universal issues — status and why they are "universal"

### 2.1 Why an issue is classified "universal"

The universal tracker was produced by **cross-pipeline root-cause clustering** of ~250 issues from the 12 pipeline reports: issues were deduplicated, then classified into five buckets — pipeline-local (~140), multi-pipeline (18), universal/shared-infrastructure (14), engine-level (8), test/docs-only (~12).

An issue is **universal/shared** when the *same root-cause pattern* recurs across many pipelines **through shared infrastructure** — one shared file, policy, or mechanism consumed by all of them. Examples: `RawStorageMode` semantics (P1/3/8/10/11 all interpret it differently for the same content types), maintenance mode (P7's export lock "affects all pipelines since the app is write-locked"), mixed-currency sums in the shared forecast engines.

An issue is **engine-level** when it lives in a *single shared engine class consumed by every pipeline* — e.g. `TransactionLifecycleCoordinator` ("consumed by all"), `WorkerExecutionGuard` (all 7 workers), DST-unsafe arithmetic in the forecast engine.

The tracker's explicit criterion for why these must **not** be fixed per-pipeline is divergence risk: *"Each pipeline fixing independently will use different patterns (some check `is CancellationException`, some use `runCatching`, some add `ensureActive()`). Need one shared pattern."* This was operationalized as the **Do-Not-Fix-Locally list** and the **dependency/order graph** (U-PR4 barrier before U-PR5/U-PR6; U-PR1 before all local catch-block fixes). Conversely, issues stayed *out* of the universal track when the fix is call-site-specific (e.g. JSON export assembly is "export-specific"), and those went back to pipeline agents.

### 2.2 Universal issue verdicts (22 issues + 1 extra)

| ID | Sev | Pipelines | Doc claim | **Verified status** | Key evidence |
|---|---|---|---|---|---|
| U-CANCEL-01 | P1 | 1,3,4,6,7,8,9,10,11 | U-PR1 ✅ | **FIXED** | `domain/util/CancellationSafe.kt:17,45` (`runCatchingCancellable`, `rethrowIfCancellation`); guard test `CancellationSafetyArchitectureGuardTest.kt:213` + `CancellationPropagationContractTest`. Note: enforcement is a **test-based architecture guard, not a detekt rule** (no `SuspendFunctionBroadCatch` rule exists). ~45 `is CancellationException` guards in 17 main-source files |
| U-TOCTOU-01 | P1 | 2 (all) | U-PR2 ✅ | **FIXED** | No helper named `atomicReadModifyWrite`; instead every update/delete in `TransactionLifecycleCoordinator.kt` (8+ sites: 885-888, 1042-1046, 1106-1111, 1195-1223, 1324-1328, 1404-1408, 1484-1488, 2113-2115) re-reads the row and captures `beforeSnapshot` **inside** `database.withTransaction` |
| U-MONEY-01 | P1 | 5,6,12 | U-PR3 ✅ | **PARTIALLY FIXED** | Snapshots normalized via `AnalyticsCurrencyNormalizer` (`ForecastInputAssembler.kt:299,412`); planned expenses via `MoneyNormalizationEngine` (:96-98). **Residual:** TODO P2-20 at `ForecastInputAssembler.kt:231-234` — merged recurring patterns keep original currency; block-party buckets fall back to raw amounts on conversion failure (`SynthesisEngine.kt:333,345,478,554`) |
| U-MONEY-02 | P1 | 5,6,12 | U-PR3 ✅ | **FIXED** | `ForecastInputAssembler.kt:96-98,412`; `FinancialHealthScoreV2.kt:127-128` surfaces `conversionConfidence` from warnings |
| U-MONEY-03 | P2 | 5,6,12 | ⏭ not done | **FIXED (doc stale)** | Fail-closed `HomeCurrencyResolution` (`domain/currency/HomeCurrencyResolution.kt:10-14`); `Failed → throw` ("NEVER silently assume EUR", `ForecastInputAssembler.kt:396-413`) |
| U-BARRIER-01 | P0 | 7 (all) | U-PR4 ✅ | **FIXED** | `DatabaseBackupRepositoryImpl.kt:481-482,701-702` — all maintenance-entering ops (`exportDatabase`, `createCostBackup`, restore paths) run inside try/finally with exit on every path; drain-timeout self-exits (`MaintenanceOperationRunner.kt:34-36`) |
| U-BARRIER-02 | P1 | 1,2,3,4,6,7,9,10 | U-PR4 ✅ | **PARTIALLY FIXED** | Barrier enforced at worker checkpoint (`WorkerExecutionGuard.kt:270-283`) + read barrier for exports (:287-295) + `WriteBarrierArchitectureGuardTest` CI gate. **Residual:** it is call-site-based, not a Room-level interceptor — exempted call sites bypass by design; `refreshToken`'s `updateToken` write (`BankApiIntegration.kt:428`) relies on an entry check only |
| U-BARRIER-03 | P1 | 9 (all) | U-PR4 ✅ | **FIXED** | `BlockedPolicy` → SKIPPED, not FAILED (`WorkerExecutionGuard.kt:211-223,299,316,654-659`) |
| U-PRIVACY-01 | P0 | 1,3,8,10,11 | U-PR5 (claimed not done) | **FIXED (doc stale; renamed)** | No `RawContentPolicy` class; instead `domain/privacy/RawPersistencePolicyResolver.kt:48-59` maps NOTIFICATION→`rawNotificationStorageMode`, RECEIPT_OCR→`rawOcrStorageMode`, EMAIL_RECEIPT→`emailReceiptStorageMode`, BANK_STATEMENT/BANK_API→`rawBankStatementStorageMode`. Caveat: the resolver itself has **no production callers** — each write site implements the matrix inline (consolidation debt, not a correctness gap) |
| U-PRIVACY-02 | P1 | 8 (affects 5,10,11) | U-PR5 (claimed not done) | **FIXED (doc stale)** | No `EffectiveCloudAiPolicy`-as-named; `domain/privacy/EffectiveCloudAiPolicy.kt:24-81` exists (merge policy `cloudAiEnabled && ai.allowCloudAi`, `requireAllowed` throws on unknown capability) + `CloudAiPrivacyGate`/`DefaultCloudPayloadPolicy` gate all 9 cloud provider files (12 call sites) |
| U-PRIVACY-03 | P1 | 7,8,11,12 | U-PR5 (claimed not done) | **PARTIALLY FIXED** | `DataRetentionWorker.kt:130-210` purges 10 targets with audit events; `ExportAnonymizer.sanitizeExport` purges 9 sinks and is applied to encrypted DB export + redacted costbackups. **Residual:** post-restore semantic-equivalence verification (overlaps P7-P1-05, still open) |
| U-WORKER-01 | P1 | 9 (all) | U-PR6 ✅ | **FIXED** | Barrier re-check immediately before `workerRunLogger.start` (`WorkerExecutionGuard.kt:553-569`, explicit "U-WORKER-01" comment) |
| U-WORKER-02 | P1 | 9 | U-PR6 ✅ | **FIXED** | Startup stale-RUNNING recovery at 15 min (`AppStartupCoordinator.kt:352-361`); guard default 4h (`WorkerExecutionGuard.kt:662`) |
| U-WORKER-03 | P1 | 9 | U-PR6 ✅ | **FIXED** | Zero-count SUCCESS logged with `WORKER_NO_WORK` reason (`WorkerExecutionGuard.kt:361-371`) |
| U-WORKER-04 | P1 | 9 | U-PR6 ✅ | **FIXED** | `ExistingWorkPolicy.REPLACE` on one-shot version bump (`WorkerSpecScheduler.kt:134-141`, "PR1-FIX: was KEEP") |
| U-TIME-01 | P2 | 4,9,10 | U-PR7 ✅ | **FIXED** | `BillReminderWorker.kt:40,64`, `WarrantyExpirationWorker.kt:68,85,88` use injected `TimeProvider`; 0 `System.currentTimeMillis()` in worker files; `BankApiIntegration` already used TimeProvider (original issue incorrect) |
| U-TIME-02 | P2 | 6 | U-PR7 ✅ | **FIXED** | `FinancialStressForecastEngine` uses `TimePeriodUtils.addDays/addMonths` at ~10 sites; 0 `DAY_IN_MILLIS` in file |
| U-SIDEEFFECT-01 | P1 | 11 | NOT A BUG | **VERIFIED NOT A BUG** | `ReceiptLifecycleCoordinator.kt:968,1091` — `createExpenseDbOnlyV2` + single `runBestEffortAfterCommit` dispatch |
| U-SIDEEFFECT-02 | P2 | 2 | FIXED | **FIXED** | `TransactionSideEffectPlanner.kt:163-280` — every action takes `SideEffectTriggerType`; idempotency keys embed it (175, 207, 244, 280) |
| U-EXPORT-01 | P0 | 12 | open | **FIXED (doc stale)** | `ExportOptionsViewModel.kt:610` — explicit `if (!first) append(',')` on the live schemaVersion-2 path (legacy copy :452) |
| U-EXPORT-02 | P1 | 12 | open | **FIXED (doc stale)** | `CsvCellSanitizer.kt:39-49,79-91` — plain-number negative exemption ("P12-PR1 (NEW-P12-003)"); 13 dedicated tests |
| U-DEAD-01 | P1 | 5 | open | **FIXED (doc stale)** | `ForecastInputAssembler.kt:324-348,379` computes previous-month totals; `FinancialHealthScoreV2.kt:48,118-133` bill-aware runway |

---

## 3. The 7 genuinely NOT-FIXED issues

| ID | Pipeline | Sev | What remains | Evidence |
|---|---|---|---|---|
| **P5-CURRENT-009** | 5 | — | Block-party "actual" sums raw `effectiveAmount` across currencies; caller passes raw `ctx.expenseEntities` | `SynthesisEngine.kt:560-568`; caller `ComputeDashboardWidgetsUseCase.kt:679` |
| **NEW-P6-015** | 6 | P3 | `isIncomePattern()` hardcoded `return false` with TODO → recurring income treated as expense in cashflow; pattern source has no income rules | `CashFlowCalculator.kt:647-651,386-389`; `MergedRecurringPatternsProvider.kt:23-24` |
| **P7-P1-05** | 7 | P1 | Post-restore **semantic equivalence verification** still a TODO (counts/manifest checks only) | `BackupVerifier.kt:20-26` (explicit TODO); counts captured at `DatabaseBackupRepositoryImpl.kt:593-606` but never compared |
| **P8-P1-12** | 8 | P1 | Unified privacy-denied UX model — only `AssistantViewModel` handles `PrivacyDenied` | grep of `ui/` finds no shared denied-state model |
| **P10-P1-02** | 10 | P1 | No OAuth state/PKCE — `initiateConnection` returns bare demo URL; entity has no OAuth session fields | `BankApiIntegration.kt:128`; `BankConnection.kt:31-59`; 0 grep hits for pkce/codeVerifier/oauthState |
| **P10-P1-09** | 10 | P1 | API sync imports one transaction per commit — no outer transaction / per-item import rows. *Mitigated* by STRICT_EXTERNAL_ID idempotent keys + durable run ledger, so retries are safe | `BankApiIntegration.kt:229-381,527-534` |
| **P12-P1-04** | 12 | P1 | Snapshot-consistent export — `export_snapshot_rows` "NOT yet implemented (P12-P1-04 / PR-SNAP)" by its own KDoc; rowCount header is a separate query | `ExportDataRepository.kt:29-42`; `ExportOptionsViewModel.kt:159-169,678-686` |

---

## 4. The 31 PARTIALLY FIXED issues (primary fix present, residual documented)

### Universal (3)
- **U-MONEY-01** — see §2.2 (TODO P2-20 + raw block-party fallback).
- **U-BARRIER-02** — call-site barrier, not Room interceptor; `refreshToken` write re-check gap (`BankApiIntegration.kt:428`).
- **U-PRIVACY-03** — export redaction landed; semantic-equivalence remainder = P7-P1-05.

### Pipeline 1 (1)
- **NEW-P1-011** (P3) — service consolidated onto `domain/common/Hashing.kt`, but `RawNotificationFingerprint.kt:26` keeps a duplicate SHA-256 impl, documented as pinned to the MIGRATION_104_105 backfill format. Arguably acceptable; document it.

### Pipeline 2 (4)
- **NEW-P2-005** — `DefaultExpenseCategoryAssignmentService.kt:29-44` is now transactional + barrier-checked + audited, but still bypasses coordinator lifecycle (no planner dispatch → no budget/anomaly side effects). Consumed by `ReceiptLinkService.kt:296`.
- **NEW-P2-006** — audited `deleteAllNotifications` writes BULK_DELETED in-txn; the deprecated event-less `deleteAll()` still exists (`NotificationRepository.kt:251-258`), no production callers.
- **NEW-P2-008** — `updateMerchantForMerchant` (dedupeKey left stale) still exists; `@RestrictedExpenseDaoMutation`, zero production callers.
- **NEW-P2-016** — timeout-protected `resolveHomeCurrency()` exists (`CurrencySettingsRepository.kt:70-85`), but the hot paths still call raw `homeCurrency().first()` with no timeout: `TransactionLifecycleCoordinator.kt:470` (create) and `:868` (update), plus ~15 other call sites. Classic "fix landed in the new path only" (audit-v4 pattern).

### Pipeline 3 (2)
- **P3-P1-05** — `ReceiptRepository.insertReceipt` still only WARNING-deprecated and has a production caller (`WarrantyTrackerRepository.kt:725`).
- **P3-P1-07** — `ProcessReceiptUseCase.kt:26` retains `?: "EUR"` null-fallback; everything else migrated to fail-closed "XXX".

### Pipeline 5 (4)
- **NEW-P5-005** — see U-MONEY-01 residuals.
- **NEW-P5-009** — `MoneyAggregateBuilder.kt:42-49` now warns on count/bucket mismatch but still defaults missing counts to 0.
- **NEW-P5-012** — `StaleRatePolicy` is policy-based, but `CurrencyConverter.kt:86-87` keeps hard-coded 24h with explicit TODO.
- **NEW-P5-013** — unknown bucket type returns `MoneyAggregate.empty` (now with a warning).

### Pipeline 6 (3)
- **P6-P1-13** — balance provider abstraction + honest `NET_CASHFLOW_ESTIMATE` labeling exist, but the only implementation is still the 90-day net-cashflow estimate; no real balance source.
- **NEW-P6-010** — thresholds extracted to named constants with TODO for AppConfig (`FinancialStressForecastEngine.kt:76-81`).
- **NEW-P6-013** — canonical `SpendingPaceCalculator` returns NO_BASELINE sentinel; the forecast-pipeline copy `ForecastInputAssembler.kt:361-365` still emits `0f`.

### Pipeline 8 (1)
- **NEW-P8-006** — per-target retention isolation + checkpointing done, but only *transient* failures trigger retry; permanent purge failures still return `Result.success` (`DataRetentionWorker.kt:231-235`).

### Pipeline 10 (3)
- **P10-P1-03** — durable run ledger (`OperationRunRecorder`) exists; **no persisted incremental cursor/checkpoint per connection** (`since` never stored).
- **P10-P1-07** — barrier re-checked before every processor write; `refreshToken`'s `updateToken` (:428) relies on entry check only.
- **P10-P1-08** — dedupe aligned via shared `DuplicateDetectionPolicy`, but code is duplicated, not shared (`BankTransactionDeduper` still "planned", `BankStatementLifecycleProcessor.kt:99-101`).

### Pipeline 11 (3) — note: two residuals are fixed only by **uncommitted** changes
- **NEW-P11-002 / NEW-P11-003** — parser-level `canParse` sender gates landed, but `detectProvider` still routes by *body* keywords without sender check (`EmailReceiptIngestionService.kt:333-336`), and the unknown-provider fallback calls parsers directly, bypassing `canParse` (:355-362). Pinned as known-deferred by an uncommitted test.
- **NEW-P11-005** — Amazon double-escaped regexes fixed (committed); the **identical defect in `AppleReceiptParser.kt` is fixed only by the uncommitted working-tree change** (tagged NEW-P11-2026-001/002, +43/-11 source, +281 tests). As-committed, Apple orderNumber extraction was broken at HEAD.

### Pipeline 12 (7) — mostly feature-completeness rather than bugs
- **P12-P0-01** — CSV import exists + roundtrip-tested, but wired only into the debug screen, no user-facing entry point.
- **P12-P1-02** — dataset-level accounting validation, still not snapshot-tied to the streamed rows.
- **P12-P1-03** — multi-currency audit columns present; `conversionStatus` field still not exported.
- **P12-P1-05** — encryption fail-closed + privacy-gated; plaintext remains the default and `EXPENSE_EXPORT` is unconditionally Allowed (`ExportPrivacyGate.kt:41-42`).
- **P12-P1-06** — CSV grew 7→26 cols, JSON ~25 fields; attachments/tags/recurring linkage still absent.
- **P12-P1-07** — receipt *provenance* (source links) exported; no receipt file content (by design?).
- **P12-P1-08** — business fields in DTO + generic exports; Xero/IIF/FreshBooks fixed headers still omit them.

---

## 5. Per-pipeline rollups

| Pipeline | Registry | Audited | FIXED | PARTIAL | NOT FIXED | STALE | Headline |
|---|---|---:|---:|---:|---:|---:|---|
| Universal | UNIVERSAL_ISSUE_TRACKER.md | 22 | 18* | 3 | 0 | 0 | All U-PR1..8 landed; U-PR5 under renamed classes |
| 1 Notification capture | PIPELINE_1 | 24 | 23 | 1 | 0 | 0 | Doc said 8 open → actually 1 partial. Uncommitted `pos` regex refinement pending |
| 2 Transaction lifecycle | PIPELINE_2 | 16 | 12 | 4 | 0 | 0 | U-PR2 TOCTOU fully fixed (inline pattern) |
| 3 Receipts | PIPELINE_3 | 19 | 17 | 2 | 0 | 0 | U-PR5 storage modes live incl. email path |
| 4 Recurring rules | PIPELINE_4 | 21 | 20 | 0 | 0 | 1 | Doc's 5 OPEN items all actually fixed |
| 5 Dashboard/synthesis | PIPELINE_5 | 27 | 22 | 4 | 1 | 0 | Mixed-currency residuals are the main theme |
| 6 Budget/forecast | PIPELINE_6 | 31 | 27 | 3 | 1 | 0 | NEW-P6-015 (income patterns) the real open bug |
| 7 Backup/export/restore | PIPELINE_7 | 16 | 15 | 0 | 1 | 0 | Only P7-P1-05 semantic verification open |
| 8 Privacy/retention/cloud-AI | PIPELINE_8 | 23 | 21 | 1 | 1 | 0 | P8-P1-12 denied-UX open; resolver bypass = debt |
| 9 Background workers | PIPELINE_9 | 27 | 27 | 0 | 0 | 0 | **Fully green**, incl. NEW-P9-008 (doc said PARTIAL) |
| 10 Bank sync | PIPELINE_10 | 15 | 10 | 3 | 2 | 0 | Weakest pipeline: OAuth/PKCE + sync atomicity open |
| 11 Email receipts | PIPELINE_11 | 13 | 10 | 3 | 0 | 0 | Apple regex fix exists **only uncommitted** |
| 12 Export/accounting | PIPELINE_12 | 18 | 10 | 7 | 1 | 0 | Partials are mostly feature-completeness |
| **Total** | | **272** | **233** | **31** | **7** | **1** | |

\* includes U-SIDEEFFECT-01 (verified not-a-bug) and U-MONEY-03/U-PRIVACY-01/U-PRIVACY-02 (doc said open, code fixed).

Notable cross-cutting observations:
- **Every registry is stale** (last validated 2026-05-31…2026-06-15). Several are internally inconsistent (PIPELINE_5/6/10/11/12 status headers contradict their own tables).
- **Uncommitted work at audit time:** `NotificationFilter.kt` (whole-word `\bpos\b` fix, NEW-P1-2026-001 + tests), `AppleReceiptParser.kt` (NEW-P11-2026-001/002 + tests). Both are real fixes that will be lost if the working tree is discarded.
- **Test-suite debt found:** `RecurringLifecycleFixesTest.kt` is an empty `@Ignore` stub referencing removed APIs; `BankTokenCipher` has no test for the `KeyInvalidated` path; no determinism test for the seeded mock RNG in P10; no dedicated TOCTOU regression test for the U-PR2 inline pattern (code-verified only).

---

## 6. Master-tracker cross-check

| Tracker | Last updated | Reliability after cross-check |
|---|---|---|
| `PIPELINE_ISSUES_MASTER_TRACKER.md` | 2026-06-01 | **Most contradictory.** Same ID namespace as pipeline docs. 8 items claimed FIXED are verified PARTIAL (NEW-P1-011, NEW-P2-005/006/008/016, P3-P1-05, P3-P1-07, NEW-P5-005); 13+ items still marked 🔴 OPEN are verified FIXED (NEW-P5-003, NEW-P7-003/004/005/006, NEW-P8-001/002/003/004/007/008, NEW-P11-001); P4-P1-05 "deferred" is obsolete. Its unique value: U1–U10 universal-contracts table (U10 partial) |
| `MASTER_ISSUE_TRACKER.md` (MIT-001…075) | 2026-07-03 | Unique MIT namespace (not duplicates), but status layer ~2 months stale and its RED/YELLOW pipeline summary is unreliable. Its remaining TODOs that map to verified residuals (MIT-032 homeCurrency timeout, block-party sums, isIncomePattern) are directionally right |
| `MASTER_ISSUE_TRACKER_SUPPLEMENT_2.md` (MIT-074…083) | 2026-06-15 | Pure TODO spec additions; no status claims to contradict |
| `ENGINE_ISSUES_MASTER_TRACKER.md` (W/A/C/G/I/T/M) | 2026-06-03/04 | **Most accurate of the five.** Spot-checks matched: C11 partial (`CategoryRepository.kt:202,253` backfill stubs), T08 partial (`TaxModule.kt:25` DemoTaxRateProvider), W22 partial (local-only validation). Its partials/TODOs/deferrals (W22, G03, G06, I05, I09, T01, T06, T08, C11; W26, M04, M10-12; 12 deferrals) were NOT exhaustively re-verified and should be treated as the live backlog for engines |
| `FIXED_CLAIMS_VALIDATION_AUDIT_v4.md` | 2026-05-31 | Its "false FIXED" findings were real then; **all have since been genuinely fixed except P3-P1-07's EUR remnant** (NEW-P6-004 window, NEW-P10-002 key-invalidation consumption, NEW-P12-003/007 sanitizer corner, NEW-P7-006 — all verified fixed). Its two systemic theses — "fix in the dead path" and "corner-cut fix trades one bug for another" — accurately predict the residuals found in this audit |

---

## 7. Recommended remaining work (priority order)

**Tier 1 — correctness/privacy bugs and at-risk fixes**
1. **Commit the uncommitted fixes** (`NotificationFilter.kt` + tests, `AppleReceiptParser.kt` + tests) — real P1/P11 fixes exist only in the working tree.
2. **Mixed-currency residual cluster** (U-MONEY-01 / NEW-P5-005 / P5-CURRENT-009 / P6-CURRENT-012): resolve TODO P2-20 (merge patterns in home currency in `ForecastInputAssembler`), stop the raw `effectiveAmount` fallback in block-party buckets (`SynthesisEngine.kt:333,345,478,554,560-568`), and normalize the raw `ctx.expenseEntities` input (`ComputeDashboardWidgetsUseCase.kt:679`).
3. **P7-P1-05** — post-restore semantic-equivalence verification (the counts are already captured at `DatabaseBackupRepositoryImpl.kt:593-606`; a comparator is missing).
4. **P12-P1-04** — snapshot-consistent export (`export_snapshot_rows`, PR-SNAP).
5. **"Fix in the wrong place" remnants:** `ProcessReceiptUseCase.kt:26` `?: "EUR"` → fail-closed; raw `homeCurrency().first()` on hot paths (`TransactionLifecycleCoordinator.kt:470,868` + ~15 sites) → route through timeout-protected `resolveHomeCurrency()`.

**Tier 2 — behavioral gaps**
6. **NEW-P6-015** — income-pattern classification in cashflow (`isIncomePattern` TODO + income rule source).
7. **P10-P1-07** — barrier re-check before `updateToken`; **P10-P1-03** — persisted incremental sync cursor.
8. **NEW-P8-006** — permanent retention-purge failure should not return success.
9. **P11 provider-detection residual** — sender-gate `detectProvider` / fallback path (pinned by test as deferred).
10. **P8-P1-12** — unified privacy-denied UX model.

**Tier 3 — debt / feature completeness**
11. P10-P1-02 (OAuth PKCE — decide if intentionally stubbed; if so, mark by-design), P10-P1-09 (outer sync atomicity), P10-P1-08 (extract shared `BankTransactionDeduper`).
12. Pipeline-12 feature partials (attachments/tags in exports, business fields in accounting writers, plaintext-default export policy).
13. Hygiene: wire `RawPersistencePolicyResolver` into write sites (or delete it), replace empty `RecurringLifecycleFixesTest`, add `BankTokenCipher` KeyInvalidated test, decide on a detekt rule for U-PR1 enforcement, NEW-P2-006/008 (delete deprecated DAO/repo methods).
14. **Regenerate the registries** — all 12 pipeline docs + `PIPELINE_ISSUES_MASTER_TRACKER.md` should be re-issued against this report; the ENGINE tracker is the only one worth keeping as-is (minus its internal count inconsistency).

---

## Appendix A — verification notes and limitations

- Verdicts are **static code verification** of the working tree at HEAD `d1fa9c68` + uncommitted changes. No Gradle/test execution was performed (per repo coordination rules); "test exists" statements cite file paths found on disk, not passing runs.
- Where multiple audits overlapped (e.g. `WorkerExecutionGuard` in U-*/P7/P9; storage modes in U-*/P3/P8/P11; TODO P2-20 in U-*/P5/P6), findings were consistent across independent agents.
- The ENGINE tracker (116 issues) and MIT tracker (MIT-001…083) were cross-checked for contradictions but not exhaustively re-verified item-by-item; their non-contradicted OPEN/PARTIAL/DEFERRED items remain candidate backlog.
- P4-P1-05 is marked STALE rather than FIXED because the original deferral may have targeted a different (cross-source matching) concern; the collision concern itself is structurally resolved (`RecurringOccurrenceExpander.kt:147-154`).
