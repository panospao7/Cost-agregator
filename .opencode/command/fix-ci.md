---
description: Diagnose and minimally fix CI, Gradle, Kotlin, Hilt, KSP, Room, lint, or test failures.
agent: ci-build-debugger
subtask: true
---

Diagnose this CI/build/test failure:

```text
$ARGUMENTS
```

## Instructions

1. Start from the provided failure log/output.
2. Identify the first meaningful failure, not just the final failure line.
3. Separate root cause from cascading errors.
4. Inspect related source and current diff.
5. Prefer targeted commands over broad suite commands.
6. Ask before running Gradle or expensive checks.
7. Apply a minimal fix only if the root cause is clear.
8. Do not weaken tests/guards to hide failures unless explicitly approved and justified.
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
