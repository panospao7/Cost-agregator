# STAGE 3 — WAVE 1 REVIEW + COMPLETION PROMPTS (CA-2026-09-21) — REV 2: RE-REVIEW ROUND

> **REV 2 (2026-09-25), supersedes the round-1 session plan below for rp-20/21/22.**
> Round 1 verdicts landed (CL-18/17/19-review-completion.md on each lane) and completion work
> followed. This round RE-REVIEWS that completion work. rp-23/rp-24 still need their FIRST review
> (round-1 method, unchanged). All lanes are now rebased onto bug-fixes `ead80016` (rp-25 test
> recovery merged): unit tests compile, triage file exists on the base.

## REV-2 SESSION PLAN (astra, sequential; open codex INSIDE the named worktree)

| # | Model | Worktree | Scope |
|---|---|---|---|
| RR1 | astra @ high | build/worktrees/rp-20 | re-review CL-18 completion (delta 86e7f244..HEAD) |
| RR2 | astra @ high | build/worktrees/rp-21 | re-review CL-17 completion (delta 7b8bc1cc..HEAD) |
| RR3 | astra @ **xhigh** | build/worktrees/rp-22 | re-review CL-19 completion — committed + **uncommitted** state |
| RR4 | astra @ high | build/worktrees/rp-23 (cd rp-24) | FIRST review of CL-01 + CL-29 (round-1 method, base ad412aec) |

## REV-2 SESSION SCOPE blocks

**RR1:** `Re-review lane rp-20 completion: delta 86e7f244..HEAD against the continuation work items (WI-3, WI-4, WI-5 and the WI-1 exit-defect fix) defined in CL-18-review-completion.md on this lane. All completion work is committed; tree should be clean.`
**RR2:** `Re-review lane rp-21 completion: delta 7b8bc1cc..HEAD against the continuation work items defined in CL-17-review-completion.md on this lane (verdict baseline was 1/11 DONE-CORRECT; WI-10 NOT-STARTED with fence blocker B-1; regression R-1 SSL-catch-bypasses-retry). Verify each plan item now DONE-CORRECT / PARTIAL / NOT-STARTED.`
**RR3:** `Re-review lane rp-22 completion: verdict the continuation work items WI-6, WI-7, WI-8, WI-9, WI-10 and WI-4a defined in CL-19-review-completion.md on this lane, over BOTH the committed delta ad412aec..HEAD AND the uncommitted working tree (git diff HEAD — 12 dirty paths). The two CRITICAL regressions R-1 and R-2 from the first review must each be verified fixed or explicitly open. Do NOT commit, stash, discard, or edit the uncommitted work — review it read-only and state which plan items it satisfies.`
**RR4:** `First review of lane rp-23 against CL-01-notification-capture-privacy-extraction.md (base ad412aec); then lane rp-24 against CL-29-group-split-settlement-correctness.md (base ad412aec)`

## REV-2 TEMPLATE DELTAS (apply on top of the TEMPLATE below; §§0–2 structure unchanged)

1. **§0 extra**: after pin/base checks, run the delta discovery for re-reviews:
   `git log --oneline --diff-filter=A -- "docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-XX-review-completion.md"` — the commit it prints is the review baseline; the delta under review is that commit..HEAD (+ working tree for RR3).
2. **§1 role**: you are the RE-REVIEWER. The governing document is the lane's existing
   `CL-XX-review-completion.md` (its continuation work items define DONE). Verdict each plan work
   item: DONE-CORRECT / DONE-DEFECTIVE / PARTIAL / NOT-STARTED, with diff evidence at current
   HEAD. Regression hunt covers ONLY the delta (+ dirty state for RR3) — do not re-litigate
   already-verdicted base work. Your artifact is a NEW file
   `docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-XX-rereview-completion.md`,
   committed on the lane (docs-only). Do not modify the first review artifact.
