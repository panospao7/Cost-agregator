# CI / Static Guardrails Deep Review — commit `5640775`

Latest reviewed commit:  
https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855

Important previous commits checked:
- `68e2d45` — local CI verification evidence for atomicity scope  
  https://github.com/panospao7/Cost-agregator/commit/68e2d45
- `65c265f` — PR24 atomicity polish  
  https://github.com/panospao7/Cost-agregator/commit/65c265fb70420722d6d5dfb3f54fcc484408f999
- `42e53e1` — MIT-031/MIT-041 closure attempt  
  https://github.com/panospao7/Cost-agregator/commit/42e53e15be17303d945fe75c5afb7e22b963eab5

Static review only. I did not run Gradle or GitHub Actions myself.

---

## Executive verdict

This is a **strong CI/static-guardrails foundation commit**, but the commit message overclaims with:

> “PRs 1–9 complete”

I would rate the current state:

- **MIT-001 — near-done in repo, branch protection not externally proven**
- **MIT-002 — near-done**
- **MIT-003 — partial / strong foundation**
- **MIT-004 — not done**
- **MIT-005 — partial**

### Bottom line

**No, CI/static guardrails are not fully green yet.**  
They are **foundation-green / enforcement-yellow**.

---

# What is genuinely good

## 1. Workflow validation exists and is blocking

The workflow now adds a dedicated `validate-workflow` job using `actionlint` before expensive jobs start.  
Source: latest `ci.yml` job definition([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855)).

This is a real improvement and directly addresses the first phase of the plan.

## 2. `:app:check` is now run in CI

`lint-and-check` runs:

- `:app:lintDebug`
- `:app:assembleDebug`
- `:app:check`

Source: workflow and `app/build.gradle.kts` wiring([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855))([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855)).

That is the right direction and much better than the earlier fragmented CI.

## 3. Existing and new Python guard scripts are wired into CI

The `static-guards` job runs:

- privacy
- DB access
- event writer
- money
- source provenance
- UI/DAO
- worker
- receipt link
- import lifecycle
- cloud payload
- allowlist compliance
- migration matrix
- ignored-test budget
- pytest for script tests

Source: latest workflow([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855))([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855))([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855))([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855)).

That is real progress.

## 4. New guard scripts are not just added, they also have tests

The commit adds many `scripts/test_verify_*.py` files and runs them with pytest in CI([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855)).

This is good guard hygiene.

## 5. Allowlists are moving toward structured governance

You added YAML allowlists and a meta-guard validating owner/reason/expiry-ish fields([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_allowlist_compliance.py))([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_allowlist_compliance.py))([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_allowlist_compliance.py)).

That is much better than ad hoc regex exceptions in source.

---

# Remaining blockers

## BLOCKER 1 — no visible CI run for `5640775`

I could not verify a GitHub Actions run for this exact commit.

Also, your workflow triggers are:

- `push` only on `main` / `master`
- `pull_request` only on `main` / `master`

Source: raw `ci.yml` header([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/564077512ec11d19bb58f210f5b5750f2b4fe855/.github/workflows/ci.yml)).

So if `5640775` was pushed to a feature branch and not attached to a PR, **CI may not have run at all**.

### Why this matters

The implementation plan is about making guardrails active in CI, not just present in YAML.

### Recommendation

If you want every feature-branch push checked, broaden `push.branches`.  
If you only want PR CI, that is okay — but then you need a visible PR run before calling it complete.

---

## BLOCKER 2 — several critical guards are still warning-only

Your workflow explicitly runs these in warning mode:

- cancellation boundaries — ~248 pre-existing violations([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855))
- PII logging boundaries — ~52 pre-existing violations([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855))
- DI/release boundaries — scaffold guard([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855))
- ignored test budget — 31 pre-existing([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855))

That means important regression classes are still **non-blocking**.

### Impact

This directly conflicts with the plan’s non-negotiable goal that critical guards be blocking.

### Verdict

MIT-003 is **not closed** yet.

---

## BLOCKER 3 — “migration matrix” is not a real migration execution matrix

This is the biggest gap relative to the DB/CI plan.

Your `verify_migration_matrix.py` does **static parsing** of:

- `AppDatabase.kt`
- `DatabaseMigrations.kt`
- `app/schemas/**`

and checks for missing stepwise version pairs([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_migration_matrix.py))([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_migration_matrix.py))([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_migration_matrix.py)).

What it does **not** do:

- run `MigrationTestHelper`
- migrate historical DB fixtures
- compare fresh latest schema vs migrated latest schema
- test non-empty data preservation
- test data-loss hotspots

Also, I found no evidence of `MigrationTestHelper`, `DatabaseMigrationMatrixTest`, or fresh-vs-migrated parity tests in the repo search([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/test_verify_migration_matrix.py))([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/test_verify_migration_matrix.py)).

### Verdict

This is **not MIT-004 complete**.  
It is a useful **static preflight**, not a real migration matrix.

---

## BLOCKER 4 — ignored-test budget is still inconsistent and incomplete

There are two parallel mechanisms:

1. Gradle `verifyNoIgnoredGrowth -PmaxIgnoredTests=310` in CI([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/564077512ec11d19bb58f210f5b5750f2b4fe855/.github/workflows/ci.yml))  
2. Python ignored-test guard in warning mode with “31 pre-existing”([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855))

And the Python scanner appears to only scan `@Ignore`, not `@Disabled`([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_ignored_test_budget.py))([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_ignored_test_budget.py)).

The plan wanted counting of:
- `@Ignore`
- disabled tests
- excluded patterns
- release-critical ignored tests

### Problems

- the counts `310` vs `31` are not harmonized
- `@Disabled` coverage appears absent
- the denylist exists, but the job is still warning-only

### Verdict

MIT-005 is still **partial**.

