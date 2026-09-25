# WAVE 1 RE-REVIEW — FULLY ASSEMBLED PASTE BLOCKS (2026-09-25)

Four independent codex sessions. Each block below is COMPLETE — copy from `## 0.` to the end of
that block only, paste into a fresh session opened inside the named worktree, on the named model.
Do not paste multiple blocks into one session. Do not paste the whole file.

Lanes are rebased on bug-fixes `ead80016` (rp-25 test recovery merged; unit tests compile).

---

## BLOCK RR1 — rp-20 (CL-18) — astra @ HIGH — re-review of committed completion

## 0. Environment verify

Working directory: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-20`.
Verify: `git rev-parse --abbrev-ref HEAD` = rp-20-wip; base contains ead80016
(`git merge-base --is-ancestor ead80016 HEAD && echo OK`); `git status --porcelain` must be empty.

## 1. Role

You are the RE-REVIEWER for lane rp-20 (cluster CL-18). The GOVERNING document is the first
review artifact on this lane:
`docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-18-review-completion.md`
Its verdicts (WI-1 DONE-DEFECTIVE, WI-2 PARTIAL) and its continuation work items WI-3, WI-4, WI-5
define what DONE means now. The original spec `wave1/CL-18-privacy-gate-fail-closed-cancellation.md`
is background only.
The completion delta under review is commit `86e7f244..HEAD` (the remediation landed after the
first review). All completion work is committed; the tree must be clean.

Three jobs: (1) verdict each plan work item (WI-1 fix, WI-3, WI-4, WI-5) DONE-CORRECT /
DONE-DEFECTIVE / PARTIAL / NOT-STARTED with diff evidence at current HEAD; (2) hunt regressions in
the delta only; (3) write the closing artifact (see artifact rule).

You are READ-ONLY for source code; you never run validation. DONE-CORRECT requires the plan's
named-test EXECUTION evidence or an explicit statement of why it is still absent — compile PASS is
never execution evidence. Note: the merged base now contains rp-25's unit-test compilation repairs
and `docs/testing/generated/TRIAGE-2026-09-test-recovery.md`; named tests CAN now run via
validation-runner by a later coder — your artifact states exactly which commands remain.

Artifact: write a NEW file
`docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-18-rereview-completion.md`,
committed on the lane as a docs-only commit. Do not modify the first review artifact.
Do not touch JOURNAL.md — return the journal line.

## 2. Method

1. Read the first review artifact in full (esp. WI-1 defect: unconditional `exit(false)` after
   blocking at Repo:770-772; and continuation items WI-3, WI-4, WI-5).
2. Inventory the delta: `git log --oneline 86e7f244..HEAD`, `git diff --stat 86e7f244..HEAD`,
   then the full diff file by file.
3. Per plan work item: does the delta implement it? Do test additions match the plan's required
   coverage? Is the WI-1 `exit(false)` defect actually removed?
4. Regression hunt on the delta only: new failure modes; files outside the original spec fence;
   privacy breaches in new lines; swallowed CancellationException; test weakening.
   Cross-lane flag: this lane shares `DatabaseBackupRepositoryImpl.kt` with rp-21/rp-22 — flag
   semantic interaction risks for merge order (rp-20 → rp-21 → rp-22). Flag only, do not resolve.
5. Write `CL-18-rereview-completion.md`: per-plan-WI verdict table; regression findings
   (severity-tagged); the exact remaining steps to make this lane mergeable (including any
   validation-runner commands a coder must run). Commit it (docs-only).

## 3. Hard rules

No subagents. No production-code edits. No validation runs. No JOURNAL/campaign edits except the
single new artifact. "Looks done" is not DONE-CORRECT without reading the hunk. Never narrate
unearned completion.

## 4. Return contract (≤20 lines)

Per plan-WI verdict line · regression count + top risks · cross-lane flags · artifact path + lane
commit sha · journal line:
`2026-09-25T<HH:MM>+03:00 | rereview-completion (astra high) | WAVE1-REREVIEW | CL-18 | <n>/<n> plan items DONE-CORRECT, <n> regressions | rp-20-wip`

---

## BLOCK RR2 — rp-21 (CL-17) — astra @ HIGH — re-review of committed completion

## 0. Environment verify

Working directory: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-21`.
Verify: `git rev-parse --abbrev-ref HEAD` = rp-21-wip; base contains ead80016; working tree clean.

## 1. Role