3. **§2 method**: per plan WI, read the plan's acceptance intent, then the delta that claims it.
   RR3 additionally: `git diff HEAD` is reviewable text; quote it like committed code, and your
   artifact's per-WI rows must state whether the satisfying change is committed or uncommitted.
   Cross-lane flags still apply (DatabaseBackupRepositoryImpl.kt is shared across rp-20/21/22).
4. **§3 hard rules** unchanged, plus: never stage/commit/discard the lane's uncommitted work
   (RR3); a verdict of DONE-CORRECT still requires named-test execution evidence per the plan —
   compile PASS is never execution evidence.
5. **§4 return contract**: per plan-WI verdict line · regression count + top risks · for RR3:
   R-1/R-2 fixed-or-open · artifact path + lane commit sha · journal line:
   `2026-09-25T<HH:MM>+03:00 | rereview-completion (astra <effort>) | WAVE1-REREVIEW | <CL-ID> | <n>/<n> plan items DONE-CORRECT, <n> regressions | <lane>`

---

# ROUND 1 (historical reference — rp-23/24 first-review method still authoritative)

Purpose: astra reviews each lane's actual diff against its spec, hunts regressions, and writes a
direct-fix completion plan so a sol coder finishes the lane to done. Lane status as of 2026-09-23:

| Lane | Cluster | State (vs base ad412aec) |
|---|---|---|
| rp-20 | CL-18 | 2 commits, both findings, clean tree — looks COMPLETE, unreviewed, no validation evidence |
| rp-21 | CL-17 | 9 commits: WI-1/2/3/5/6/7/8/9/11 done, **WI-4 partial, WI-10 not started**, 25 files |
| rp-22 | CL-19 | 2 commits: WI-4 implemented (tests never ran), WI-1/2/3/5 partial; coder report on disk (untracked); targeted tests FAIL on out-of-fence test compilation |
| rp-23 | CL-01 | 2 commits, both findings, clean tree — looks COMPLETE, unreviewed, no validation evidence |
| rp-24 | CL-29 | 3 commits (WI-1 + fixup, WI-2), 9 files +772/−26, clean tree — looks COMPLETE, unreviewed, no validation evidence |

## SESSION PLAN (astra, sequential; R1 opened in rp-20's worktree, cd'ing to the others)

| # | Model | Working directory | Scope |
|---|---|---|---|
| R1 | astra @ high | build/worktrees/rp-20 (cd to rp-23, then rp-24) | CL-18 + CL-01 + CL-29 review; completion plans if gaps |
| R2 | astra @ high | build/worktrees/rp-21 | CL-17 review + WI-4/WI-10 completion |
| R3 | astra @ xhigh | build/worktrees/rp-22 | CL-19 review + completion of WI-1/2/3/5 + regression diagnosis |

## SESSION SCOPE blocks

**R1:** `Review lane rp-20 against CL-18-privacy-gate-fail-closed-cancellation.md (base ad412aec); then lane rp-23 against CL-01-notification-capture-privacy-extraction.md; then lane rp-24 against CL-29-group-split-settlement-correctness.md`
**R2:** `Review lane rp-21 against CL-17-bounded-diagnostics-ui-error-leakage.md (base ad412aec); completion plan for WI-4 (partial) and WI-10 (not started)`
**R3:** `Review lane rp-22 against CL-19-restore-backup-fail-closed-state-machine.md (base ad412aec); diagnose the 5 out-of-fence test compile failures; completion plan for WI-1/2/3/5 and WI-4 test execution`

---

## TEMPLATE (paste per session)

## 0. Environment verify

Working directory: <the worktree from the session table>. Verify:
`git rev-parse --abbrev-ref HEAD` = the lane branch; base is ad412aec
(`git merge-base ad412aec HEAD` prints ad412aec). Record uncommitted/untracked paths.

## 1. Role

You are the completion reviewer for one Wave-1 lane. Three jobs, in order:
(1) VERDICT each spec work item DONE-CORRECT / DONE-DEFECTIVE / PARTIAL / NOT-STARTED,
with diff evidence (file:line). (2) HUNT REGRESSIONS the diff introduces. (3) Write a
direct-fix completion plan so a sol coder can finish the lane.

