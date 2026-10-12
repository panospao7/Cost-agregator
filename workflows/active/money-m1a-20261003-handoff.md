# M-1a receipt/source-currency boundary handoff

Date: 2026-10-03. Branch: `bug-fixes`. Base checkpoint: `a1c66173219d7d959b0d36859a68176aaf814242`.

## Status and resume point

The user requested local review and the start of implementation. This handoff records M-1a: implementation and 27 regression tests are authored, not validated. Subsequent M-1b work is recorded in `money-m1b-20261003-handoff.md`; M-2 through M-5 remain unstarted. No subagents, builds, test execution, scanners, guards, baseline/policy edits, commit or push were used for these slices. Independent strict money/receipt review remains pending; local author review does not replace it.

Read the source diff and `money-m0-inventory-20261003.md` before validation or further implementation. Keep the tree stable throughout a validation chain and journal results only after it ends. Preserve unrelated dirty files.

## Local review findings and dispositions

- M-0 incorrectly implied that `CurrencyCode.parse` proves catalog support. Source inspection shows shape validation only. The new source-currency boundary separately checks `CurrencyCode.ALL_SUPPORTED`; the inventory contract is corrected.
- Historical finding M060 is the inline tax-inclusive item sum, not a confidence comparison. Its description is corrected without changing the historical finding coordinates.
- An initial currency-token expansion could have treated the word `TRY` in merchant text as Turkish currency. ISO evidence now requires an amount-adjacent token, a standalone currency line, or an explicit currency label; a regression covers non-monetary `TRY` text.
- A generic dollar sign must not override explicit CAD/AUD/etc. Qualified dollar symbols and explicit dollar-family codes are reconciled before the legacy generic-dollar USD interpretation. Recognized contradictory currencies remain unavailable rather than falling back to home currency.
- Local source review found no remaining blocking issue in this slice. Runtime behavior, compile compatibility, and independent review are still pending. Regression risk is medium because the parser is widely consumed.

## Files touched

Production:

- `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt`

Tests:

- `app/src/test/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilderSourceCurrencyTest.kt` — 9 authored tests, NOT RUN.
- `app/src/test/java/com/yourname/expensetracker/domain/receipt/ReceiptParserMoneyBoundaryTest.kt` — 18 authored tests, NOT RUN.

Workflow documentation: this handoff, the M-0 inventory/handoff, and M-0/M-1 status in `wave4-money-batch-plan-20261001.md`.

## Implemented contract

### Source-currency aggregate boundary

`MoneyAggregateBuilder.fromSourceCurrencyAmounts` validates one source currency and enumerates amounts once. It performs no FX conversion and preserves existing Double addition without per-value rounding. Valid results carry the source currency, IDENTITY rate basis, a source bucket and included count. Valid empty input produces an available empty aggregate, not a missing-total receipt.

Missing, malformed or unsupported source currency, non-finite contributions, and non-finite running sums produce controlled `Unavailable` results. Invalid input does not become a successful partial total. Excluded counts cover all supplied contributions. Counts represent supplied values: receipt item aggregation counts items, while the receipt-total projection supplies one receipt total; do not present item counts as expense-transaction counts.

Historical transaction normalization and already-normalized analytics contracts are untouched. This helper must not replace adapters that need upstream exclusions, rate provenance or confidence.

### Receipt parser and consumers

- Item fallback, inline tax inclusion and confidence share one validated item aggregate. The companion tax helper uses the same builder and now requires receipt currency.
- Both inspected production callers, `ReceiptRepository` and `ReceiptScanViewModel`, pass stored receipt currency. No lifecycle/DAO ownership, schema or persistence change was made.
- Strict tax tolerance remains less than 5%; confidence cross-validation remains less than 10%. Optional subtotal behavior, item rounding/decimal parsing and scalar constructor fields are retained.
- `ParsedReceipt.totalAggregate` is computed from the current total/currency, so copying or editing the legacy DTO cannot leave a stale stored aggregate. Missing total returns `RECEIPT_TOTAL_MISSING`, not available zero. The getter has no new backing field; an authored Gson regression checks legacy serialized fields.
- Recognized currency conflicts, unsupported explicit currency labels and invalid supplied home currency suppress the total. A valid explicitly supplied home currency is used only when no currency evidence was recognized. The existing parser argument default of EUR is retained for compatibility, not introduced as recovery from failed resolution.
- Currency detection is not a complete global currency-identification redesign. Bare ambiguous symbols retain the existing policy where not qualified; arbitrary unknown OCR words are not classified as ISO codes. Broader symbol/unknown-code policy is outside this slice.

## Finding disposition and remaining scope

The four historical ReceiptParser RAW04 occurrences M056/M059/M060/M062 now call the approved monetary boundary rather than sum price fields locally. This is a source disposition only: no scanner was run and no numerical guard-count reduction is claimed.

M057 (`ParsedReceipt.total`), M058 (`subtotal` lexical match), and M061 (confidence helper parameter) remain open. The compatible optional scalar DTO is deliberately retained while exposing a typed projection. Do not rename fields to dodge the scanner, claim every parser finding is closed, or weaken RAW06. Review the consumer migration and any narrowly justified scanner semantics separately in the planned money closure work.

M-1b receipt-item tax distribution is not changed. Radar/cash-flow normalization should still be brought forward as isolated M-4a slices once the shared contract is reviewed; they need not wait for all analytics migrations. Contribution-history currency remains decision-gated.

## User-run validation queue

NOT RUN. Use Windows PowerShell 5.1 from the repository root, not `pwsh`. Complete each command before starting the next, stop on a real failure, and do not invoke Gradle directly. Check for an existing active run first; poll it instead of restarting. The wrapper can report bookkeeping errors while a detached run is alive: inspect durable `result.json`, completion marker and matching start/end fingerprints before classifying the run. Unknown, STARTING, RUNNING, stale, timed-out and infrastructure results are never PASS.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile compile
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*MoneyAggregateBuilder*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReceiptParser*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*GreekNormalizationTest*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReceiptScanViewModelTest*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReceiptRepositoryStatementDuplicateTest*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReceiptRepositoryBatchDuplicateTest*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReceiptPartialOcrPipelineTest*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReceiptLifecycleCoordinatorTest*'
```

Then start the scoped raw-money observation through the durable runner, wait using its returned ID, and inspect the terminal result:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\validation-runner.ps1 -Action Start -Profile registered-guard -GuardId raw_money_aggregates
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\validation-runner.ps1 -Action Status -RunId '<returned-run-id>'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\validation-runner.ps1 -Action Wait -RunId '<returned-run-id>' -MaxWaitSeconds 300
```

The raw-money guard is expected to remain FAIL while other slices and the scalar declarations remain unresolved. Confirm no new finding; do not interpret a smaller count as an overall PASS. Full static-suite/freshness checks remain final stable-tree gates under `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03/FG-06/FG-07/FG-23. No policy, baseline, allowlist or ratchet changes are part of M-1a.

## Commit boundary and risks

No staging or commit was requested. Exclude unrelated `.codex`, campaign prompt, verdict/report/coverage files, generated outputs and semantic-no-op ownership-policy dirtiness. Do not reset or delete them.

Pending: compile and focused regression results, scoped guard observation, independent money/receipt review, the remaining M slices, and previously deferred device/release gates. M-1a is not validated, closed, or commit-ready on author review alone.
