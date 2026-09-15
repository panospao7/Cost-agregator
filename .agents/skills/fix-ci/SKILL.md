---
name: fix-ci
description: Diagnose and minimally fix CI, Gradle, Kotlin, Hilt, KSP, Room, lint, or test failures. Use when the user pastes a build failure or asks to fix CI.
---

# Fix CI

Diagnose the CI/build/test failure described in the invoking message (failure log or output pasted by the user). Codex skills have no argument passing: treat everything in the invoking message as the failure input.

Delegate the diagnosis to the `ci-build-debugger` agent.

## Instructions

1. Start from the provided failure log/output.
2. Identify the first meaningful failure, not just the final failure line.
3. Separate root cause from cascading errors.
4. Inspect related source and current diff.
5. Prefer targeted commands over broad suite commands.
6. Ask before running Gradle or expensive checks.
7. Apply a minimal fix only if the root cause is clear.
8. Do not weaken tests/guards to hide failures unless explicitly approved and justified. For guard infrastructure this concretely means: do not grow `config/baselines/*.json` ratchets, do not broaden `config/db_access_allowlist.yml` or `config/guards/*` exceptions, and do not add allowlist/exception entries to mask failures without explicit human approval (see `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-06/FG-07, path + FG-ID only).
9. Stop if the fix requires unapproved schema, privacy, security, or broad architecture changes.

## Preferred targeted checks

Use only when appropriate and with approval:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:kspDebugKotlin
./gradlew :app:testDebugUnitTest --tests "*ClassName*"
./gradlew :app:lintDebug
```

Broader checks only after targeted checks:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:check
```

## Output format

```markdown
CI/build root cause:
- ...

Fix:
- files: ...
- change: ...

Validation:
- command: ...
- result: PASS|FAIL|NOT RUN
- important output: ...

Remaining failures:
- none | details

Next recommended command:
- `command`
```
