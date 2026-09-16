# Method & Classification — Fresh Debug Session 2026-09-04

## 1. What was validated

Every issue in the 12 `PIPELINE_N_CONSOLIDATED_ISSUES.md` documents (~240 issues) plus the 22 tracked universal issues, re-verified directly against the source tree at `d1fa9c68` (branch `atomicity-pr21-enforcement-final`). Nine parallel validation agents did the file-level work; every headline claim was additionally spot-checked by the orchestrator.

## 2. Static-only rule (deliberate)

No Gradle, compilation, test execution, lint, or KSP was run. This is the repo's own standing convention for debug sessions (`.opencode/agents/orchestrator.md`: "No agent/tool may run build, compile, Gradle… The human will run validation manually later") and was additionally required because a guardrail session owns builds in the `gr-00`/`gr-00-local` worktrees right now.

Consequences:
- Verdicts are based on reading code paths, call sites, diffs, and tests — not on executing them.
- Any verdict that genuinely cannot be determined statically is marked `NOT_VERIFIABLE_STATICALLY` (exactly one issue: P12-P0-01).
- Before closing any issue marked `VERIFIED_FIXED` for milestone purposes, a targeted test run is still recommended. Suggested commands are listed per pipeline doc, section 6.

## 3. Status vocabulary used in this session

| Fresh status | Meaning |
|---|---|
| `VERIFIED_FIXED` | Fix located in current source with file:line evidence |
| `VERIFIED_OPEN` | Issue still present in current source with file:line evidence |
| `VERIFIED_PARTIAL` | Some of the issue's scope fixed, remainder present (evidence for both) |
| `REGRESSED` | Was fixed in a past commit; broken again at HEAD (evidence: fix commit + current line) |
| `CLAIMED_FIXED_UNVERIFIED` | Doc claims fixed; spot-check did not find the fix or the evidence was inconclusive |
| `NOT_VERIFIABLE_STATICALLY` | Cannot be settled without runtime (marked, not guessed) |

## 4. Classification rules

**`UNIVERSAL`** — the issue is an instance of a recurring cross-pipeline pattern. Known universal categories (from the U-PR program): CancellationException swallowed in broad catches (U-PR1); TOCTOU read-outside-transaction (U-PR2); mixed-currency money math / silent currency fallback (U-PR3); maintenance/restore barrier mishandling (U-PR4); privacy contract gaps (U-PR5); worker-guard contract gaps (U-PR6); direct clock reads / DST-unsafe arithmetic (U-PR7); side-effect double dispatch / trigger-type collisions (U-PR8). New categories discovered this session are listed in `02_UNIVERSAL_ISSUES_FRESH_VALIDATION.md` §3 (candidates U-PR9+).

**`PIPELINE`** — specific to one feature area.

**`DOC-DRIFT`** — a discrepancy between the docs/trackers and code reality. Recorded per pipeline in §3.1 of each pipeline doc. Both directions count: "marked open, actually fixed" and "marked fixed, actually open/partial".

## 5. Evidence standards

- Every verdict cites `path:line` in the current checkout, or a commit SHA from `git log/show`.
- Claims by the older audit (`FIXED_CLAIMS_VALIDATION_AUDIT_v4.md`) were treated as leads, not facts, and re-verified at HEAD — the audit predates ~40 commits including `bb2a6f18` (compile repairs) and `25c636e1` (June fix wave). Several audit verdicts changed as a result (see per-pipeline docs).
- Architecture docs (`docs/architecture/LEGAL_PATHS.md`, `CODEBASE_SEGMENTS.md`, `dao-map.md`, `ENGINE_INTERACTION_MAP.md`) were used as the *expected law*; code is the *source of truth*; disagreements are findings, not tie-breakers.

## 6. Review rubrics applied (from the repo's own guardian directives)

- Lifecycle legal paths: expense/receipt/recurring mutations must go through their coordinators; no direct DAO writes from forbidden layers.
- Worker contract: `WorkerExecutionGuard`, write/restore barriers, retry idempotency, cancellation propagation, intentional timeouts, structured reason codes, metrics only after success, notification permission never blocking unrelated DB work.
- Privacy: no raw notification/OCR/receipt text, no `e.message`/stack traces/file paths/SQL messages persisted, controlled reason-code constants, fail-closed consent gates, cleanup workers must run.
- Room: entity/DAO consistency, transaction usage, no bulk materialization of sensitive payloads, raw-query injection risk.

## 7. Limitations

- Line numbers are valid at `d1fa9c68`; the in-flight `gr-00-local` (GR-10B/GR-11) branch will move many `scripts/verify_*` files but not the app-source evidence.
- Static analysis cannot prove absence of runtime-only issues (race timing, device-specific behavior). The TOCTOU and DST findings are logically demonstrated, not reproduced.
- The `gr-00-local` time sweep (commit `8b45879e`, ~49 remaining `System.currentTimeMillis()` sites + SynthesisEngine Calendar debt) is NOT an ancestor of HEAD; leftovers reported here are real at HEAD and expected to close when that branch lands.
