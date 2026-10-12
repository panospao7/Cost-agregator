# Money M-2d Handoff - Spending Personality Caller Audit

Date: 2026-10-04. Branch: `bug-fixes`. Base checkpoint: `a1c66173`.

## Status

M-2d source review, caller audit, and normalized-path regression tests are
authored. Strict money/architecture review and live validation are **pending**;
the user requested implementation now and validation later. No subagents,
build/test/guard profiles, staging, commit, or push were used.

## Audit result

- `AnalyticsViewModel` calls `SpendingPersonalityClassifier.classify(allInput)`
  with the normalized analytics input.
- No production caller of the deprecated no-argument `classify()` path was
  found. It remains for compatibility and isolated legacy tests only.
- The normalized path scopes expenses to `NormalizedAnalyticsInput.period`,
  derives feature values from normalized amounts, and exposes the typed
  `MoneyAggregateResult` on the profile. Invalid target/source currencies fail
  closed instead of producing a partial personality result.
- The normalized average transaction-size feature is derived from the typed
  aggregate display amount, not from a second raw-Double aggregation.
- Count-based weekend/night features and deposit-only impulse detection remain
  documented behavioral differences from the legacy spend-weighted path. This
  batch does not silently redefine those feature contracts.
- Repository/budget fetch dependencies are not touched by normalized calls;
  the focused tests verify that normalized classification does not re-enter the
  raw self-fetch path.

## Files

- `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifierNormalizedMoneyTest.kt`

## Regression coverage

Five tests cover mixed source currencies with normalized amounts, confidence
reduction for excluded/missing-rate data, insufficient normalized input,
period scoping, and invalid source-currency fail-closed behavior. They have
**not been executed**.

## Deferred validation

After strict review, run serially through the durable validation runner:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile compile
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*SpendingPersonalityClassifier*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*AnalyticsViewModel*'
```

Retain the combined M-1/M-2a through M-2c2 queue and record one stable
start/end fingerprint. A static money-guard result is not claimed here.

## Remaining scope

The deprecated raw classifier remains intentionally available for compatibility
and test coverage; it is not a production closure path. M-3 forecast/health/
savings consumers, M-4 dashboard/cash-flow/export consumers, and M-5 evidence
and guard closure remain open. Do not change baselines, allowlists, exceptions,
or ratchets to close this batch.
