# Release 1.0.1 — delivery strategy

```text
Owner direction (2026-10-19):
  1. Finish the test-suite work, then merge it into bug-fixes.
  2. Release 1.0.1.
  3. Merge the AI evolution work.
  4. Further features.
  Be efficient. Run fixes in parallel where advisable. Avoid frequent compilations.
  The organization is left to the release orchestrator.
Backlog: release-1.0.1-backlog.md (same folder)
```

## 1. Sequence and baselines

| Step | What | Baseline produced |
| --- | --- | --- |
| S0 | Now, while the owner finishes the test suite: read-only planning only (no source edits, no builds). Detail in section 2. | plans |
| S1 | Test-suite integration (section 3) | **B0** = `bug-fixes` with 1.0 + the test suite, gated |
| S2 | 1.0.1 waves W1..W4 on branch `release-1.0.1` from B0 (section 4) | candidates per wave |
| S3 | 1.0.1 final gate + device smoke + final acceptance, then fast-forward `bug-fixes` and tag | 1.0.1 |
| S4 | Merge `bug-fixes` into `feature/ai-evolution`, then merge the AI work | — |

S0 work:

- Batch plans with production write sets.
- Owner decisions.
- Cheap read-only checks.

Why no source work before S1: the test suite rewrites about 345 test files. 1.0.1 fixes written
earlier would collide with that and be redone. Production-side plans stay valid; exact test paths
are fixed after S1.

**Time bomb TB-1.** On 2026-11-01, `feature/ai-evolution` breaks:

- 16 WriteBarrier guard exemptions and the hanging-ledger review expire on 2026-10-31;
- from 2026-11-01 the guard test fails and the validation runner refuses to run.

If S4 has not happened by then, merge `bug-fixes` into `feature/ai-evolution` before that date.
This needs owner approval, because it is the owner's branch.

## 2. S0 — planning now (read-only)

- **release-planner** splits backlog batches into slices with disjoint production write sets, a
  test intent per slice, and a review tier. At most two planners/auditors at a time.
- **Cheap decisive checks:**
  - MONEY-06: does restore validate `exchange_rates` values?
  - FORE-04: what is the double-count risk?
  - MONEY-02: GreekBank, Revolut and SMS parser regexes.
  - DIAG-01: runtime count check on the owner database copy (counts only; owner approval).
- **Owner decisions** collected in one list: FORE-04, PRIV-09 design, product items.

## 3. S1 — test-suite integration

The test suite branch is based on 8671b844 (R28). 1.0 added R18..R32 and their tests on top of
that. The test branch also changes about 10 production files (B2-2c, B2-4, LOG-01 and others).

1. The owner commits the finished test-suite work on `chore/test-suite-audit`. The orchestrator does
   not edit that worktree while the owner's session is active.
2. In a clean integration worktree, the orchestrator merges `bug-fixes` into the test-suite branch
   (a merge, not a rebase: one conflict pass). Conflicts are expected in tests that 1.0 changed.
   Rules:
   - keep both the 1.0 regression assertions and the refactored structure;
   - never drop a 1.0 regression test;
   - production conflicts go to `reviewer-strict`.
3. **Gate B0, one cycle:**
   - static-guards;
   - app-check (one compile, plus all unit tests);
   - legacy-tests;
   - assemble-release + evidence.

   Connected tests run here only if the suite changed `androidTest`.
4. Fast-forward `bug-fixes` to B0. Update the backlog: LIFE-05, DIAG-11, LOG-01 and MISC-04 become
   FIXED, and every finding that refers to test names is re-checked.

## 4. S2 — 1.0.1 waves (compile-efficient pipeline)

### Principles

**1. Compile per wave, not per fix.**

- Each wave holds 4–8 small slices. They are coded one after another into the wave branch, then
  validated together:
  - static-guards: Python, no compile, run whenever useful;
  - **one** combined `targeted-unit-test` run (several test filters in one Gradle invocation);
  - **one** `app-check` at the end of the wave.
- Debug fixes are batched into a single re-run.
- The full gate (legacy, connected, release build, evidence) runs **once**, at S3.

**2. Parallel where it pays.** The slow resource is Gradle (about 33 min app-check, about 5–10 min
for a targeted compile). The pipeline therefore runs in three lanes at the same time:

- **Lane V:** the validation runner validates wave N in worktree A.
- **Lane W:** one coder writes wave N+1 slices in worktree B. The write sets are disjoint from wave
  N.
- **Lane R:** up to two read-only reviewers/planners: strict review of finished slices and planning
  of the next wave.

Only one coder writes at a time. Coding takes minutes; parallel writers would add merge risk
without saving compile time. Only one validation runs at a time across all worktrees.

**3. Review before compile.** `reviewer-strict` (money/privacy/Room/workers) and the cheaper review
run on the diff before the wave's compile. Must-fixes are applied in the same wave, so each wave
compiles once or twice, not once per review iteration.

**4. Integration by fast-forward.** Wave commits flow from B to A by fast-forward. Campaign records
are updated only in integrating commits.

### Waves (from the backlog; final membership is set by the S0 plans)

| Wave | Content | Risk / review | Validation |
| --- | --- | --- | --- |
| W1 quick wins | MONEY-01 Greek VAT; MONEY-05/07; PRIV-14; PRIV-02; TB-2 hanging-ledger review; F-R32-a test; F-R24b-a; DIAG-07; MONEY-04 CE catch | Low–medium; strict review for money | 1 targeted + static-guards + app-check |
| W2 cancellation and guard hygiene | MISC-01 + DIAG-11 (if not already in B0) + DIAG-04; F-R20-a leftovers; HZ-08 F1/F2 alignment; no baseline growth | Guard-sensitive: strict review + architecture-guardian | 1 targeted + static-guards + app-check |
| W3 diagnostics and privacy | Bounded-code sweep (DIAG-01/03/05/06/10, RET-01, LOG-02, PRIV-11); redacted-export scrub of the diagnostics tables; PRIV-03; PRIV-12 retention targets; F-R28-a..d | Privacy: strict review + privacy-security-guardian | 1 targeted + static-guards + app-check |
| W4 money and forecast | CURR-6 (planner decides whether Room is involved); FORE-04 (after the owner decision) / 01 / 02 / 05 / 06; MONEY-06; F-R20b-a/b/c Home windows | Money: strict review; Room: room-migration-guardian if the schema changes | 1–2 targeted + app-check |
| Final (S3) | Release gate | final reviewer (Opus/max) | full gate once + device smoke on the release APK |

**Kept out of 1.0.1** (go to the feature pipeline after S4 unless the owner says otherwise):

- Safe-to-spend calculation
- "Remove from group"
- Onboarding
- Privacy settings in Settings
- Tax/investment enablement
- PRIV-09 Keystore redesign

Docs and test-debt P3s ride along in whichever wave touches the same files.

### Expected Gradle cost

| Stage | Compiles | Approx. time |
| --- | --- | --- |
| B0 | 1 app-check + legacy + release build | about 45 min |
| Each wave | 1 targeted + 1 app-check | 45–50 min |
| S3 | full gate | about 60 min |

That is about 6 compiling cycles for the whole 1.0.1, compared with about 20+ if each fix were
validated on its own.

## 5. Gate rules (unchanged from 1.0)

- Never advance on a failed, stale or unknown result.
- Static guards fail closed.
- No guard weakening and no baseline growth without the owner.
- Shrinking a baseline is allowed.
- No destructive migration.
- No push or merge into owner branches without the owner.
- No device actions on the owner's phone.
- Bounded reason codes only.
