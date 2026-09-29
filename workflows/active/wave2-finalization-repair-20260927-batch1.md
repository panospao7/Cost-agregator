# Wave-2 finalization repair — batch 1, September 27, 2026

## Status: implementation repairs authored; Wave 2 NOT CLOSED

This is a bounded, single-agent validation-unblocking repair batch following the independent review and the human validation handback. No subagents, commits, pushes, branch/worktree changes, or live builds/tests/guards were used. The campaign still assigns execution to the human; the batch stops for that gate rather than inventing a passing result or silently waiving remaining work.

The independent review remains unchanged at `workflows/active/wave2-independent-deep-review-20260927-100231Z.md`. It covers all seven clusters, all 34 original findings and A1–A8. This handback records only the incremental repairs below; it does not replace the original traceability matrix or retroactively change its reviewed snapshot.

## Snapshot and preservation

- Branch: `bug-fixes`.
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`.
- Repair intake: 2026-09-27T13:06:50.6538114+03:00; dirty fingerprint `b7447cd23b2b5c823fad90da1ce63497842060d13673927edd6f7d62dce7d6e8`.
- Source read-back before this documentation write: 2026-09-27T13:18:31.7544565+03:00; fingerprint `d6c44d47e22745109f9964d78d5782045b6dda3b99a79aa02a0a22beaf4c36fe`.
- Index remained empty. The 11 paths below are the only incremental source/test changes observed against repair intake. Existing user work, validator-side edits, the authorized zero-entry UI DAO allowlist, and the independent review were preserved.
- These documentation changes alter the next runner fingerprint. Future results must report their own actual start/end fingerprints, not reuse the source-only/pre-documentation value above.

## What was repaired

### 1. Money Radar: truthful required-income fixtures, not another provider rewiring

Actual failed evidence: `vr-20260927-094905-1a35bdce`. The three failing tests already supplied `mergedRecurringPatternsProvider.getConfirmedPatterns()`. That dependency/call also already existed at HEAD. Their strict ExpenseRepository mock instead omitted `getTotalDepositsForPeriod`, which is now a required read whenever due bills are scored. The failure correctly reached the unavailable-result boundary.

Added an explicit successful income fixture (5000.0, matching the neighboring bill-window fixture) to the due-earlier-today, scalar merchant-reason, and merged-recurring-dedup tests. Original bill, amount, date and reason assertions are unchanged. No Money Radar production routing/filter logic was altered. Existing required-signal failure/cancellation controls remain intact. The journal’s missing-new-provider explanation is not the source-supported diagnosis.

### 2. Forecast: reuse the resolved currency

`FinancialStressForecastEngine` now passes its already-resolved display currency into `resolveStartingBalanceBaseline` rather than independently reading home settings a second time. The explicit-currency success control retains its no-settings-read assertion and now verifies the provider receives that currency. A new default-currency control verifies settings are read exactly once and the same currency reaches the provider.

This addresses W2-R4. It does not redesign the separately tracked NetCashflowBalanceProvider/currency-blind fallback behavior (FRESH-P6-009), its numeric assumptions, or other excluded recurrence/baseline debt. No universal claim about every internal forecast fallback is made.

### 3. Cloud proof: preserve the real return-expression boundary

W2-R1: helper-return discovery no longer consumes spaces from masked quoted/template text. It finds the return keyword in executable source, rejects labelled returns, then skips only actual source whitespace. The existing positive prepared-template and negative raw-input assertions remain. Their coverage now includes ordinary/triple-quoted literals and a nonempty literal prefix.

### 4. Cancellation guard: exact paths and no trusted-filename bypass

W2-R2: allowlist paths now require exact normalized repository-relative equality; basenames and partial suffixes cannot exempt another directory. Test fixtures use an actual repository-relative source layout. Added negative suffix, absolute/traversal/wildcard, different-directory and full-scan basename cases.

Self-review also found an independent blanket `CancellationSafe.kt` filename exclusion that would bypass the stricter matcher. Removed that exclusion. Tests retain the safe-helper control, require an unsafe same-named file to produce G-CANCEL-02, and inspect the checked-in helper without any exemption. This narrows exceptions; it does not add or broaden an allowlist entry. Exact rule and callable/overload assertions remain.

### 5. Three Python fixtures repaired without weakening their contracts

- `test_ci_workflow_evidence_gate.py`: checks the actual caller-stated HEAD/base printf export plus commit/base validation, instead of an obsolete exact echo command. The Python expected string is raw so shell backslash-newline escapes remain literal.
- `test_verify_cloud_payload_boundaries.py`: the positive synthetic provider now imports the canonical `toRequestBody` extension it actually invokes. Provenance enforcement and negative cases are unchanged.
- `test_db_guard_run_cache.py`: passes the parser’s fully qualified owner `com.example.Repo`. Warm-cache equality and resolved-versus-unresolved status/type assertions are retained, so the test reaches its intended isolation check.

These repairs target four of the 15 observed Python failures, counting the cloud-proof failure above. They are authored changes, not four proven passing reruns. Recursive collection, module-identity collection repair, test discovery, assertions, baselines and configured allowlists were not weakened.

## Incremental files touched

- `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt`
- `scripts/guardrails/cloud_payload_proof.py`
- `scripts/guardrails/test_cloud_payload_proof.py`
- `scripts/verify_cancellation_boundaries.py`
- `scripts/test_verify_cancellation_allowlist_scope.py`
- `scripts/ci/test_ci_workflow_evidence_gate.py`
- `scripts/test_verify_cloud_payload_boundaries.py`
- `scripts/test_db_guard_run_cache.py`
- `scripts/test_verify_cancellation_boundaries.py`

Documentation: this new handback and a pointer update in `workflows/active/wave2-remaining-handoff.md`. No authoritative campaign STATE/JOURNAL or previous review record was rewritten.

## Review and validation actually performed

- Read AGENTS.md, local scope/review-strict/fix-ci/implement-batch guidance, bounded architecture ownership/legal paths, campaign scope/fences, original findings, current handoffs, relevant source/callers and persisted failure evidence.
- Performed author-only static review and targeted read-back of each changed contract. No further defect was identified within this bounded repair batch during that read-back. This is not an independent strict-review or guardian approval.
- `git diff --check -- <the 11 listed paths>`: exit 0. This is whitespace inspection only, not Python syntax, Kotlin compilation, test execution or guard evidence.
- Gradle / pytest / compile / lint / guards / validation-runner execution by this coder: **NOT RUN**, per the persisted human-run policy.
- Required independent review/guardian decisions remain pending; no new waiver is assumed.
- Checked no active validation lock/process before writes. No baseline growth, exception addition, allowlist expansion, recursive-selection reduction, assertion weakening or generated-output edit was made. Policy references: `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07 and FG-23.

