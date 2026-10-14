# Release 1.0 assurance handoff

```text
Status: PENDING (Phase 0 PARTIAL; coverage plan awaiting human approval)
Current source/artifact: local HEAD 84d819be6e288b82649a1c125fbcaa10f026d0d9 (pushed;
  origin/bug-fixes = 84d819be). The owner committed 14a2e6ce (perf runner), 73a22f44
  (agent guidance) and 84d819be (kit) DURING Phase 0. app/ == ea446316 (verified).
  Runner/build inputs are those of 14a2e6ce. No release artifact.
Current approved phase/scope: Phase 0 + coverage planning ONLY (2026-10-14). Nothing beyond.
Mode: delegated. User authorized named release subagent profiles for this lane
  on 2026-10-14. Limits: at most 2 read-only workers, 1 writer, 1 validation owner, no
  nested delegation. Historical Wave 5 and performance-lane no-subagent
  constraints are unchanged for those lanes.
Decisions / historical constraints: Wave 5 CLOSED under owner acceptance with
  independent remediation re-review WAIVED (original independent FAIL preserved).
  Release lane treats that delta as not independently reviewed (R-1).
Changed paths (this session, artifacts only):
  docs/releases/release-1.0-assurance/release-1.0-baseline.md (new)
  docs/releases/release-1.0-assurance/release-1.0-coverage-matrix.md (new)
  docs/releases/release-1.0-assurance/release-1.0-regression-ledger.md (new, seed)
  workflows/active/release-1.0-assurance-handoff.md (this file)
Confirmed/refuted/open findings: none (no audit run). Seeded closure claims are UNVERIFIED.
Review/guardian verdicts: none in this lane.
Current vs historical validation: Wave 5 candidate-only runs (6x PASS, isolated
  clone, content-equivalent to ea446316) are baseline evidence only. No validation
  was run in this lane. Remote CI: no runs on bug-fixes since 2026-06-18.
```

## Approval log

- 2026-10-14: the user replied "proceed" to the Phase 0/coverage report. Recorded as approval
  of the coverage/dispatch plan and of slice RG-01 ONLY (discovery + independent
  refutation + persistence, then stop). It is NOT approval of remediation, validation,
  commit/push, later slices, or answers to B-1..B-3/H-1..H-4 (still open).
- RG-01 discovery dispatched: release-regression-auditor, requested Opus 5.5/xhigh.

## Next action (requires human approval)

Approve or amend the coverage plan, then dispatch slice RG-01 only:

- Slice: RG-01, CL-19 restore/backup fail-closed state machine (P07-003/004/005
  P0, P10-001 P1, I01-001) plus classification of RP-03 S2 asset-recovery gaps.
- Discovery: `release-regression-auditor` with explicit Opus 5.5/xhigh request
  (T1 override of the Sonnet/high pin; record actual model/effort).
- Refutation: fresh `release-finding-verifier` (Opus 5.5/xhigh), not the auditor.
- Write scope for agents: NONE. The lead persists results to the regression ledger,
  `release-1.0-verification-ledger.md` and this handoff.
- Pin check before/after: `git diff --quiet ea446316 -- app` and no untracked app files;
  record `git rev-parse HEAD`.
- Stop after RG-01: no remediation, no validation, no later slices.

## Human prerequisites (from baseline dossier)

- B-1: performance lane (gradle.properties, scripts/validation-runner.ps1,
  test-shards.json) is now committed locally in 14a2e6ce. Owner must confirm it
  belongs on the release line and state the review status of its later changes.
  A runner self-check at the pin comes before any release queue. Not needed for static slices.
- H-4: RESOLVED — the three Phase 0 commits were owner-requested and are pushed on origin/bug-fixes.
- B-2: no runner release APK/AAB profile; CI release-check not running. Approve
  a narrow profile or restore CI before the artifact gate.
- B-3: production signing/distribution process (release is debug-signed).
- H-1: shipped 1.0 feature scope (bank sync appears debug-only in code; demo tax
  and static negotiation providers bound in release; debug screen reachability).
- H-2: supported upgrade/backup policy and device/API matrix, stabilization length.
- H-3: PUBLIC repo tracks an expense .db backup, 28 receipt photos and session
  transcripts. Owner decides whether they contain real personal data. This lane
  does not read them.

## Resume protocol

1. Read this file, then `docs/prompts/release-1.0-assurance/README.md`.
2. Verify `git rev-parse HEAD` (last recorded 84d819be) and that app/ is unchanged
   vs ea446316. If app/ changed, re-run Phase 0 for the delta before continuing.
   If only HEAD moved, record the new commits and their paths.
3. Read `docs/releases/release-1.0-assurance/release-1.0-baseline.md` and
   `release-1.0-coverage-matrix.md` (section 6 queue). Do not exceed the recorded
   approved scope.
4. Kit drift: README/earlier handoff name `money-w5-savings-b8-20261012-handoff.md`
   as the latest Wave 5 packet. The actual latest is
   `workflows/active/money-w5-independent-remediation-20261013-handoff.md`.

## Setup history (2026-10-13)

The kit and profiles were authored at HEAD 4137afdf on mixed Wave 5 WIP. No phase
or validation was run at setup, and actual profile loading was NOT RUN.
In this session (2026-10-14) two `scout` read-only indexing tasks ran. The profile
pin requests Haiku 5.5/medium, but the actual model/effort was not independently
confirmed from /tasks. Their reports are summarized
in the coverage matrix and regression ledger seed.
