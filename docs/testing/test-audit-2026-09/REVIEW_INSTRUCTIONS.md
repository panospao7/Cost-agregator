# Test Suite Audit 2026-09 — Reviewer Instructions

**Scope:** every `.kt` file under `app/src/test` and `app/src/androidTest` (675 files, ~180k LOC).
**Out of scope:** anything under `GR00-worktree/` (worktree duplicate), production code changes, `scripts/` guardrails.
**You are reviewing one batch.** Your assigned files are listed in `batch-plans/batch-NN-<slug>.txt` (tab-separated: path relative to `app/src/`, LOC).
**Your output:** one markdown file `batches/batch-NN-<slug>.md` in this directory. You write ONLY that file. Everything else is read-only.

## Hard constraints

- Do NOT run Gradle or any build/test command. This is a static review.
- Do NOT modify production code, test code, or any file except your own batch MD.
- Do NOT paste large code excerpts into the MD — summarize and cite `path:line`.
- Repo root: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker`.

## Required context reading (before reviewing files)

1. `docs/testing/MASTER_TESTING_STRATEGY.md` — the quality bar. Core principle: **a test must verify expected behavior, not implementation details**. Mock-verification-only tests, tautologies, and "no crash" assertions are low value.
2. `docs/architecture/CODEBASE_SEGMENTS.md` — the 39-segment map; use it to name the segment/area each file belongs to.
3. `TEST_FAILURE_LEDGER.md` (repo root) — measured failing families F-01…F-21. If one of your files is in a family, cite the family ID and factor it into the verdict.
4. Prior (stale, 2026-05) audit: `docs/testing/generated/TEST_PRUNING_CANDIDATES.md` and `docs/testing/generated/test-batches/batch-00*.md`. Grep these for your file names to find prior verdicts. **Re-verify against current source; overturn stale verdicts when warranted and say so.**

## Per-file review procedure

For each file in your plan:

1. **Read the whole file.**
2. **Extract facts:** LOC; count of `@Test` methods; count of `@Ignore`; production classes under test (from imports/usages of `com.yourname.expensetracker.*` main classes); segment #/name from CODEBASE_SEGMENTS.
3. **Classify style** (pick the dominant one):
   - `PURE` — pure logic, no mocks, no Android
   - `MOCKED` — MockK/Mockito mocks of repos/DAOs/services (verify interactions and/or state)
   - `VIEWMODEL` — ViewModel state testing
   - `ROBOLECTRIC` — Robolectric Android
   - `ROOM` — real in-memory Room DB on JVM
   - `SRCTEXT` — reads production source files as text and asserts on strings (brittle anti-pattern)
   - `STRESS` — stress/perf/concurrency (usually slow)
   - `FIXTURE` — shared harness/fixture, not a test class
4. **Assess assertion quality:** does it assert real expected outputs/invariants (money correctness, lifecycle events, dedup, privacy fail-closed), or only `verify{}` mock calls / non-null / "does not throw"? Note isolation hazards: shared DataStore files, real clock/sleeps, global state, order dependence.
5. **Overlap check:** for the main production class, run e.g.
   `grep -rl "ClassName" app/src/test --include="*.kt" | grep -v <thisfile>`
   List up to 5 sibling test files in the `overlap` column. Mark `DUP` when two files test the same class AND substantially the same behaviors; `complementary` when aspects differ (unit vs stress vs golden vs db-backed).
6. **Compile-fragility:** flag `FRAGILE` when heavy constructor/mock coupling means refactors will break this test (a stated user pain: refactors break ~100 tests).
7. **Verdict** (one per file):
   - `KEEP` — solid, unique value
   - `STRENGTHEN` — keep; has real value but notable gaps (list them)
   - `MERGE` — duplicate/crossover; name the surviving file
   - `REWRITE` — real business value but broken/outdated/anti-pattern
   - `DELETE` — dead, tautological, tests stdlib/platform, SRCTEXT, or tests removed APIs
   - `NIGHTLY` — slow stress; keep but out of PR CI
   - `UNKNOWN` — genuinely undecidable statically (say what's needed)
   and **priority** `P0` (protects money/lifecycle/privacy/backup/worker invariants) `P1` high, `P2` medium, `P3` low, `P4` negative value.
8. Strict areas per AGENTS.md need extra care: workers, privacy/security/AI/export/backup, Room entities/DAOs/migrations, money math, transaction/receipt/recurring lifecycle. If your batch covers these, check the legal paths in `docs/architecture/LEGAL_PATHS.md` for the owning coordinator and whether the test exercises the legal path or a bypass.

## Batch MD template (follow exactly)

```markdown
# Batch NN — <slug>

Scope: <areas/segments covered> · Files: <n> · LOC: <total>
Reviewer notes: <anything global about this area>

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|

(One row per file. Keep note ≤ 10 words; details below.)

## Findings (noteworthy files only)

### <file path>
- <2-6 bullet lines: what it does, what's wrong/right, evidence path:line, recommended action>

## Area gaps (what is NOT tested in this area)

- <bullets>

## Rollup

- verdict counts, P0 count, DUP pairs found, FRAGILE count
```

## Return message (to the coordinator)

Compact summary, ≤ 500 words: verdict counts; up to 10 highlights (most important findings, with file paths); all DUP pairs; all P4 (negative-value) files; coverage gaps; anything you could not decide.
