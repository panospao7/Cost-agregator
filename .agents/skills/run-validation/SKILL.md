---
name: run-validation
description: Run one serialized compile, test, lint, or guard profile through the durable validation runner and report its result.
---

# Run Validation

Use the `validation-runner` agent. It is the sole owner of live compilation,
test, lint, connected-test, and guard execution.

## Rules

1. Use only `scripts/validation-runner.ps1`; never invoke Gradle or guard
   scripts directly.
2. Start one allowlisted profile and capture the run ID.
3. Poll `Status`. Exit code 3 means RUNNING, not failure.
4. Do not start concurrent validation.
5. PASS requires both a PASS result and completion marker.
6. Route failures to `ci-build-debugger`/coder; the runner never edits.
7. Guard execution is verification-only; never alter baselines, allowlists,
   exceptions, or policy.
8. Use heartbeat/output timestamps and byte counts to detect stalled output.
9. Prefer named serial shards over the full suite for broad local evidence.
10. Treat app-check and static-guards as distinct gates; do not skip either when required.

## Profiles

`compile`, `assemble-debug`, `targeted-unit-test`, `targeted-release-unit-test`, `unit-test-shard`,
`trusted-tests`, `legacy-tests`, `unit-tests`, `migration-tests`, `lint`,
`static-guards`, `registered-guard`, `app-check`, `connected-tests`,
`runner-smoke`, `runner-stall-smoke`, `runner-protocol-tests`, `pytest-subset`
(`-TestFilter scripts[/path][::test_id]`; PASS needs exit 0 and at least one
passed test, otherwise `E_NO_TESTS_PASSED`).

Standing rule: any change to `scripts/validation-runner.ps1`,
`scripts/ci/test_validation_runner_protocol.py`, or runner profiles/docs requires
`runner-protocol-tests` (and `runner-smoke`). `static-guards` no longer runs the
runner protocol file. Release final acceptance should run `runner-protocol-tests`
alongside `static-guards`.

`targeted-unit-test` / `targeted-release-unit-test` take `-TestFilter` as one
filter or a comma-separated list (`"*A*,*B*"`, max 50, no spaces, no empty or
duplicate items) to validate a batch of test classes in one Gradle run. Invalid
lists fail with `E_INVALID_TEST_FILTER`; more than 50 items with
`E_TOO_MANY_TEST_FILTERS`. Gradle fails only when the WHOLE run matches zero
tests; an item that matches nothing is silently skipped and the run can PASS.
For a multi-filter run, confirm per-item coverage from JUnit XML
(`app/build/test-results/<task>/` files newer than the run start); an item with
zero matched tests is NOT validated (run it alone or fix the name). A
runner-side per-item check is planned (B4-P1d).

## Report

```markdown
VALIDATION VERDICT: PASS|FAIL|RUNNING|INFRA_FAILURE|STALE_RESULT
- run ID: ...
- profile: ...
- exit code: ...
- result/log paths: ...
- next action: ...
```
