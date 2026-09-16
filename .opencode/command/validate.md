---
description: Run one serialized compile, test, lint, or guard validation profile.
agent: validation-runner
subtask: true
---

Run repository validation through the durable serialized runner.

Requested profile/context:

```text
$ARGUMENTS
```

## Instructions

1. Select exactly one allowlisted validation profile.
2. Never execute Gradle, Python guards, adb, Kotlin, or tests directly.
3. Start the profile through `scripts/validation-runner.ps1`.
4. Capture the run ID and poll `Status`; exit code 3 means still running.
5. Do not start another run while the first is active.
6. Report durable result and log paths.
7. On non-green results, return control to the orchestrator for routing to
   `ci-build-debugger` or the coder. Do not edit or fix files.
8. For guards, never update baselines/allowlists/exceptions or generate
   candidates.
9. Prefer `unit-test-shard` over `unit-tests` when broad evidence is needed.
10. `app-check` and `static-guards` are distinct gates; run both when required.

If no profile can be inferred safely, list profiles and stop without running.
