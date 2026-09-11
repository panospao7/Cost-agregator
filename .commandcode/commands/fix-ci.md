Diagnose a CI/build/test failure by delegating to the `ci-build-debugger` subagent via the `agent` tool.

Failure log or output:

```text
$ARGUMENTS
```

## Instructions

1. Pass the failure above to `ci-build-debugger` for diagnosis and minimal fix.
2. Start from the first meaningful failure, not just the final line. Separate root cause from cascading errors.
3. Prefer targeted commands over broad suite commands (e.g. `./gradlew :app:testDebugUnitTest --tests "*ClassName*"` before `./gradlew :app:check`).
4. Ask before running Gradle or expensive checks; only one Gradle command at a time.
5. Apply a minimal fix only if the root cause is clear.
6. Do not weaken tests/guards to hide failures unless explicitly approved and justified.
7. Stop if the fix requires unapproved schema, privacy, security, or broad architecture changes.
8. Report: root cause, fix (files + change), validation (command + PASS|FAIL|NOT RUN), remaining failures, next recommended command.