You are the RE-REVIEWER for lane rp-21 (cluster CL-17). GOVERNING document:
`docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-17-review-completion.md`
Its baseline verdict was 1/11 DONE-CORRECT (WI-11), 1 DONE-DEFECTIVE (WI-6, regression R-1: the new
SSL catch bypasses the retry predicate), 8 PARTIAL, WI-10 NOT-STARTED with fence blocker B-1. Its
continuation/direct-fix work items define what DONE means now. Original spec
`wave1/CL-17-bounded-diagnostics-ui-error-leakage.md` is background.
The completion delta under review is commit `7b8bc1cc..HEAD`.

Three jobs: (1) verdict each of WI-1..WI-11 anew at current HEAD (carrying the first review's
evidence — a WI that was PARTIAL for missing test execution is now DONE-CORRECT only if execution
evidence exists or the plan's alternative evidence path was satisfied); (2) regression hunt on the
delta only — especially verify R-1 (SSL catch / retry predicate) is fixed; (3) write the closing
artifact.

READ-ONLY for source; never run validation. Compile PASS is never execution evidence. The merged
base has rp-25's test-recovery: named tests can now actually run — your artifact lists the exact
remaining validation-runner commands per WI.

Artifact: NEW file `.../wave1/CL-17-rereview-completion.md`, committed docs-only on the lane.
Do not modify the first review artifact. Do not touch JOURNAL.md.

## 2. Method

1. Read the first review artifact in full.
2. Inventory: `git log --oneline 7b8bc1cc..HEAD`, `git diff --stat 7b8bc1cc..HEAD`, full diff.
3. Per WI (1..11): current verdict + evidence at HEAD. WI-10: was the fence blocker B-1 resolved
   or worked around — if worked around, was it in-fence? WI-6: is R-1 fixed?
4. Mechanical gate (from the first review, still binding): grep the 16 CL-17 files for surviving
   raw `e.message` / throwable-to-log pass-throughs — count must be ZERO or each survivor
   justified against the spec's reason-code table.
5. Regression hunt on the delta only. Cross-lane flag: shared `DatabaseBackupRepositoryImpl.kt`
   with rp-20/rp-22 — flag merge-order interaction risks only.
6. Write `CL-17-rereview-completion.md`: per-WI verdict table; regression findings; the exact
   remaining steps to make this lane mergeable. Commit (docs-only).

## 3. Hard rules

No subagents. No production edits. No validation runs. No JOURNAL/campaign edits except the single
new artifact. Never narrate unearned completion.

## 4. Return contract (≤20 lines)

Per-WI verdict lines · mechanical-gate survivor count · R-1 status · regression count + top risks ·
cross-lane flags · artifact path + lane commit sha · journal line:
`2026-09-25T<HH:MM>+03:00 | rereview-completion (astra high) | WAVE1-REREVIEW | CL-17 | <n>/<n> DONE-CORRECT, R-1 <fixed/open>, <n> regressions | rp-21-wip`

---

## BLOCK RR3 — rp-22 (CL-19) — astra @ XHIGH — re-review incl. UNCOMMITTED work

## 0. Environment verify

Working directory: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-22`.
Verify: `git rev-parse --abbrev-ref HEAD` = rp-22-wip; base contains ead80016.
This lane carries ~12 UNCOMMITTED paths (production + tests + untracked
`PendingWorkerTestFactory.kt`) — in-progress completion work. You review it READ-ONLY: never
stage, commit, stash, discard, or edit it.

## 1. Role

You are the RE-REVIEWER for lane rp-22 (cluster CL-19 — restore/backup fail-closed state machine,
the most destructive-path surface in the app). GOVERNING document:
`docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-19-review-completion.md`
Its baseline: 0/5 DONE-CORRECT, 5 source-evidenced regressions (2 CRITICAL: R-1, R-2; 3 MAJOR:
R-3, R-4, R-5), out-of-fence compile failures confirmed PRE-EXISTING. Its continuation items
WI-6, WI-7, WI-8, WI-9, WI-10 and WI-4a define DONE. Original spec
`wave1/CL-19-restore-backup-fail-closed-state-machine.md` is background.

Review scope is BOTH the committed delta `ad412aec..HEAD` AND the uncommitted working tree
(`git diff HEAD` + untracked files) — quote dirty code exactly like committed code, and each
verdict row states whether the satisfying change is committed or uncommitted.

Three jobs: (1) verdict WI-1..WI-5 anew plus each continuation item WI-6..WI-10, WI-4a at current
state; explicitly rule R-1..R-5 FIXED or OPEN with evidence; (2) regression hunt on the new delta
and dirty state only; (3) write the closing artifact with the exact remaining steps to mergeable.

READ-ONLY for all source (committed and dirty); never run validation. Compile PASS is never
execution evidence; DONE-CORRECT requires the plan's named-test execution (the merged base now
permits test runs — list the exact validation-runner commands per WI in your artifact).

Artifact: NEW file `.../wave1/CL-19-rereview-completion.md`, committed docs-only on the lane
(only the artifact — never the dirty work). Do not modify the first review artifact. Do not touch
JOURNAL.md.

## 2. Method

1. Read the first review artifact in full (esp. R-1..R-5 and WI-6..WI-10/WI-4a).
2. Inventory committed: `git log --oneline ad412aec..HEAD`, `git diff --stat ad412aec..HEAD`.
   Inventory dirty: `git status --porcelain`, `git diff HEAD`, untracked files.
3. Per WI and per R: verdict at current state, evidence file:line, committed-or-dirty tag.
4. Regression hunt on the delta + dirty state: the state machine's fail-closed semantics are the
   priority — durable-or-fail journal writes, corrupt/unknown journal → CRITICAL_RECOVERY_REQUIRED,
   no silent mode resets, bank terminal-status barrier, worker rescheduling after rollback.
   Cross-lane flag: shared `DatabaseBackupRepositoryImpl.kt` with rp-20/rp-21 — flag merge-order
   interaction risks only.
5. Write `CL-19-rereview-completion.md`: per-WI + per-R verdict table (committed/dirty tags);
   regressions; exact remaining steps to mergeable, ordered. Commit (docs-only).

## 3. Hard rules

No subagents. No source edits (committed or dirty). No validation runs. Never stage/commit/stash/
discard the uncommitted work. No JOURNAL/campaign edits except the single new artifact. Never
narrate unearned completion.

## 4. Return contract (≤20 lines)

Per-WI + per-R verdict lines (committed/dirty tags) · regression count + top risks · cross-lane
flags · artifact path + lane commit sha · journal line:
`2026-09-25T<HH:MM>+03:00 | rereview-completion (astra xhigh) | WAVE1-REREVIEW | CL-19 | <n>/<n> DONE-CORRECT, R-1..R-5 <fixed/open>, <n> regressions | rp-22-wip`

---

## BLOCK RR4 — rp-23 + rp-24 (CL-01 + CL-29) — astra @ HIGH — FIRST review

## 0. Environment verify

Working directory: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-23`.
Verify: HEAD = rp-23-wip; base contains ead80016; clean. Later `cd ../rp-24` for the second review
(HEAD = rp-24-wip, clean).

## 1. Role

You are the FIRST reviewer for two lanes that have never been reviewed. Per lane: (1) VERDICT each
spec work item DONE-CORRECT / DONE-DEFECTIVE / PARTIAL / NOT-STARTED with diff evidence at HEAD;
(2) HUNT REGRESSIONS in the lane's diff; (3) write a direct-fix completion plan.

READ-ONLY for source; never run validation. Compile PASS is never execution evidence; if named
tests never executed, the completion plan must include executing them (the merged base now permits
test runs — name the exact validation-runner commands).

Artifacts (one per lane), committed docs-only on their respective lanes:
`docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-01-review-completion.md`
`docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-29-review-completion.md`
Do not touch JOURNAL.md — return the journal lines.

## 2. Method (per lane)

1. Read the spec in full (rp-23: `wave1/CL-01-notification-capture-privacy-extraction.md`;
   rp-24: `wave1/CL-29-group-split-settlement-correctness.md`).
2. Inventory: `git log --oneline ad412aec..HEAD`, `git diff --stat ad412aec..HEAD`, full diff.
3. Per work item: implemented per spec? Tests match the spec's enumerated boundary cases?
   Validation evidence in `build/validation-runs/`?
4. Regression hunt: new failure modes; out-of-fence files; privacy breaches in new lines;
   swallowed CancellationException; test weakening. CL-29-specific: verify the unequal-split
   coercion fix against the reviewer counterexamples (requested-vs-persisted share; settlement
   net-balance toward zero) and the member-join trigger; confirm whether the display/persist
   inconsistency was addressed or remains open.
5. Write the artifact: per-WI verdict table; regressions (severity-tagged); DIRECT-FIX items in
   spec format if anything is not DONE-CORRECT. Commit docs-only.

## 3. Hard rules

No subagents. No production edits. No validation runs. No JOURNAL/campaign edits except the single
artifact per lane. Never narrate unearned completion.

## 4. Return contract (≤20 lines)

Per lane: per-WI verdicts · regression count + top risks · artifact path + lane commit sha ·
journal lines:
`2026-09-25T<HH:MM>+03:00 | review-completion (astra high) | WAVE1-REVIEW | CL-01 | <n>/<n> DONE-CORRECT, <n> regressions | rp-23-wip`
`2026-09-25T<HH:MM>+03:00 | review-completion (astra high) | WAVE1-REVIEW | CL-29 | <n>/<n> DONE-CORRECT, <n> regressions | rp-24-wip`
