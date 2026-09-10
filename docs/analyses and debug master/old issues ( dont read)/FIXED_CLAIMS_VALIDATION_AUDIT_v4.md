# FIXED-Claims Validation Audit — v4 (Pipelines 1-5 local fixes + v3-blocker closure)

> **Generated:** 2026-05-31 (fourth validation pass)
> **Supersedes context:** `FIXED_CLAIMS_VALIDATION_AUDIT_v3.md`.
> **HEAD at validation:** `ca5972bf` ("fix(p1-p5): pipeline-local fixes…"), on top of `b3fce31d` ("fix: close v3 audit findings — build green…").
> **Method (unchanged):** Each flipped claim validated independently by one `debugger` + one `reviewer`. **The user executed the gradle build** — its output is authoritative and reconciles the one agent disagreement.

---

## 0. ⛔ BLOCKER — the codebase does NOT compile at HEAD

User-run build output (authoritative):

```
> Task :app:compileDebugKotlin FAILED
ProcessReceiptUseCase.kt:29:89 Argument type mismatch: actual type is 'String?', but 'String' was expected.
ProcessReceiptUseCase.kt:40:85 Argument type mismatch: actual type is 'String?', but 'String' was expected.
BUILD FAILED in 4m 17s
```

**Root cause:** the `ca5972bf` change for **P3-P1-07** added `userCurrencyProvider.getHomeCurrency()` which returns `String?` (nullable), and passes it directly into `ReceiptParser.parse(rawText, homeCurrency: String)` (non-null) at lines 29 and 40 — no `?:` fallback, no null check. This is a hard compile error.

**Consequence:** Nothing at HEAD is testable; the app cannot build. The earlier commit `b3fce31d` had fixed the previous red build (the CE guard test) — but the very next commit `ca5972bf` **reintroduced a build break** via the P3-P1-07 change. The 10 other genuinely-correct fixes in this round are real in source but **unverifiable by test until this compiles**.

This is the single most important finding of the round and reverses the implied "all green" status of the P1-P5 commit.

---

## 1. Executive Summary

