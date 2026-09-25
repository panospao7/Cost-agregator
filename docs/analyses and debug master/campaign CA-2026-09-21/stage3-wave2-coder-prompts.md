# STAGE 3 — WAVE 2 CODER SESSION PROMPTS (CA-2026-09-21) — rev 3 protocol

**PREREQUISITES before any coder session below:**
1. The cluster's spec file must exist (`wave2/CL-XX-spec.md`) — produced by spec sessions
   W2-S1..S5 per `stage3-wave2-spec-prompts.md`.
2. The cluster's findings must be verified (W2 GATE) — same independent adversarial pass as
   Wave 1; GATE-LIFT stamps applied to the spec files.
3. Worktree created from current `bug-fixes` + branch `rp-NN-wip` (see table).
4. Model: **gpt-5.6-sol @ high** for every coder session (rev 3 routing — astra reviews).
5. Session rotation per rev 3: one session per work item or small batch; end with the return
   contract; fresh session resumes from plan + `git log`. Never exceed ~60 turns / ~120K context.
6. **HUMAN RUNS ALL VALIDATION (default-on for Wave 2):** coder sessions NEVER invoke
   validation-runner, scripts/vrun.ps1, or Gradle. Per completed work item the coder outputs the
   exact command for the human (who runs it via `scripts/vrun.ps1`, the blocking wrapper) and
   reads the durable result next turn — zero agent validation turns.

## LANE TABLE (merge order; adjust to free slots on the day)

| Merge order | Worktree / branch | Cluster | Spec (fill after W2-S sessions) |
|---|---|---|---|
| 1st | build/worktrees/rp-26 · `rp-26-wip` | CL-27 money core | wave2/CL-27-spec.md |
| 2nd | build/worktrees/rp-27 · `rp-27-wip` | CL-05 currency contract (PR A then B) | wave2/CL-05-spec.md |
| 3rd | build/worktrees/rp-28 · `rp-28-wip` | CL-23 worker diagnostics (+A1) | wave2/CL-23-spec.md |
| 4th | build/worktrees/rp-29 · `rp-29-wip` | CL-15 failure semantics (+A1 if open) | wave2/CL-15-spec.md |
| 5th | build/worktrees/rp-30 · `rp-30-wip` | CL-21 cloud routing | wave2/CL-21-spec.md |
| 6th | build/worktrees/rp-31 · `rp-31-wip` | CL-22 guard hardening | wave2/CL-22-spec.md |
| any | build/worktrees/rp-32 · `rp-32-wip` | CL-09 CAS sweep | wave2/CL-09-spec.md |

Merge-order notes: CL-27 → CL-05 (money core before contract). CL-23/CL-15 after the merged
CL-19 state they build on. CL-21 after CL-17/CL-18 (already merged). CL-22 last (its hardened
guards enumerate violations from everything landed before it). CL-09 independent.

## WRAPPER (identical for every session — fill the three slots)

## Role

You are the implementation coder for one cluster of audit campaign CA-2026-09-21, Wave 2.
Your single source of truth is this spec file — read it IN FULL before any other action:

SPEC: docs/analyses and debug master/campaign CA-2026-09-21/wave2/<SPEC FILE>

BACKGROUND (read after SPEC, for context only):
docs/analyses and debug master/campaign CA-2026-09-21/wave1/ — Wave-1 completion state, and
docs/testing/generated/TRIAGE-2026-09-test-recovery.md — the triage rows your spec may fold in.

LANE BRANCH: <rp-NN-wip>

## Step 0 — environment verify (before anything)

1. `git rev-parse --abbrev-ref HEAD` must print your LANE BRANCH. If it does not, or the tree
   is not clean, STOP and report — do NOT create, checkout, or switch branches yourself.
2. Confirm the SPEC file exists and is readable. If missing, STOP and report.
3. If a `## GATE` stamp in the spec says BLOCKED (none should post-verification), stop and report.

## Execution order (per the spec)

1. Read the spec in full, including fences, validation section, and review gate.
2. Run the spec's Pre-implementation checks (pin-drift diff on allowed files; re-read target
   functions at current HEAD). Anchors taken at earlier pins: function names + signatures are
   authoritative — line numbers advisory.
3. Implement the work items exactly as specified, one commit per work item where sensible.
   Deviations are allowed ONLY where the real code makes the spec's instruction impossible or
   wrong — then STOP that work item (never redesign a shared contract mid-lane: stop and
   escalate), implement nothing speculative, and record it in your report.
4. Implement the spec's enumerated tests (add/update the named test classes; cover every listed
   boundary case — do not invent or drop coverage). Failures in tests your spec does NOT name:
   record as pre-existing and out of fence — never fix them.
5. **VALIDATION IS RUN BY THE HUMAN — never by you.** You must NOT invoke validation-runner,
   scripts/vrun.ps1, or Gradle in any form. BATCHING RULE: when a work item (or small batch) is
   complete and committed, output the EXACT command(s) the human should run, then END YOUR TURN:
   `pwsh scripts\vrun.ps1 -Worktree <rp-NN> -Profile targeted-unit-test -TestFilter '*ClassName*'`
   (or `-Profile compile`). The human runs it and reports the run id; your next turn reads
   `build/validation-runs/<id>/result.json` and the stdout/stderr logs directly — one read, no
   polling loop. While waiting, continue editing OTHER work items that do not depend on the
   result. A validation result you did not see in a result.json is NOT RUN — never claim one,
   and never report a human-run as your own execution.
6. Respect the blast-radius fence absolutely: if you find yourself about to edit a FORBIDDEN
   file, stop — that is a spec/coder mismatch, not something to resolve unilaterally.
7. Privacy rules from AGENTS.md apply to every line you write: controlled reason-code constants
   only, never raw exception text/paths/SQL; never swallow CancellationException; worker changes
   preserve WorkerExecutionGuard semantics; money/currency changes preserve rounding semantics.
8. Do NOT edit JOURNAL.md or anything under the campaign directory — the human appends your
   journal line at merge. Do not push, open PRs, or merge; leave your commits on the lane
   branch, then stop and report.

## Hard rules

- Work directly in THIS session; do not spawn subagents.
- ROTATION RULE: when your current work item (or batch) is committed AND its validation is
  recorded, END the session with the return contract. Do not accept additional work items that
  would push the session past ~60 turns.
- No reformatting, no unrelated refactors, no opportunistic cleanup — the spec's fence defines
  your entire diff surface.
- Do not weaken tests or guards to make anything pass; do not touch baselines/allowlists.

## Return contract (final message, ≤20 lines)

Per work item: done/deviated/blocked (one line each, deviation reason if any) · files changed ·
tests added/updated · validation commands ISSUED to the human + results read from result.json
(run ids) · the exact journal line for the human to append (format: `2026-09-NN T<HH:MM>+03:00 |
coder (direct session, sol high) | WAVE2-IMPLEMENT | <CL-ID> | <n>/<n> work items, tests <names>,
validation <human-run results> | <lane>`) · anything the reviewer must inspect closely. Nothing else.

---

## WORKTREE CREATION (human, per lane, when its spec + gate are ready)

```
git worktree add build/worktrees/rp-NN -b rp-NN-wip bug-fixes
copy local.properties into the new worktree   # gradle SDK path — fresh worktrees need it
```
(Lanes rp-20..rp-25 worktrees may be retired once you no longer want their logs:
`git worktree remove build/worktrees/rp-XX` + `git branch -d rp-XX-wip`.)
