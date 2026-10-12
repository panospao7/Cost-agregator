# Money M-2c2 Handoff - Category and Weekday Analytics

Date: 2026-10-04. Branch: `bug-fixes`. Base checkpoint: `a1c66173`.

## Status and resume point

Normalized consumer changes and direct regression tests are authored. All live
validation and independent strict money/architecture review remain **pending**.
No subagents, build/test/guard profiles, staging, commit, or push were used.
The user requested continued implementation with validation deferred. This is
not a completed money-track milestone or a claim of guard closure.

Read `money-m2c1-20261004-handoff.md` for the advanced producer slice and the
mechanical M-2b repairs included in this continuation. Retain the M-1/M-2a/M-2b
queues for the combined tree.

## Implemented source contract

- `CategoryInsight` and `DayOfWeekInsight` expose typed money results alongside
  legacy scalar fields. Previous category money also remains explicitly typed.
- Normalized category totals use the canonical aggregate boundary without a
  second conversion. Monthly exclusion selection follows the same half-open
  range as included expenses. Since excluded rows lack category identity,
  category results fail closed when their missing money cannot be assigned.
- An unavailable current or previous category total does not become a fake
  zero-spending percentage change. Known empty previous categories preserve
  the legacy scalar absence while their typed empty result remains observable.
- Weekday results attribute date-known exclusions only to their own weekday
  and requested window. Unknown-date exclusions remain unavailable, not zero.
- Current and historical normalized inputs are merged by transaction identity;
  current included/excluded state takes precedence over an older historical
  representation. A duplicated historical row cannot be counted both as an
  inclusion and an exclusion after a current state transition.
- A display-currency override is checked against the normalized row currencies.
  It cannot merely relabel an EUR value as GBP or trigger an implicit conversion.
- `InsightsEngine` routes its normalized path through these producers. Source
  inspection of `AnalyticsViewModel` confirms that its insights call supplies
  `currentInput` and `historicalInput`; its downstream legacy presentation
  wrapper and raw-list overloads remain separate consumer-migration work.

## Files

Production:

- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`

Tests:

- `app/src/test/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngineNormalizedMoneyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzerNormalizedMoneyTest.kt`

Current planning/inventory status is reconciled in
`wave4-money-batch-plan-20261001.md` and `money-m0-inventory-20261003.md`. The
historical 83/28 finding inventory is not a new scanner result.

## Authored regression coverage

Four category and four weekday cases cover available typed totals and counts,
unattributable category exclusions, previous unavailable results, out-of-window
exclusions, target-currency mismatch, date-local weekday quality, historical
identity deduplication, and current inclusion/exclusion precedence. Weekday
fixtures use the existing system-local calendar contract rather than assuming
that a UTC timestamp always has the same weekday in every timezone.

All eight tests are **NOT RUN**. Source inspection and whitespace checks do not
prove compilation, runtime behavior, or an independent strict-review PASS.

## Deferred validation queue

After strict review, run only through the durable runner, one profile at a time:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile compile
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*CategoryInsightEngine*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*DayOfWeekAnalyzer*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*InsightsEngine*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*AdvancedAnalyticsDashboardTest*'
```

Use the M-2c1 advanced-engine queue and retain the earlier receipt, money-builder,
daily/totals, period-comparison, pace, and analytics golden filters. Record
terminal result/exit code, completion marker, and a stable start/end fingerprint;
no stored result is claimed for this authored tree.

## Remaining scope and commit boundary

Legacy raw-list overloads still contain financial sums and scalar DTO/consumer
paths still need migration. This slice does not close those findings, migrate
`SpendingPersonalityClassifier`, or finish M-2. M-2d, M-3, M-4, and M-5 remain
open; no guard baseline, allowlist, exception, or policy was changed here.

Keep unrelated `.codex` configuration, campaign/prompt/verdict/coverage files,
generated artifacts, and line-ending-only ownership-policy dirtiness outside
the eventual money commit. A nonzero static-guard result remains **FAIL**;
never hide it through a baseline or allowlist adjustment.
