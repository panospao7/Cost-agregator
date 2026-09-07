# RP-01 — Cancellation-safety completion + guard repair

> **Scope class:** Universal (engine + architecture guard). **Mode:** strict.
> **Crossover:** touches `TransactionLifecycleCoordinator` (engine consumed by all pipelines — coordinate with RP-11, which changes the same file's semantics; land RP-01 first, RP-11 rebases), `FinancialHealthScoreV2` (shared with RP-06 — fix only the cancellation part here), `AppStartupCoordinator` (shared with RP-02/RP-03 — one-line changes only).

## U-001 — Raw `runCatching` swallows CE in expense create/update conversion

**Problem.** `TransactionLifecycleCoordinator.kt:476` (create) and `:874` (update) wrap the suspending `currencyConverter.convertAsOf(...)` in raw `runCatching { ... }.getOrNull()`. `runCatching` catches `Throwable`, so a delivered `CancellationException` is converted to `null`; the method keeps running as a zombie (wasted dedupe/validation, wrong `DuplicateSkipped`/`InsertConflict` results returned to dead callers). The best-effort event writes at `:321, :343, :515, :652, :691` use the same construct; `:652` even fabricates `eventLogged = false` via `getOrDefault(false)`.

**Fix design.**
1. Replace the two conversion wraps with the existing shared helper:
   ```kotlin
   val preComputedConversion = if (expense.currency != homeCurrency) {
       CancellationSafe.runCatchingCancellable {
           currencyConverter.convertAsOf(amount = expense.amount, ...)
       }.getOrNull()
   } else null
   ```
   (`domain/util/CancellationSafe.kt:31` — `runCatchingCancellable` is `inline fun <T> (block: () -> T): Result<T>` that **itself rethrows CancellationException** (:34-35) and catches `Exception` only (not `Throwable`). Do **not** add a second CE rethrow in an `onFailure` — that branch is unreachable.)
2. Replace the five event-write wraps with a single private helper in the coordinator:
   ```kotlin
   private suspend inline fun <T> bestEffortEvent(block: suspend () -> T): T? =
       CancellationSafe.runCatchingCancellable { block() }.getOrNull()
   ```
   This removes the `getOrDefault(false)` fabrication at `:652` — return `null` on failure and let the diagnostic field stay unset rather than claiming `false`.
3. **Shrink the guard exclusion:** the coordinator file is excluded wholesale in `CancellationSafetyArchitectureGuardTest.KNOWN_VIOLATIONS` (`:135`, entry `"TransactionLifecycleCoordinator.kt"` / `FALSE_POSITIVE_CE_RETHROW`; the filename filter at `:214-218` excludes it from both the broad-catch and raw-runCatching scans). After the fixes, remove the file-level entry and (if any residual sites genuinely must stay raw, e.g. cancel-safe by construction) replace it with narrowly-scoped per-line entries with owner/reason/expiry — the guard's allowlist "must only shrink" design then polices it.

**What it solves.** Cancelled expense create/update stops doing work and returning results after cancellation; event-write bookkeeping stops lying; the engine becomes guard-enforceable again.

**Guardrails.**
- Do **not** change transaction semantics here: Room's `withTransaction` already throws CE on entry for an already-cancelled caller — verified; the fix is only about not swallowing before the txn.
- Do not alter validation outcomes or event reason codes (controlled constants).
- Crossover: RP-11 rewrites some of these methods' no-op/dispatch behavior — merge order RP-01 → RP-11, keep diffs separable (RP-01 touches only the catch expressions, not control flow).
- Money rule: conversion-failure handling (`baseAmount` clearing, NEW-P2-007 behavior) must remain byte-identical for non-cancellation failures.

**Tests.**
- Extend `CancellationPropagationContractTest` with: create + update entry points — cancel the calling scope during conversion (a `CurrencyConverter` fake that suspends forever, then cancel) and assert `CancellationException` propagates and no `TransactionEvent` rows are written.
- Guard test: add a fixture suspend fun **with a default parameter** wrapping raw `runCatching` and assert it is now flagged (pairs with U-002 below).

## U-002 — Architecture guard is blind to default-parameter suspend functions and token-presence "evidence"

**Problem.** `CancellationSafetyArchitectureGuardTest.kt:664-669` — `findSuspendFunBodyRanges` breaks on the first `=` inside the parameter list (any default parameter!) and skips the function; `:210` accepts a catch body that merely *mentions* `CancellationException` (even in a comment) as guard evidence. The rule is vacuous for a large fraction of suspend functions.

**Fix design.**
1. Rewrite the body scanner: after matching a `suspend fun` declaration, skip the parameter list by **balanced-paren tracking** (depth counter over `( )`, string/char-literal aware is nice-to-have, not required), and only treat `=` as an expression body if it appears *after* the closing paren of the parameter list. Brace-match from there as today.
2. Strengthen evidence: catch body must contain one of `throw`, `rethrowIfCancellation(`, `runCatchingCancellable`, or `ensureActive()` — not the bare tokens. Note the current evidence regex (`:210`) accepts **two** tokens (`CancellationException` **or** `rethrowIfCancellation`) and matches comments — the replacement eliminates both token forms.
3. Re-run the guard mentally against the known allowlists: entries that only "mentioned" a token will now fail → fix those sites or move them to the structured allowlist with owner/reason/expiry (must shrink).

**What it solves.** The U-PR1 enforcement becomes real for default-parameter suspend functions (pervasive in this codebase) and can no longer be satisfied by comments.

**Guardrails.**
- Guard tests are CI gates — this change will surface new violations; budget for fixing them **in this PR** (they are the same one-line rethrow pattern) or moving them to the structured allowlist with expiry. Do not widen allowlists to make the test pass.
- Scanner performance: files are small; balanced-paren scan is fine.

**Tests.** Fixture corpus inside the guard test: (a) default-param suspend fn + raw runCatching → flagged; (b) default-param suspend fn + rethrow → clean; (c) catch body containing the word CancellationException in a comment only → flagged.

## U-003 — `RAW_RUN_CATCHING_ALLOWLIST` expiry never enforced

**Problem.** `CancellationSafetyArchitectureGuardTest.kt:350-358` enforces expiry only for `KNOWN_VIOLATIONS`; the **12** raw-runCatching entries (`:184-197` — RestoreJournalImporter, DefaultCloudPayloadPolicy, AnalyticsRepository, DatabaseBackupRepositoryImpl, ValidateBankStatementTransactionsUseCase, AdvancedAnalyticsEngine, CarbonFootprintCalculator, DiagnosticsRepository, FinancialStressForecastEngine, NetCashflowBalanceProvider, InvestmentTracker, SubscriptionManagerEngine) expiring **2026-10-01** would silently become permanent.

**Fix design.** In the allowlist-validation test (`:405-413`), mirror the KNOWN_VIOLATIONS check: `val expiredRaw = RAW_RUN_CATCHING_ALLOWLIST.filter { it.expires.isBefore(LocalDate.now()) }; assert(expiredRaw.isEmpty()) { "expired raw-runCatching allowlist entries: $expiredRaw — fix or re-justify" }`. Extract a shared `assertNoneExpired(entries, label)` helper used by both lists.

**What it solves.** Time-boxed exemptions actually expire. Guardrails: none beyond diff discipline. **Tests:** one expired synthetic entry → test fails with the entry named (use a fixed `Clock` or inject today to keep the test deterministic — prefer asserting against a synthetic entry list passed to the helper, not wall-clock).

## U-006 — Startup recovery uses raw `runCatching` on suspend calls

**Problem.** `AppStartupCoordinator.kt:351` (inside `recoverStaleWorkerRuns()`, fun at `:349`) wraps the suspend `workerExecutionGuard.recoverStaleRunningJobs`; `:376` and `:378` (inside `importRestoreJournals()`, fun at `:374`) wrap the suspend journal importers. Practically harmless (scope dies only with the process) — pure pattern debt.

**Fix design.** Swap to `CancellationSafe.runCatchingCancellable`, keep the `Timber.w` onFailure logging identical.

**Tests.** None needed (behavioral no-op); covered by the guard after U-002.

---

## Verification addendum (2026-09-07 re-evaluation)

All anchors re-verified against HEAD. Additional facts for the implementer:
- The coordinator has **more** sibling rethrow sites than cited (:275, :393, :469-473 — rethrow at :472, :1185, :2245, plus :237, :302, :870, :1683, :1863, :2036, :2077, :2105, :2147) — the PR's pattern swap applies to the five target sites only; do not "fix" the already-correct ones.
- `FinancialHealthScoreV2` has **four** runCatching sites (:93, :99-101, :381, :383-385). Sites :93 and :381 use `.getOrElse { throw IllegalStateException(...) }` which **converts** CE into ISE — the mechanical CE fix must rethrow CE *before* that mapping, not beside it. The fabricated-score half of P5-008 stays in RP-06.
- `CancellationPropagationContractTest` (`app/src/test/.../contracts/`, 138 lines) is a **static source-scanning** contract test (regex over files; `criticalEntryPoints: List<CriticalCatch(file, methodLabel, catchMarker)>` at `:35-63`) — coordinator create/update are not yet listed. The "fake converter suspends forever, then cancel" runtime test belongs in a `TransactionLifecycleCoordinator` unit test, not the contract file; add static `CriticalCatch` entries for create/update to the contract test separately.

---

## Validation plan (run when the stakeholder re-enables Gradle)

```
./gradlew :app:testDebugUnitTest --tests "*CancellationSafetyArchitectureGuard*" --tests "*CancellationPropagation*"
./gradlew :app:testDebugUnitTest --tests "*TransactionLifecycleCoordinator*"
./gradlew :app:compileDebugKotlin
```

## Sequencing & risk notes

- Land **before** RP-11 (same file) and RP-06 (FinancialHealthScoreV2). The `FinancialHealthScoreV2.kt:93/99/383` CE fixes belong to this PR (mechanical rethrow); the *fallback-to-fabricated-score* half of P5-008 stays with RP-06.
- Risk: medium — engine hot path, but changes are catch-expression-only; the guard tightening is the part likely to surface surprise violations, which is intended.