## Human-only commands for this batch

Run from the repository root on a quiescent tree, after the required review decision. Run exactly one validation at a time; stop on every non-PASS. If any run is RUNNING/STARTING, poll that ID rather than starting a duplicate. Do not invoke Gradle or pytest directly. Do not use vrun -Raw for consecutive targeted filters.

### A. Targeted Kotlin contracts

```powershell
$filters = @(
    '*ComputeMoneyRadarUseCaseTest',
    '*FinancialStressForecastEngineTest',
    '*ComputeDashboardWidgetsUseCasePaceWiringTest'
)
foreach ($filter in $filters) {
    pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter $filter
    if ($LASTEXITCODE -ne 0) { throw "Stopped at $filter; inspect the persisted result before continuing." }
}
```

### B. Independent recursive Python evidence, still globally serialized

This is a separate evidence branch, not permission to overlap a Kotlin run. vrun does not forward GuardId, so use the runner directly. The registered guard retains the full recursive Python selection; do not reduce it to only the repaired files. The previously observed DB artifact/access failures are still blockers and may keep this result FAIL.

```powershell
$startText = powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile registered-guard -GuardId guard_tests -AllowOverlap -OverlapReasonCode HUMAN_REQUEST | Out-String
if ($LASTEXITCODE -ne 0) { throw "Guard run did not start: $startText" }
$start = $startText | ConvertFrom-Json
if (-not $start.run_id) { throw "Runner returned no run ID; inspect the output, do not retry blindly." }
do {
    $waitText = powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Wait -RunId $start.run_id -MaxWaitSeconds 300 | Out-String
    $result = $waitText | ConvertFrom-Json
    if (-not $result.status) { throw "Runner returned no status; inspect $($start.run_id)." }
} while ($result.status -in @("RUNNING", "STARTING"))
$result | ConvertTo-Json -Depth 10
if ($result.status -ne "PASS") { throw "Guard result is $($result.status); retain and hand back all failures." }
```

