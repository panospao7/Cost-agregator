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

`compile`, `assemble-debug`, `targeted-unit-test`, `unit-test-shard`,
`trusted-tests`, `legacy-tests`, `unit-tests`, `migration-tests`, `lint`,
`static-guards`, `registered-guard`, `app-check`, `connected-tests`,
`runner-smoke`, `runner-stall-smoke`.

## Report

```markdown
VALIDATION VERDICT: PASS|FAIL|RUNNING|INFRA_FAILURE|STALE_RESULT
- run ID: ...
- profile: ...
- exit code: ...
- result/log paths: ...
- next action: ...
```