You are READ-ONLY for source code. You never run validation (a completion coder will).
You write exactly one artifact: `docs/analyses and debug master/campaign CA-2026-09-21/wave1/<CL-ID>-review-completion.md`
inside this worktree, committed to the lane as a docs-only commit. If the coder left stray
untracked files (e.g. rp-22's implementation report at
`docs/analyses and debug master/CL-19-wave1-implementation-report-2026-09-23.md`), read them as
evidence and commit them on the lane. You do NOT touch JOURNAL.md — return the journal line.

## 2. Method

1. Read the spec in full (worktree copy under the campaign dir), then the coder's report if any.
2. Inventory: `git log --oneline ad412aec..HEAD`, `git diff --stat ad412aec..HEAD`, then the
   full diff file by file.
3. Per work item: does the diff implement it per spec? Do the tests match the spec's enumerated
   boundary cases (named classes, no invented/dropped coverage)? Check validation evidence in
   `build/validation-runs/` of this worktree — if the named tests never executed, the completion
   plan must include executing them.
4. Regression hunt on NEW code: new failure modes; files outside the spec's ALLOWED fence;
   privacy breaches in new lines (raw e.message/stack traces/paths); swallowed
   CancellationException; WorkerExecutionGuard semantics broken; test weakening to pass.
   R2 extra (mechanical gate): grep the 16 CL-17 files for surviving raw `e.message` /
   throwable-to-log pass-throughs — the count must be ZERO or each survivor justified; verify
   each site's reason-code constants match the spec's table.
   R3 extra (the decisive question): the 5 out-of-fence test compile failures
   (`KeystoreInstallationSecretHashingTest`, `LegacyDataConsistencyCheckerTest`,
   `ReceiptLifecycleCoordinatorTest`, `BackupRestoreViewModelPrivacyDenialTest`,
   `ExportOptionsViewModelPrivacyDenialTest`) — read `build/validation-runs/*/stderr.log`; decide
   REGRESSION (errors reference symbols this lane changed) vs PRE-EXISTING at ad412aec
   (check out those test files at the base via `git show ad412aec:<path>` and compare against the
   errors). State the verdict per file with the compile-error line as evidence. Also scrutinize
   the coder's own flagged risks: persisted-NORMAL window during `exit(false)` scheduling,
   present-but-null mode values, post-destructive rollback/import failure paths,
   `DatabaseBackupRepositoryImplTest` not updated, worker scheduling before NORMAL observable.
5. Cross-lane flags (R2/R3): `git diff --name-only ad412aec..rp-2X-wip` for sibling W1 lanes;
   where this lane and a sibling both touch a file (notably `DatabaseBackupRepositoryImpl.kt`
   across rp-20/21/22), flag semantic interaction risks for the human's merge order
   (rp-20 → rp-21 → rp-22). Flag only; do not resolve.
6. Write the completion artifact: per-WI verdict table; regression findings (severity-tagged);
   then DIRECT-FIX work items in the same spec format (location at CURRENT HEAD, change,
   acceptance criteria, tests, validation profile), continuing WI numbering (WI-4a, WI-10 …).
   Same fences as the original spec; anything the original spec forbids stays forbidden.
   Commit it on the lane (docs-only).

## 3. Hard rules

- No subagents. No production-code edits. No validation runs. No JOURNAL/campaign edits except
  the single review-completion file (+ committing the coder's stray report).
- "Looks done" is not DONE-CORRECT without reading the diff hunk. "Compile passed" is never
  test evidence.
- Never narrate unearned completion; the artifact on the lane is the deliverable.

## 4. Return contract (≤20 lines)

Per WI: verdict (one line). Regression findings: count + top risks. R3: per-file verdict on the
5 out-of-fence test failures. Cross-lane flags. Completion-artifact path + lane commit sha.
Journal line for the human:
`2026-09-23T<HH:MM>+03:00 | review-completion (astra <effort>) | WAVE1-REVIEW | <CL-ID> | <n>/<n> DONE-CORRECT, <n> regressions, out-of-fence verdicts <...> | <lane>`
