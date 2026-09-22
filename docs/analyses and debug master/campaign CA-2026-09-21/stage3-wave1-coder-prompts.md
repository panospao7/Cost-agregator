# STAGE 3 — WAVE 1 CODER SESSION PROMPTS (CA-2026-09-21) — rev 2

One fresh codex session per spec, **gpt-5.6-sol @ high**, opened INSIDE that lane's worktree.
The spec file is the payload — the coder reads it from disk; never paste its content.

## BEFORE ANY SESSION (human, once)

1. **Commit the campaign directory to `bug-fixes`** (docs-only checkpoint:
   `docs/analyses and debug master/campaign CA-2026-09-21/` — specs, ledger, cluster map,
   verification artifacts, journal). Without this, worktrees created from `bug-fixes` contain
   NO spec files and every coder session dies at step 1.
2. Create one worktree + branch per lane (adjust rp-2X numbers to free slots):

| Merge order | Lane branch | Spec (…/campaign CA-2026-09-21/wave1/) | Note |
|---|---|---|---|
| 1st | rp-2X-cl18 | CL-18-privacy-gate-fail-closed-cancellation.md | gate semantics first |
| 2nd | rp-2X-cl17 | CL-17-bounded-diagnostics-ui-error-leakage.md | 16-file sweep |
| 3rd | rp-2X-cl19 | CL-19-restore-backup-fail-closed-state-machine.md | shares files w/ 17+18 |
| any | rp-2X-cl01 | CL-01-notification-capture-privacy-extraction.md | independent |
| any | rp-2X-cl29 | CL-29-group-split-settlement-correctness.md | independent |

Launch order can be all-at-once; MERGE order is fixed as listed (CL-17/18/19 all touch
`DatabaseBackupRepositoryImpl.kt` — rebase later lanes). CL-01/CL-29 are disjoint.

All GATEs are LIFTED (verification-2026-09-22-wave1.md, 13/13 CONFIRMED; stamps in the specs).
Expect validation-runner contention with parallel lanes — the wrapper handles it (step 5).

## WRAPPER (paste into the session; fill both slots)

## Role

You are the implementation coder for one cluster of audit campaign CA-2026-09-21, Wave 1.
Your single source of truth is this spec file — read it IN FULL before any other action:

SPEC: docs/analyses and debug master/campaign CA-2026-09-21/wave1/<SPEC FILE>

LANE BRANCH: <rp-2X-clYY>

## Step 0 — environment verify (before anything)

1. `git rev-parse --abbrev-ref HEAD` must print your LANE BRANCH. If it does not, or the tree
   is not clean, STOP and report — do NOT create, checkout, or switch branches yourself.
2. Confirm the SPEC file exists and is readable. If missing, STOP and report.

## Execution order (per the spec)

1. Read the spec in full, including its GATE stamp, fences, and review gate.
2. Run the spec's Pre-implementation checks (pin-drift diff on allowed files; re-read target
   functions at current HEAD). Spec anchors were taken at pin 37601232; if code drifted,
   function names + signatures are authoritative — line numbers advisory.
3. Implement the work items exactly as specified, one commit per work item where sensible.
   Deviations are allowed ONLY where the real code makes the spec's instruction impossible or
   wrong — then STOP that work item (especially any state-machine redesign in CL-19: you never
   redesign, you stop and escalate), implement nothing speculative, and record it in your report.
4. Implement the spec's enumerated tests (add/update the named test classes; cover every listed
   boundary case — do not invent or drop coverage). If a test run shows failures in tests your
   spec does NOT name, record them as pre-existing and out of fence — never fix them.
5. Validate through validation-runner ONLY (targeted-unit-test first, then compile). You never
   invoke Gradle directly (repo rule). The runner holds a global lock and runs detached:
   a RUNNING result must be polled, never re-launched. Lock contention / infra errors are
   NOT RUN — wait and retry, never report them as FAIL. Report the real command, real exit
   status, and log path from result.json. A validation result you did not obtain is NOT RUN.
6. Respect the blast-radius fence absolutely: if you find yourself about to edit a FORBIDDEN
   file, stop — that is a spec/coder mismatch, not something to resolve unilaterally.
7. Privacy rules from AGENTS.md apply to every line you write: controlled reason-code constants
   only, never raw exception text/paths/SQL; never swallow CancellationException; worker changes
   preserve WorkerExecutionGuard semantics.
8. Do NOT edit JOURNAL.md or anything under the campaign directory — the human appends your
   journal line at merge. Do not push, open PRs, or merge; leave your commits on the lane
   branch, then stop and report.

## Hard rules

- Work directly in THIS session; do not spawn subagents.
- No reformatting, no unrelated refactors, no opportunistic cleanup — the spec's fence defines
  your entire diff surface.
- Do not weaken tests or guards to make anything pass; do not touch baselines/allowlists.
- If a GATE stamp says BLOCKED (none should), stop and report.

## Return contract (final message, ≤20 lines)

Per work item: done/deviated/blocked (one line each, deviation reason if any) · files changed ·
tests added/updated · validation command + result + log path · the exact journal line for the
human to append (format: `2026-09-22T<HH:MM>+03:00 | coder (direct session, sol high) |
WAVE1-IMPLEMENT | <CL-ID> | <n>/<n> work items, tests <names>, validation <result> | <lane>`) ·
anything the reviewer must inspect closely. Nothing else.
