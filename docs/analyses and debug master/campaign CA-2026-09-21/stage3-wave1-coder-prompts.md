# STAGE 3 — WAVE 1 CODER SESSION PROMPTS (CA-2026-09-21) — rev 3

**REV 3 (2026-09-25) — cost/latency protocol, learned from the 2026-09-25 burn analysis
(¥109/day, 204-turn sessions at the 243K context wall, 181 validation runs):**
1. **Model discipline**: completion coders run on **gpt-5.6-sol @ high**. astra reviews. Astra-run
   coder sessions cost 3-4× for the same work.
2. **Session rotation per work item (or small batch)**: finish WI(s) → commit → END the session.
   A fresh session resumes from the plan + `git log`. Never carry a lane in one session past
   ~60 turns / ~120K context — context re-billing is the dominant cost and the 258K wall kills
   hours of work.
3. **Validation batching**: ONE validation run per completed work item (or batch), never per
   edit. Edit-level loops (dozens of sub-1-minute runs) are the pattern that burned 09-25.
4. **Optional HUMAN-RUNS-VALIDATION mode** (below) — removes the agent from the
   start/poll/retry loop entirely; the human runs the command, the agent reads the durable
   result in one turn.

One fresh codex session per spec, **gpt-5.6-sol @ high**, opened INSIDE that lane's worktree.
The spec file is the payload — the coder reads it from disk; never paste its content.

## BEFORE ANY SESSION (human, once)

1. ~~Commit the campaign directory to `bug-fixes`~~ **DONE 2026-09-22: checkpoint commit
   `ad412aec`** (41 files, docs only — specs/ledger/map/verifications/journal).
2. ~~Create one worktree + branch per lane~~ **DONE 2026-09-22: rp-20..rp-24 created at
   `build/worktrees/rp-2X`, branches `rp-2X-wip`, based on the checkpoint.**
   Open each codex session INSIDE its worktree directory. Lane↔cluster mapping:

| Merge order | Worktree / branch | Spec (…/campaign CA-2026-09-21/wave1/) | Note |
|---|---|---|---|
| 1st | build/worktrees/rp-20 · `rp-20-wip` | CL-18-privacy-gate-fail-closed-cancellation.md | gate semantics first |
| 2nd | build/worktrees/rp-21 · `rp-21-wip` | CL-17-bounded-diagnostics-ui-error-leakage.md | 16-file sweep |
| 3rd | build/worktrees/rp-22 · `rp-22-wip` | CL-19-restore-backup-fail-closed-state-machine.md | shares files w/ 20+21 |
| any | build/worktrees/rp-23 · `rp-23-wip` | CL-01-notification-capture-privacy-extraction.md | independent |
| any | build/worktrees/rp-24 · `rp-24-wip` | CL-29-group-split-settlement-correctness.md | independent |

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
   invoke Gradle directly (repo rule). BATCHING RULE: one validation per completed work item or
   small batch — never per edit. The runner holds a global lock and runs detached:
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
- ROTATION RULE: when your current work item (or batch) is committed AND its validation is
  recorded, END the session with the return contract. The human starts the next slice fresh.
  Do not accept additional work items that would push the session past ~60 turns.
- No reformatting, no unrelated refactors, no opportunistic cleanup — the spec's fence defines
  your entire diff surface.
- Do not weaken tests or guards to make anything pass; do not touch baselines/allowlists.
- If a GATE stamp says BLOCKED (none should), stop and report.

## Optional mode: HUMAN RUNS VALIDATION (drop this block into the wrapper when active)

HUMAN-RUNS-VALIDATION MODE IS ACTIVE: never invoke validation-runner yourself. When a work item
needs validation, output the exact command (profile + -TestFilter) and END YOUR TURN. The human
runs it and tells you the run id; your next turn reads build/validation-runs/<id>/result.json and
the stdout/stderr logs directly — one read, no polling loop. While waiting, you may continue
editing OTHER work items that do not depend on the result.

## Return contract (final message, ≤20 lines)

Per work item: done/deviated/blocked (one line each, deviation reason if any) · files changed ·
tests added/updated · validation command + result + log path · the exact journal line for the
human to append (format: `2026-09-22T<HH:MM>+03:00 | coder (direct session, sol high) |
WAVE1-IMPLEMENT | <CL-ID> | <n>/<n> work items, tests <names>, validation <result> | <lane>`) ·
anything the reviewer must inspect closely. Nothing else.