For any already active run, use only:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Wait -RunId '<actual-existing-run-id>' -MaxWaitSeconds 300
```

After the remaining repairs/review gates, a fresh full `pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130` is still required. An isolated registered-guard pass is not a 25-guard-suite pass. Expensive broader profiles require separate human approval.

Retain `build/validation-runs/<run-id>/result.json`, stdout.log, stderr.log and complete.marker; record actual command, run ID, result, exit code, HEAD and matching start/end fingerprints. Missing, skipped, unknown, timed-out, stale or infrastructure-error evidence is not PASS.

## Required next batch and remaining closure blockers

1. **W2-R3 / CA-P-03-003 remains PARTIAL.** Generic PDF receipt intake still drops OCR completeness metadata; a real partial OCR → persisted ledger → UI integration remains missing. The next coordinated receipt repair must address the repository result, lifecycle outcome/events, scan/batch consumers and meaningful integration coverage together, preserving the single writer, transaction, raw-data policy and duplicate semantics. This batch did not add a new processing status, schema field or half-wired partial flag. The existing statement helper passes do not close that original requirement.
2. **Eleven of the 15 previously observed Python failures are not repaired here:** six migration-signature cases, one combined-seed consistency case and four DB-access cases. Persisted known-good/db-access infrastructure evidence identifies OperationRunEventDao, not the new receipt/warranty CAS predicates. These are recorded pre-existing artifact/guard debts explicitly fenced by the approved remaining plan; do not regenerate policy/baselines or relax exact assertions to hide them. Closure needs their approved resolution/disposition, not an inferred waiver.
3. **Full guard evidence remains FAIL:** the latest inspected full run is `vr-20260927-084139-95dbc7df`, exit 2, 17 pass / six violation / two infrastructure-error legs; pytest 15 failed / 4302 passed / 28 skipped. The new proof module did execute and had a failing prepared-template case. Neither “all new modules pass” nor “identical names mean zero new violations” is established by that run.
4. **WorkerRunLoggerTest is not a proven current class PASS.** The prior independent report distinguishes the compile failure, two-timeout failure and later one-timeout failure. The asserted environmental mechanism was not independently established; preserve the timeout assertions and obtain trustworthy current evidence.
5. **Complete the remaining CL-15, A7 and CL-22 tails** after batch repair gates. Retain the approved historical CL-05 merge decision without extending its waiver. A2 decision/privacy and A7 architecture/money gates, all-provider transport execution, and required independent review remain outstanding.
6. The eight listed validator-side edits remain preserved, including the recurring and backup non-copying sentinels and their strict identity assertions. Their existing historical PASS results are not fresh results for this changed worktree.

No Wave-2 milestone is marked DONE/GREEN/complete. This repair batch is implemented but unverified; the overall Wave-2 verdict remains open/blocked until required code, execution and review gates are actually satisfied.

## Repaired source byte manifest (before documentation writes)

| Path | Bytes | SHA-256 |
|---|---:|---|
| `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt` | 40560 | `b0159d0157637be68aca727246eb96dfd3855746947c9e42713b77f206958794` |
| `app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt` | 37583 | `0852af0cbc9977759720f0f4544143fc83ef25c4dd19919f43542f058c8f7e60` |
| `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt` | 29728 | `84fc68f519cff22b330ef2b2560b2c882fd7de9e24ed81e6359d14c61f1a280b` |
| `scripts/ci/test_ci_workflow_evidence_gate.py` | 5662 | `e9633f5bf396cc8c960aa4668027f2d332ab653e988a837765a12412339c7bac` |
| `scripts/guardrails/cloud_payload_proof.py` | 31403 | `904654f677729f490a7dbce1fc76c0e586313a8e9f9869a4b805cf5a3bad2e71` |
| `scripts/guardrails/test_cloud_payload_proof.py` | 12916 | `d7a9b290cd3780338478d00ef2f193d8f8f9983d92020b461672d8b8a2169938` |
| `scripts/test_db_guard_run_cache.py` | 16686 | `0760e84989a427b471a9b5ebd3900c1e5a4fc36fbe2c641b8fe7fdd58ea8f0d3` |
| `scripts/test_verify_cancellation_allowlist_scope.py` | 6144 | `fed4dac4efa53b78ca73e166da3bd68039da7a688fd82b0c68843f1144b1d202` |
| `scripts/test_verify_cancellation_boundaries.py` | 26052 | `24ab49a27c7e018ea07b29b5bc6a2033301eb4d089d185a00babc14d81941ffa` |
| `scripts/test_verify_cloud_payload_boundaries.py` | 12028 | `69c0e25fb1ad784c8b194ed576cc760a5f2c01daed007ea5bdb1e5ac97407396` |
| `scripts/verify_cancellation_boundaries.py` | 22731 | `671db49813976969db18d99a6cc3278c045c75083f379f5554cf4606744af45c` |

