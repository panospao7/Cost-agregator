Review the current uncommitted worktree diff by delegating to the `reviewer-strict` subagent via the `agent` tool.

Additional context or plan:

```text
$ARGUMENTS
```

## Instructions

1. Delegate the diff review to `reviewer-strict`. Do not edit files yourself.
2. If a plan or context is given above, pass it to the reviewer for comparison.
3. If no approved plan is available, say so and have the reviewer judge against the diff and repository rules (`AGENTS.md`).
4. Always check carefully if the diff touches: workers / WorkManager, privacy/security/permissions, diagnostics/logging persistence, Room entities/DAOs/migrations/schema, backup/restore/export/cloud AI, money/currency, transaction/receipt/recurring lifecycle, static architecture guards, cross-layer/cross-module changes.
5. Report the verdict (`PASS` | `FAIL`), issues in `[ISSUE-N] [SEVERITY] problem - file - suggested fix` format, coverage assessment, and regression risk. Any issue means FAIL.
