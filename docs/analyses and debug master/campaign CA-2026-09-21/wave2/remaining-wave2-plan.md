# Remaining Wave-2 execution plan

Current validation handback, September 27: workflows/active/wave2-validation-repair-20260927.md records partial human results, the authorized empty-allowlist resolution, receipt identity-fixture and pytest collection repairs, and review of all six validator-side edits. Repairs have not been rerun. The authoring-phase status rows below are historical and are not a current passing validation ledger; Wave 2 remains open.

Provenance: 2026-09-26; starting HEAD `a4807632`, branch `bug-fixes`; audit pin `37601232` is an ancestor. This document organizes the user's request to implement the remaining work serially in one session, without subagents.

## Execution boundaries

- Retain the approved finding scopes and legal paths; verify each against current source before freezing its spec.
- Work in the current checkout without switching branches, creating worktrees, committing, merging, or disturbing pre-existing changes. The older separate-session/lane-worktree instructions are superseded only to that extent by this request.
- Perform implementation, test authoring, and explicit self-review sequentially. A self-review is not an independent reviewer or guardian approval; no new review waiver is assumed.
- The persisted Wave-2 coder plan specifies human-run validation. Do not silently override that policy: prepare exact runner commands and report new validation as NOT RUN until execution is authorized or durable human-run results are supplied. Never invoke Gradle or guards directly.
- Preserve FG-03/FG-06/FG-07/FG-23. No baseline growth, allowlist expansion, guard bypasses, destructive operations, or unrelated suite repairs.
- No cluster is closed merely because its code or spec exists. Record implementation, source verification, review, and live validation separately.

## Ordered worklist

| Batch | Scope | Source/spec status | Implementation | New validation |
|---|---|---|---|---|
| 0 | CL-05 historical review closing addendum | Merge and waiver verified from `a4807632` / `03f197d1` | Documentation addendum only | Not a new validation claim |
| 1a | CL-23 worker diagnostics and monotonic drain | Both findings rechecked; source-grounded spec written | Two fixes and eight regression tests authored; static self-review recorded; independent gate pending | NOT RUN |
| 1b | CL-09 four-site stale-write protection, A1 transaction cancellation | Four findings revalidated; source-grounded spec written; A1 transaction site already fixed | Four fixes and 29 regression tests authored; static self-review recorded; independent gate pending | NOT RUN |
| 1c | CL-21 prepared cloud payloads and applicable hybrid routing | Both findings revalidated; source-grounded spec written | Query policy boundary, five bound hybrid delegates, and focused tests authored; static self-review recorded; independent gate pending | NOT RUN |
| 2 | CL-15 eight failure-semantics findings, A1 startup cancellation | Eight anchors revalidated; source-grounded spec written; CL-05 section 8 preserved; A1 startup already fixed | Eight boundary fixes, consumer states, and targeted regressions authored; static author review recorded; independent gates pending | NOT RUN |
| 3 | CL-22 eight guard-hardening findings | Eight findings revalidated against current scripts and CI; source-grounded spec written | All eight changes and regressions authored; author review records an existing invalid empty-allowlist input as a closure blocker; independent gates pending | NOT RUN |

Each cluster receives its own source-grounded spec before edits. Keep per-site tests and report newly exposed guard violations without fixing or baselining out-of-scope findings.

## Additional issue ledger

| Item | Placement / disposition | Evidence status |
|---|---|---|
| A1 retention cancellation | CL-23: no duplicate fix. `DataRetentionWorker.emitRetentionAudit` catch at current lines 438-446 already rethrows cancellation. | Source inspected; landed through CL-05. |
| A1 transaction cancellation | CL-09 adjudication: no duplicate fix; current broad catch rethrows cancellation. | Source and blame verified: line 2324 added by `3b7cabfe`, merged through CL-05. |
| A1 startup cancellation | No duplicate fix: the historical asset-resume catch now has explicit cancellation propagation, including the current broad catch at lines 498-499. | Historical anchor ad412aec and current resumeAssetsIncompleteRecovery inspected; all three listed A1 sites now have source-level protection. Live guard evidence remains separate. |
| A2 denial reason-code contract | Source-grounded decision recorded in A2-denial-contract.md: preserve capability denial versus operational gate failure; normalize unrecognized codes. | Producer/typed transport/UI changes and focused tests authored; no assertion weakening; independent privacy gate and runtime validation pending. |
| A5 phone over-redaction | Already repaired by CL-05 commit 3b7cabfe; no duplicate edit or new privacy exemption. | Current CloudPiiSanitizer and regression corpus inspected: decimal amounts are preserved, while explicit phone labels/international prefixes and local phone shapes remain protected. No new runtime PASS claimed. |
| A7 settlements in displayed balances | Bounded CL-29 read-only follow-up: shared settlement policy, manager and actual screen read paths. | Source-grounded spec, implementation, 19 targeted tests and author review authored; no settlement writer/schema change; live and independent gates pending. |
| A3 / A4 | Record verification/placement if encountered; not permission for broad ViewModel or suite recovery. | Existing candidate findings, not silently closed. |
| A6 / A8 | CL-05 / CL-27 landed work; preserve warning redaction, failed-source buckets, and established money semantics. | Do not reopen without current contradictory evidence. |

## Explicit exclusions

The newly visible 76-failure suite tail, previously recorded RestoreMaintenanceMode/BankSyncStartupRecovery/database-artifact/raw-money guard debt, and device-dependent PDF/image testing remain follow-up work unless an in-scope change directly requires them. Historical failures remain failures, not evidence of a green baseline.

## Combined handoff

Resume from workflows/active/wave2-remaining-handoff.md, not chat memory. Exact human-run commands and the scoped file manifest are in the adjacent wave2-remaining-validation.md and wave2-remaining-files.md files. CL-22-self-review.md records the known input blocker and bounded-parser limitations.

All five remaining cluster scopes have authored implementation and regression changes; this does not close those clusters or finalize Wave 2. No new compile, test, guard, independent-review, or guardian approval exists. No new commits, merges, pushes, worktrees, or subagents were used. The campaign STATE.md and JOURNAL.md remain unchanged; the handoff includes proposed journal wording for the human.

## Pre-existing worktree changes preserved

- `.codex/agents/orchestrator.toml`
- `.codex/agents/explorer-lite.toml` (untracked)
- `docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md`
- `docs/analyses and debug master/CL-29-wave1-implementation-report.md` (untracked)
- `docs/architecture/COVERAGE_MATRIX.md` (untracked)

At intake, `git status --porcelain -- app config scripts` was empty.
