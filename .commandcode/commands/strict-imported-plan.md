Execute an external review/debug doc plus implementation plan one strict batch at a time, following the /orchestrated-batch skill workflow.

User arguments (paths, pasted context, or instructions for the external doc and plan):

```text
$ARGUMENTS
```

## Required behavior

1. Identify the review/debug source and the implementation plan.
2. If file paths are provided, read those files first.
3. Do not re-plan from scratch.
4. Treat the external plan as approved intent, not guaranteed truth.
5. First delegate to the `scout` subagent to verify the plan against current source.
6. If the plan is stale, conflicting, or unsafe, stop and re-plan the delta before implementing.
7. If the plan matches, execute only the requested batch.
8. If no batch is specified, start with Batch 1.
9. Use strict gates for workers, privacy, security, permissions, diagnostics, Room/migrations, lifecycle paths, architecture guards, backup/export/cloud AI, or cross-layer changes.
10. Do not implement all batches at once.
11. Do not mark docs/status work complete until code, tests, guardian review, and strict review pass.

## Default strict batch loop

```text
scout verifies current source
→ relevant guardian checks architecture/privacy/Room risk
→ specialist-coder implements minimal diff
→ tester-runtime adds targeted tests
→ reviewer-strict reviews current diff
→ stop or continue based on verdict
```

## Hard stops

Stop and report if the review doc or plan cannot be found, the plan does not match current code, files/classes named in the plan are missing, reviewer returns FAIL, tests fail and root cause is not obvious, schema migration appears unexpectedly, privacy/security behavior is ambiguous, the legal path is unclear, implementation exceeds batch scope, or destructive git/file commands would be needed.