11 flipped claims validated. **9 genuinely fixed in source, 1 fixed-with-caveat, 1 actually open (and it's the one breaking the build).** Plus the v3 logic blockers re-checked and confirmed closed by `b3fce31d`.

| Issue | Claim | Verdict | Note |
|-------|-------|---------|------|
| **P3-P1-07** | ✅ FIXED (P3-PR1) | 🔴 **ACTUALLY_OPEN** | Compile error (§0); even if it compiled, `AppConfigCurrencyProvider` hardcodes EUR; `ProcessReceiptUseCase` has no production callers + no currency persistence → dead code |
| NEW-P1-002 | ✅ FIXED (P1-PR2) | 🟡 FIXED_WITH_CAVEAT | Deadlock vector (dispatcher-switching `emit` in txn) genuinely removed; but doc claim "source-link writes happen after commit" is **false** — writes stay in-txn (they're pure DAO, safe); only diagnostics deferred. Test is superficial (passthrough txn mock) |
| NEW-P1-015 | ✅ FIXED (P1-PR2) | ✅ CONFIRMED_FIXED | `IllegalStateException` removed in all 3 review paths; review commits; failure emitted post-commit; rolled-back txn emits no orphan |
| NEW-P2-007 | ✅ FIXED (P2-PR1) | ✅ CONFIRMED_FIXED | Failure branch nulls baseAmount to sentinel 0.0 (`TransactionLifecycleCoordinator.kt:960-970`); doc file-ref wrong (not ExpenseWriteStore) |
| NEW-P2-010 | ✅ FIXED (P2-PR1) | ✅ CONFIRMED_FIXED | Both `bulkUpdateCategory` overloads share `if (affectedCount > 0)` guard (`:1820/:1893`) |
| P3-P1-03 | ✅ FIXED | ✅ CONFIRMED_FIXED | All 3 match outcomes persisted (AutoMatch link, Suggested update, NoMatch→MATCH_NOT_FOUND). **Pre-existing — not authored by ca5972bf** |
| P3-P1-06 | ✅ FIXED | ✅ CONFIRMED_FIXED | `ReceiptInsertResolver` handles IGNORE-conflict; no caller proceeds with id=0. **Pre-existing** |
| P3-P1-08 | ✅ FIXED | ✅ CONFIRMED_FIXED | PARSE_FAILED set on parse-throw path + durable event. **Pre-existing** |
| NEW-P4-002 | ✅ FIXED (P4-PR1) | ✅ CONFIRMED_FIXED | Single `computeScheduledAt` call; shadow removed (`RecurringOccurrenceMaterializer.kt:207`) |
| NEW-P5-001 | ✅ FIXED (P5-PR1) | ✅ CONFIRMED_FIXED | `previousMonthAggregate` populated end-to-end; adapter loads prior-month data (`DashboardContractsAdapter.kt:57-62`), aggregated + consumed |
| NEW-P5-002 | ✅ FIXED (P5-PR1) | ✅ CONFIRMED_FIXED | `if (daysElapsed > 0)` guard (`ComputeDashboardWidgetsUseCase.kt:569`); defensive (DAY_OF_MONTH always ≥1) |
| NEW-P5-011 | ✅ FIXED (P5-PR1) | ✅ CONFIRMED_FIXED | `runwayDays` computed from real remaining/burn (`:612-616`); caveat: NO_INCOME status can mislabel a budget-derived positive runway |

### v3 blockers re-checked (commit b3fce31d) — all genuinely closed

| v3 item | v4 verdict | Evidence |
|---------|-----------|----------|
| P6-P1-10 (block-party dead code) | ✅ CONFIRMED_FIXED | `displayCurrency` now set on the returned forecast (`SynthesisEngine.kt:418`) + assembler populates it (`ForecastInputAssembler.kt:652`); `bpCurrency` non-blank at runtime → conversion no longer dead |
| U-BARRIER-01 (createCostBackup) | ✅ CONFIRMED_FIXED | `try { … } finally { runCatching { exit() } }` now wraps the whole body (`DatabaseBackupRepositoryImpl.kt:640-641`); covers `return@withContext` |
| U-MONEY-01 (public raw overload) | ✅ CONFIRMED_FIXED | `internal fun synthesize(` (`SynthesisEngine.kt:133`); no out-of-module caller |
| NotificationCaptureService:520 CE | ✅ CONFIRMED_FIXED | `if (e is …CancellationException) throw e` added (`:521`) |

So `b3fce31d` did close all the v3 logic blockers — but `ca5972bf` then broke the build for an unrelated reason (P3-P1-07).

---

## 2. The one disagreement, reconciled by the build

**P3-P1-07** was the only item where the two agents split:
- **Debugger:** CONFIRMED_FIXED — saw `UserCurrencyProvider` injected and `parse(..., homeCurrency = homeCurrency)` wired.
- **Reviewer:** ACTUALLY_OPEN — flagged a nullability compile error (`String?`→`String`), that the only provider impl returns `AppConfig.DEFAULT_CURRENCY = "EUR"`, and that `ProcessReceiptUseCase` has no production callers / no currency persistence (dead code; the real path is `ReceiptRepository.homeCurrency()`, already correct via a different mechanism).

**The user's build confirms the reviewer exactly** — compile error at `ProcessReceiptUseCase.kt:29` and `:40`. Verdict: **P3-P1-07 ACTUALLY_OPEN**. The debugger's pass missed the nullability type mismatch.

Three distinct problems with this "fix":
1. **Compile error** — `getHomeCurrency(): String?` passed to non-null `parse(homeCurrency: String)`; no `?:` fallback.
2. **Still hardcodes EUR** — even if it compiled, `AppConfigCurrencyProvider.getHomeCurrency()` returns `AppConfig.DEFAULT_CURRENCY` = `"EUR"`. The hardcode was relocated, not removed.
3. **Dead code** — `ProcessReceiptUseCase` has no production injection site; `ProcessedReceipt` carries no currency field. The currency that actually persists comes from `ReceiptRepository.homeCurrency()` (= `currencySettingsRepository.resolveHomeCurrency()`), which never touches `UserCurrencyProvider`.

Recommended fix: have `UserCurrencyProvider` resolve from `CurrencySettingsRepository` (not `AppConfig`); handle nullability before `parse`; and either wire `ProcessReceiptUseCase` into the real pipeline or close P3-P1-07 against the already-correct `ReceiptRepository.homeCurrency()`.

---

## 3. Notable secondary findings

- **Provenance mismatch (P3):** the doc batches P3-P1-03/06/08 under the same FIXED flip as ca5972bf, but `ca5972bf` only actually edited `ProcessReceiptUseCase.kt` (P3-P1-07). The 03/06/08 fixes are real at HEAD but were landed by earlier work. If the audit trail matters, correct the attribution.
- **NEW-P1-002 doc wording:** the consolidated doc says "source-link writes now happen after the transaction commits." False — the DAO writes stay inside the txn (correct, they're pure/atomic); only the dispatcher-switching diagnostic `emit` was deferred. The deadlock is genuinely gone, but the claim text overstates the change.
- **Test rigor gaps (not defects, but weak verification):**
  - `NotificationProcessingPipelineSourceLinkTest` mocks `withTransaction` as a passthrough, so it cannot prove post-commit deferral (NEW-P1-002); its `coVerify(atLeast=1){ emit }` is satisfied by the always-present terminal emit.
  - `DashboardProjectionSafetyTest` re-implements the projection/runway formulas inline rather than driving `ComputeDashboardWidgetsUseCase` — tautological; won't catch a regression at the real call site. NEW-P5-001 (the P0) has no end-to-end test.
- **Doc file-ref errors (cosmetic):** P2 issues point to `ExpenseWriteStore.kt`; real logic is in `TransactionLifecycleCoordinator.kt`. P5 issues reference `DashboardSynthesisEngine.kt` (doesn't exist); real logic is `ComputeDashboardWidgetsUseCase.kt`.
- **NEW-P5-011 status mismatch:** `runwayDays` is computed correctly, but `runwayStatus` returns `NO_INCOME` whenever `monthlyIncome == 0.0` even when a budget yields a positive runway — the day count is right but the label can contradict it.

---

## 4. Required work before this round can be called done

**Must fix (blocker):**
1. **Repair the build** — `ProcessReceiptUseCase.kt:29/40`: handle the nullable `getHomeCurrency()` (add `?:` fallback or non-null resolution). Until then HEAD does not compile and no test runs.

**Must re-do (the fix doesn't actually fix):**
2. **P3-P1-07** — make `UserCurrencyProvider` resolve the real user currency (not `AppConfig.DEFAULT_CURRENCY = "EUR"`), and wire it into the path that actually persists receipts, or close the issue against `ReceiptRepository.homeCurrency()`.

**Should tighten (verification):**
3. Replace the passthrough-txn mock in the P1 source-link test with one that proves post-commit ordering; add an end-to-end test for NEW-P5-001; drive the real use case in `DashboardProjectionSafetyTest`.

**Doc hygiene:** correct P3 provenance, NEW-P1-002 wording, P2/P5 file refs.

---

## 5. Bottom Line

`b3fce31d` genuinely closed all four v3 logic blockers (P6-P1-10 dead-code, U-BARRIER-01, U-MONEY-01, NotificationCaptureService CE) — real progress. And 9 of the 11 pipeline-1-5 local fixes are correct in source (NEW-P1-015, NEW-P2-007, NEW-P2-010, P3-P1-03, P3-P1-06, P3-P1-08, NEW-P4-002, NEW-P5-001, NEW-P5-002, NEW-P5-011), with NEW-P1-002 fixed-with-caveat.

But the round cannot be signed off:
- **The build is RED** — `ca5972bf` introduced a compile error in `ProcessReceiptUseCase.kt`, so HEAD doesn't compile and nothing is test-verifiable.
- **P3-P1-07 is not fixed** — the change that claims to fix it is the thing breaking the build, still hardcodes EUR, and is dead code. It must be reverted or redone.

All verdicts reached debugger + reviewer consensus except P3-P1-07, where the disagreement was resolved decisively by the user's build output in favor of ACTUALLY_OPEN.

---

# ADDENDUM — Pipelines 6-9 local fixes (commit 661e78a1)

> **Added:** 2026-05-31 (same audit doc, next remediation round)
> **HEAD at validation:** `661e78a1` ("fix(p6-p9): pipeline-local fixes for budget, backup, privacy, workers")
> **Plans reviewed:** PIPELINE_6/7/8/9_IMPLEMENTATION_PLAN.md
> **Method:** Each claim validated by an independent `debugger` + `reviewer`. Build state re-confirmed.

## A0. ⛔ Build is STILL RED (carried from v4)

`661e78a1` did **not** touch `ProcessReceiptUseCase.kt`. At HEAD, `getHomeCurrency(): String?` is still passed into the non-null `ReceiptParser.parse(homeCurrency: String)` at lines 29 and 40 with no `?:` fallback. The v4 compile blocker persists — **the codebase does not compile**, so none of the P6-P9 fixes below are test-verifiable; all verdicts are from source reading. Fixing P3-P1-07 (see v4 §0) remains the prerequisite for any green build.

## A1. Summary of P6-P9 claims

| Issue | Claim | Verdict | Note |
|-------|-------|---------|------|
| **NEW-P6-004** | ✅ FIXED (P6-PR1) | ⚠ **ACTUALLY_PARTIAL** | Loop now bounded at `MAX_ROLLOVER_PERIODS=365` (ANR risk fixed), **but introduces a correctness regression**: it iterates forward (oldest→newest) and caps at 365, so for daily budgets >1yr old it keeps the OLDEST periods and silently drops the MOST RECENT (most relevant) ones → wrong `effectiveLimit`. Per-period query also NOT batched (plan item unmet). In-code comment "older surplus is lost" is inverted |
| **NEW-P7-003** | (P7-PR4) | ✅ CONFIRMED_FIXED | `enterCriticalRecoveryRequired` now writes reason+timestamp+mode in a single `prefs.edit()...commit()` (`RestoreMaintenanceMode.kt:115-123`); no two-commit crash window |
| **NEW-P7-004** | (P7-PR4) | ✅ CONFIRMED_FIXED | `appendEventToFile` now wrapped in `synchronized(journalLock)` (`RestoreJournal.kt:221/235`); @Singleton → serializes all in-JVM callers. **Residual (out of scope):** `writeJournal()` does its own RMW on the same file without the lock; cross-process still races |
| **NEW-P7-005** | (P7-PR4) | ✅ CONFIRMED_FIXED | `CostbackupBundle.extract()` now `try { … } finally { runCatching { fis.close() } }` (`:316/464-467`) — closes on every exception path |
| **NEW-P7-006** | (P7-PR4) | 🔴 **ACTUALLY_OPEN** | NOT addressed — `DatabaseBackupRepositoryImpl.kt` was not touched by the commit; `countRowsFromSourceTable` still interpolates `"SELECT COUNT(*) FROM $tableName"` unquoted (`:1825`). **Tracker correctly still marks it OPEN** — no false flip. Not exploitable today (callers pass literals) |
| **NEW-P8-005** | (P8-PR1, EffectiveCloudAiPolicy) | ✅ CONFIRMED_FIXED (caveats) | `requireAllowed()` now checks the capability arg (`EffectiveCloudAiPolicy.kt:21-30`), composite-gates on BOTH PrivacySettings AND AiSettings. **Caveats:** method has no production caller (test-only today), covers only receipt-image + bank-statement capabilities, and **no doc marks it FIXED — still 🔴 OPEN in trackers** |
| **NEW-P9-001** | ✅ FIXED (P9-PR1) | ⚠ **ACTUALLY_PARTIAL** | `TimeoutCancellationException` retry branch was added to `runGuarded` (`WorkerExecutionGuard.kt:145-153`) **only** — NOT to `runGuardedWithContext` (`:272-276`), which is the method **all 7 production workers actually use**. Since timeout extends CancellationException, real workers still misclassify timeouts as CANCELLED_BY_SYSTEM. Fix is in the unused path |
| **NEW-P9-002** | ✅ FIXED (P9-PR1) | ✅ CONFIRMED_FIXED | BillReminderWorker settings/quiet-hours check now inside `runGuardedWithContext` lambda (`BillReminderWorker.kt:44-61`); only a `Log.d` precedes the guard |
| **NEW-P9-003** | ✅ FIXED (P9-PR1) | ✅ CONFIRMED_FIXED | All 5 `WorkerRunContext` counters now `AtomicInteger` with `addAndGet` (`:15-31`). Thread-safety test exercises 1000 coroutines on `Dispatchers.Default` (genuine concurrency, though probabilistic) |
| **NEW-P9-004** | ✅ FIXED | ✅ CONFIRMED_FIXED | WarrantyExpirationWorker uses `runGuardedWithContext` + increments ctx counters (`:71-128`). (Was already true from U-PR7 round) |

## A2. The two PARTIAL findings (both agents agreed independently)

**NEW-P6-004 — bound caps the wrong end.** The rollover loop builds periods chronologically forward from the budget start and stops when `periods.size == 365`. For a daily budget older than ~1 year, only the oldest 365 periods are processed and the most recent periods (which actually determine the current effective limit) are dropped. `runningEffectiveLimit` freezes at its ~period-365 state. So the perf fix (O(N)→O(≤365) queries, ANR risk genuinely removed) trades in a silent correctness bug for long-lived daily budgets. The plan's batch-query item was also not done — it's still one query per period. Recommended: iterate backward from the current period (keep the most recent 365), or implement the planned ledger batch query.

**NEW-P9-001 — fix landed in the dead path.** The timeout-retry classification was added to `runGuarded`, but every production worker (BillReminder, Warranty, DataRetention, DailyBriefing, ReceiptMatching, LocationBackfill, MerchantKeyBackfill) calls `runGuardedWithContext`, whose catch block has no `TimeoutCancellationException` branch. Because timeout is a `CancellationException` subclass, a worker timeout still falls into the generic branch → `CANCELLED_BY_SYSTEM`, rethrown, not retried. The documented fix has no runtime effect. Recommended: add the same timeout branch (before the generic CE check) to `runGuardedWithContext`.

## A3. Doc-accuracy notes

- **NEW-P6-004:** `PIPELINE_6_CONSOLIDATED_ISSUES.md` flips it to ✅ FIXED, but `PIPELINE_6_IMPLEMENTATION_PLAN.md` and `PIPELINE_ISSUES_MASTER_TRACKER.md` still show 🔴 OPEN — internally inconsistent, and the validated status is PARTIAL anyway.
- **NEW-P8-005:** the EffectiveCloudAiPolicy code fix is real, but no tracker marks it FIXED (still OPEN). The change is also effectively test-only (no production caller invokes `requireAllowed`; cloud services use `privacyGate.check`).
- **NEW-P7-006:** correctly still OPEN in trackers — good. The P7-PR4 plan only ever scoped P7-003/004/005.

## A4. P6-P9 Bottom Line

The ~80-line P6-P9 commit genuinely closed **6 issues** (NEW-P7-003, NEW-P7-004, NEW-P7-005, NEW-P8-005, NEW-P9-002, NEW-P9-003, NEW-P9-004 — note P9-004 was already fixed). But:
- **NEW-P9-001** is fixed in the wrong method — real workers still misclassify timeouts (PARTIAL).
- **NEW-P6-004** swaps an ANR risk for a silent miscalculation on year-old daily budgets, and the batch-query was skipped (PARTIAL).
- **NEW-P7-006** was flipped/claimed nowhere it touched — the SQL stays unquoted, but the trackers honestly keep it OPEN.
- **The build is still red** (P3-P1-07 from v4), so none of this is test-verified.

Net trend across v1→v5: the team is steadily closing real defects, and crucially the trackers are getting more honest (NEW-P7-006 not falsely flipped). The two recurring failure modes to watch: (1) fixes applied to a method/field that the production path doesn't actually use (NEW-P9-001 in `runGuarded`; earlier P6-P1-10 `displayCurrency`, P3-P1-07 dead use case), and (2) perf caps that silently change correctness (NEW-P6-004). Priority order to finish: fix the build (P3-P1-07) → NEW-P9-001 in the context guard → NEW-P6-004 truncation direction.

---

# ADDENDUM 2 — Pipelines 10-12 local fixes (commit d935ee5c)

> **Added:** 2026-05-31 (final pipeline remediation round — all 12 pipelines now covered)
> **HEAD at validation:** `d935ee5c` ("fix(p10-p12): bank token cipher, email parsers, export sanitizer")
> **Plans reviewed:** PIPELINE_10/11/12_IMPLEMENTATION_PLAN.md
> **Method:** Each claim validated by an independent `debugger` + `reviewer`. Build state re-confirmed.

## B0. ⛔ Build is STILL RED (now 4 rounds running)

`d935ee5c` did **not** touch `ProcessReceiptUseCase.kt`, `UserCurrencyProvider.kt`, or the OCR `ReceiptParser.kt`. The v4 compile blocker persists verbatim — `getHomeCurrency(): String?` is still passed into the non-null `ReceiptParser.parse(homeCurrency: String)` at `ProcessReceiptUseCase.kt:29/40`. **The codebase does not compile**, so every P10-P12 verdict below is source-read only, not test-verified. This blocker has now survived `ca5972bf`, `661e78a1`, and `d935ee5c` untouched. It must be fixed first.

> NEW this round: reviewer found `d935ee5c` **added a second compile break** — making `BankApiConfig.isStubMode` a `val` (NEW-P10-001) breaks two test files that still assign it (`BankApiIntegrationTest.kt:79`, `PR5PrivacyContractTest.kt:100` → "val cannot be reassigned"). So the test source set has two independent compile errors now.

## B1. Summary of P10-P12 claims

| Issue | Claim | Verdict | Note |
|-------|-------|---------|------|
| **NEW-P10-002** | ✅ FIXED (P10-PR1) | ⚠ **ACTUALLY_PARTIAL** | P1 security. Cipher now returns typed `DecryptResult.KeyInvalidated` (`BankTokenCipher.kt:77-78`) — real. **But no caller consumes it:** `decryptWithResult` has zero production callers; the sole decrypt path `refreshToken → decryptIfNeeded` re-collapses `KeyInvalidated → null` (`:48-49`), so `BankApiIntegration.kt:296-302` still can't distinguish key invalidation and there is **no re-auth prompt**. User-visible bug persists. Both agents agree |
| **NEW-P10-001** | ✅ FIXED (P10-PR1) | ✅ CONFIRMED_FIXED (new regression) | `isStubMode` now `val = BuildConfig.DEBUG` (`BankApiConfig.kt:10`); double-guard holds, release builds safe. **But introduces test-compile break** (2 files still assign the val) — flagged NEW-P10-005 |
| **NEW-P10-004** | ✅ FIXED (P10-PR1) | ✅ CONFIRMED_FIXED | `generateMockTransactions` now seeded `Random(bankId.hashCode()+since)` (`BankApiIntegration.kt:414`). Stale "intentionally non-deterministic" comment remains |
| **NEW-P11-002** | ✅ FIXED (P11-PR3) | ✅ CONFIRMED_FIXED | `AmazonReceiptParser.canParse()` now sender-gated only (`:59-63`); body/subject false-positive branch removed |
| **NEW-P11-003** | ✅ FIXED (P11-PR3) | ✅ CONFIRMED_FIXED | `UberReceiptParser.canParse()` now sender-gated only (`:88-92`) |
| **NEW-P11-004** | ✅ FIXED (P11-PR3) | ✅ CONFIRMED_FIXED | 176 formatters now hoisted to companion `by lazy formatterCache` (`EmailReceiptParser.kt:103-109`), built once |
| **NEW-P11-005** | ✅ FIXED (P11-PR3) | ✅ CONFIRMED_FIXED (Amazon) | Amazon raw-string regexes corrected to single-backslash `\s`/`\d` (verified actually correct, not just changed). **Both agents independently flag: `UberReceiptParser` still has the identical double-escape bug** (`\\s`/`\\d` in raw strings at `:31-76` etc.) → Uber amount/date/ID extraction effectively broken. Out of P11-005's literal (Amazon) scope, but same defect class left unfixed |
| **NEW-P12-001** | ✅ FIXED (P12-PR1) | ✅ CONFIRMED_FIXED | P0 invalid-JSON genuinely fixed: null branch now emits `null,` (`ExportOptionsViewModel.kt:597`). Doc names a non-existent `JsonExporter.kt`; real fix is in ExportOptionsViewModel |
| **NEW-P12-002** | ✅ FIXED (P12-PR1) | ✅ CONFIRMED_FIXED | `sourceLinksJson` now appended raw (already valid JSON), not double-escaped (`ExportOptionsViewModel.kt:600-602`). Doc names wrong file (ExportDataRepository.kt) |
| **NEW-P12-003** | ✅ FIXED (P12-PR1) | ⚠ **ACTUALLY_PARTIAL** | Negative-amount corruption fixed (`-50.00` preserved), **but the new heuristic reintroduces CSV/DDE formula injection**: leading `-` is only neutralized when char 2 is non-alphanumeric, so `-2+3+cmd\|'/C calc'!A0` and `-cmd\|'/C calc'!A0` pass UNESCAPED (`CsvCellSanitizer.kt:39-41`). Test omits dangerous-dash vectors |
| **NEW-P12-007** | ✅ FIXED (P12-PR1) | ⚠ **ACTUALLY_PARTIAL** | Negative-merchant corruption fixed, same injection bypass as P12-003 (`sanitizeIif`, `CsvCellSanitizer.kt:77-79`). Doc names non-existent `IifExporter.kt` |

## B2. Key findings (debugger + reviewer consensus)

**NEW-P10-002 — fix is half-wired (the recurring failure mode again).** This is the round's most important finding: the cipher was correctly refactored to *enable* surfacing key invalidation (sealed `DecryptResult.KeyInvalidated`), but the production decrypt path never consumes it. `BankApiIntegration.refreshToken()` still calls `decryptIfNeeded()`, which flattens `KeyInvalidated` back to `null` — identical to any other failure. No re-authentication prompt exists anywhere. The P1 user-visible defect ("user never prompted to re-auth after biometric/key change") is unchanged. Same pattern as NEW-P9-001, P6-P1-10, P3-P1-07: the fix lands in a path the product doesn't use. Recommended: route `refreshToken` through `decryptWithResult` and act on `KeyInvalidated` with a re-auth UX.

**NEW-P12-003 / NEW-P12-007 — corruption fix trades into an injection regression.** The negative-number fix is real, but the new predicate `trimmed.startsWith("-") && !trimmed[1].isLetterOrDigit() && trimmed[1] != '.'` only escapes a leading `-` when the next char is a symbol. Excel/LibreOffice treat any leading `-` as formula entry, so `-2+3+cmd|...` (char 2 = digit) and `-cmd|...` (char 2 = letter) now flow through UNESCAPED — classic CSV/DDE injection vectors. This is security-sensitive code (export consumed by accounting tools), and the new test only covers benign dashes. Recommended: neutralize a leading `-` unless the whole value matches `^-?\d+(\.\d+)?$` (numeric-only exception), and add dangerous-dash tests.

**NEW-P11-005 — Uber left with the same bug.** Both agents independently noted that while Amazon's raw-string regexes were correctly de-escaped, `UberReceiptParser` still uses `\\s`/`\\d` inside triple-quoted raw strings (matches literal backslash, not whitespace/digit). Uber receipt amount/date/trip-ID extraction is effectively broken. The P11-005 claim was scoped to Amazon, so its verdict stands, but the defect class persists in the sibling file the same commit touched.

## B3. Doc-accuracy notes

- **P12 file attributions are wrong** (fixes are real, files mislabeled): NEW-P12-001 → not `JsonExporter.kt` (doesn't exist), it's `ExportOptionsViewModel.kt`; NEW-P12-002 → not `ExportDataRepository.kt`, it's `ExportOptionsViewModel.kt`; NEW-P12-007 → not `IifExporter.kt` (doesn't exist), it's `CsvCellSanitizer.kt`.
- **NEW-P10-001/004** carry stale comments contradicting the new behavior ("set isStubMode = true for demo"; "intentionally non-deterministic").

## B4. P10-P12 Bottom Line

The commit genuinely closed **7 issues** (NEW-P10-001, NEW-P10-004, NEW-P11-002, NEW-P11-003, NEW-P11-004, NEW-P11-005[Amazon], NEW-P12-001, NEW-P12-002). But:
- **NEW-P10-002** (P1 security) is only half-wired — no caller consumes the surfaced result, no re-auth prompt → PARTIAL.
- **NEW-P12-003 / NEW-P12-007** fix the corruption but reintroduce CSV/DDE formula injection for dash-prefixed payloads → PARTIAL, and it's security-sensitive.
- **Uber regexes** remain double-escaped (broken) — same bug class as the Amazon fix.
- **The build is red** (two errors now: P3-P1-07 from v4, plus the new `isStubMode` val-reassignment in tests).

---

# FINAL ROLL-UP — All 12 pipelines + universal contracts (v1 → addendum 2)

The full validation is now complete across all 8 universal PRs and all 12 pipeline-local rounds. The work is substantial and most of it is real. Two systemic issues dominate the residual risk:

1. **The build does not compile** and hasn't for the last 3 commits. Fixing `ProcessReceiptUseCase.kt` (P3-P1-07) is the single prerequisite to verifying ANY of the ~50 source-confirmed fixes by test. A green build is the top priority.

2. **The dominant failure mode is "fix in the dead path"** — a fix is applied to a method, field, or class that the production code path doesn't actually exercise, so the issue is marked FIXED but the user-visible behavior is unchanged. Confirmed instances across rounds: P3-P1-07 (dead use case + still EUR), P6-P1-10 (`displayCurrency` never populated — later fixed), NEW-P9-001 (`runGuarded` vs `runGuardedWithContext`), NEW-P10-002 (`decryptWithResult` never called). Validation MUST trace to the production caller, not just the changed file.

3. **Security regressions from corner-cutting fixes** — NEW-P12-003/007 traded a display bug for a formula-injection hole; NEW-P6-004 traded an ANR risk for a wrong-result truncation. Fixes in sanitizers/financial math need adversarial tests.

Genuinely closed and solid (test-verifiable once the build is green): U-PR2 TOCTOU, U-PR4 maintenance/barrier (export path), U-PR6 worker guard core, U-PR7 TimeProvider, U-PR8 side-effects, U-PR5 cloud gate + retention, and the bulk of P1-P12 local items (P1-P1-15, P2-007/010, P3-03/06/08, P4-002, P5-001/002/011, P7-003/004/005, P9-002/003/004, P10-001/004, P11-002/003/004/005-Amazon, P12-001/002).

Still open or partial after all rounds: P3-P1-07 (+build), NEW-P9-001, NEW-P6-004, NEW-P10-002, NEW-P12-003/007, NEW-P7-006, U-MONEY-01 (public overload), U-BARRIER-02 (SourceLinkBackfillWorker mid-run), plus the Uber-regex and stale-doc/file-attribution items.
