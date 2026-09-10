# CI / Static Guardrails — Remaining Issues Plan

Base reviewed commit: `564077512ec11d19bb58f210f5b5750f2b4fe855`

## Current status

- MIT-001: near-done
- MIT-002: near-done
- MIT-003: partial / strong foundation
- MIT-004: not done
- MIT-005: partial

## Main remaining issues

1. No visible green CI proof for latest commit.
2. Several critical guards are warning-only.
3. Migration “matrix” is static analysis, not real migration execution.
4. Ignored-test enforcement is inconsistent (`310` vs `31`, no full `@Disabled` handling).
5. DI/release guard is still scaffold-level.
6. Branch protection is not repo-proven.

---

## PR10 — Make CI execution/evidence real

### Goals
- Ensure latest branch/PR actually runs CI.
- Make failures externally visible and reproducible.

### Tasks
- Add `workflow_dispatch`.
- If desired, broaden `push.branches` to feature branches or use PR-only policy consistently.
- Pin `actionlint` version instead of curl-to-main.
- Upload reports/artifacts for:
  - unit tests
  - lint
  - static guards
- Add `docs/ci/LATEST_CI_VERIFICATION.md` with:
  - commit SHA
  - commands/jobs
  - pass/fail result

### Acceptance
- A visible green Actions run exists for latest commit/PR.
- Artifacts exist on failure.

---

## PR11 — Real migration execution MVP

### Goals
Close the biggest MIT-004 gap.

### Tasks
- Add real migration tests using `MigrationTestHelper`.
- Create:
  - `DatabaseMigrationMatrixTest`
  - `FreshVsMigratedSchemaParityTest`
  - `UnsupportedVersionPolicyTest`
- Representative paths:
  - `minimumSupported -> latest`
  - `145 -> latest`
  - `146 -> latest`
  - fresh latest
- Add non-empty fixture data.
- Wire migration tests into CI.

### Acceptance
- CI fails on missing migration.
- CI fails on schema drift.
- CI proves actual migration execution, not just static chain presence.

---

## PR12 — Ignored-test budget hardening

### Tasks
- Harmonize Gradle and Python counts.
- Extend scanner to include:
  - `@Ignore`
  - `@Disabled`
  - excluded patterns if possible
- Separate categories:
  - release-blocking
  - flaky
  - obsolete
  - unknown
- Make release-critical denylist blocking.
- Keep fail-on-increase blocking.

### Acceptance
- One canonical count.
- Release-critical ignored tests fail CI.

---

## PR13 — Promote critical warning guards to blocking

### Order
1. ignored-test denylist
2. DI/release
3. PII logging
4. cancellation

### Tasks
- Burn down false positives/allowlists first.
- Convert warning-only guards to `--fail-on-violation`.
- Require structured allowlists:
  - owner
  - reason
  - issue
  - expiry

### Acceptance
- Critical regression classes cannot merge silently.

---

## PR14 — DI/release guard maturation

### Tasks
- Expand beyond `di/**` string scanning:
  - release bindings
  - BODY logging
  - cleartext endpoints
  - demo/stub/no-op impls
- Add fixtures/tests.
- Make blocking once noise is low.

### Acceptance
- Release-unsafe bindings fail CI.

---

## PR15 — Branch protection + governance

### Manual GitHub settings
Require checks:
- `validate-workflow`
- `unit-tests`
- `lint-and-check`
- `static-guards`
- migration test job
- ignored-test budget

### Also
- CODEOWNERS review for:
  - workflows
  - scripts
  - allowlists
  - migrations

### Acceptance
- Repo settings match documented required checks.

---

## Recommended order

1. PR10
2. PR11
3. PR12
4. PR13
5. PR14
6. PR15

## Minimal next patch

If you want the shortest high-value step first:
1. Add `workflow_dispatch`
2. Get visible green CI for latest commit
3. Add real migration execution tests
4. Make ignored-test denylist blocking