---

## BLOCKER 5 — DI/release guard is still a scaffold

`verify_di_release_boundaries.py` literally describes itself as a scaffold guard for debug-leak bindings([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_di_release_boundaries.py)) and runs in warning mode([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855)).

It checks some useful things, like:
- suspicious debug/demo types in DI modules
- `http://` in DI modules
- `isMinifyEnabled = false` in release blocks([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_di_release_boundaries.py))([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_di_release_boundaries.py))

But it does **not** prove:
- full Hilt release binding graph safety
- no debug/demo module reachable in release runtime
- no BODY logging across all network stacks
- no unsafe release provider outside `di/**`

### Verdict

Good start, not closure.

---

## BLOCKER 6 — branch protection cannot be verified from repo contents

You added `CODEOWNERS`, which is good([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/564077512ec11d19bb58f210f5b5750f2b4fe855/CODEOWNERS)).

But I cannot verify from code whether GitHub branch protection actually marks these jobs as required:

- `validate-workflow`
- `static-guards`
- `unit-tests`
- `lint-and-check`

That part lives in GitHub settings, not in the repo.

### Verdict

MIT-001 is **code-ready but not externally proven complete**.

---

# Medium issues

## 1. `actionlint` download is unpinned

You download it from a live script URL:

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/rhysd/actionlint/main/scripts/download-actionlint.bash)
```

Source: workflow validation job([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855)).

This is convenient, but not ideal for reproducibility or supply-chain stability.

### Better

Pin a release version or use a maintained GitHub Action.

---

## 2. Direct-event and cancellation debt is still large

The CI commit itself doesn’t reduce the underlying debt; it mostly exposes it.

That’s okay, but it means the branch is more like:

```text
enforcement scaffold complete
backlog burn-down still required
```

not “finished”.

---

## 3. Some guards remain heuristic regex scanners

Examples like UI DAO and worker boundary scripts are clearly regex/path-based heuristics([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_ui_dao_boundaries.py))([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_worker_boundaries.py)).

That is acceptable, but you should expect false positives/negatives and keep the allowlists disciplined.

---

# CI/static-guardrails status by MIT

## MIT-001 — full Gradle verification in PR CI

**Status: NEAR-DONE**

Good:
- `assembleDebug`, `lintDebug`, unit tests, and `:app:check` are in workflow([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/564077512ec11d19bb58f210f5b5750f2b4fe855/.github/workflows/ci.yml)).

Not proven:
- required branch protection settings.

## MIT-002 — all existing scripts in CI

**Status: NEAR-DONE**

Good:
- existing scripts run in CI([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855))([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855))([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855))([github.com](https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855)).

Remaining:
- some are warning-only.

## MIT-003 — missing architecture guards

**Status: PARTIAL / STRONG FOUNDATION**

Good:
- many new guards landed.

Remaining:
- cancellation / PII / DI-release are not blocking
- some guards are scaffold-quality rather than final-enforcement quality

## MIT-004 — real migration execution matrix

**Status: NOT DONE**

Current state:
- static migration registration/schema-coverage script only([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_migration_matrix.py))([github.com](https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_migration_matrix.py)).

Missing:
- actual migration execution tests.

## MIT-005 — ignored-test debt enforcement

**Status: PARTIAL**

Current state:
- growth guard exists
- denylist infrastructure exists
- Python scanner exists

Missing:
- blocking enforcement
- `@Disabled`
- harmonized counts
- release-critical denylist actually blocking CI

---

# Recommended next PRs

## PR10 — make CI evidence real

1. Open/update a PR so the workflow actually runs for latest guardrail commits.
2. Capture visible GitHub Actions green for:
   - `validate-workflow`
   - `static-guards`
   - `unit-tests`
   - `lint-and-check`

Without this, the work is hard to verify externally.

## PR11 — migration execution MVP

Do the real MIT-004 work:

- `MigrationTestHelper`
- `DatabaseMigrationMatrixTest`
- representative supported-version migration runs
- fresh-vs-migrated parity

## PR12 — ignored-test budget hardening

1. Harmonize `31` vs `310`
2. add `@Disabled`
3. fail CI on release-block denylist classes
4. keep growth guard blocking

## PR13 — promote critical warning guards

Promote to blocking in this order:
1. ignored-test denylist
2. DI/release if low-noise enough
3. PII logging
4. cancellation after backlog split/burn-down

---

# Final verdict

This commit is **good infrastructure progress**.

But I would not say:

```text
CI static guardrails — PRs 1–9 complete
```

I would say:

```text
CI/static guardrails foundation landed.
Gradle verification and many guards are wired into CI.
Several critical guards remain warning-only.
Migration matrix is still static, not executed.
Branch protection / latest visible green CI not yet proven.
```

## Final status

```text
CI / Static Guardrails: YELLOW-GREEN
MIT-001: near-done
MIT-002: near-done
MIT-003: partial / strong foundation
MIT-004: not done
MIT-005: partial
```

Sources used:
- Latest CI commit: https://github.com/panospao7/Cost-agregator/commit/564077512ec11d19bb58f210f5b5750f2b4fe855
- Workflow: https://raw.githubusercontent.com/panospao7/Cost-agregator/564077512ec11d19bb58f210f5b5750f2b4fe855/.github/workflows/ci.yml
- Actions page: https://github.com/panospao7/Cost-agregator/actions
- Migration guard: https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_migration_matrix.py
- Cancellation guard: https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_cancellation_boundaries.py
- UI DAO guard: https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_ui_dao_boundaries.py
- DI/release guard: https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_di_release_boundaries.py
- Allowlist compliance: https://github.com/panospao7/Cost-agregator/blob/564077512ec11d19bb58f210f5b5750f2b4fe855/scripts/verify_allowlist_compliance.py