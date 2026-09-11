Create a concise handoff summary for the current branch/worktree by delegating to the `documentor` subagent via the `agent` tool.

Additional context:

```text
$ARGUMENTS
```

## Instructions

1. Have the `documentor` summarize: changed files, behavior changed, tests/checks run, reviewer/guardian verdicts if present, remaining risks, next recommended action.
2. Do not edit files unless explicitly asked to write a handoff document file.
3. Do not claim tests passed unless actually run.
4. Do not mark work complete unless gates passed; if partial, use `pending`, `partial`, `blocked`, or `conditional`.
5. Report status (`PASS`|`PARTIAL`|`BLOCKED`|`UNKNOWN`), changed files, validation results, review/guardian gates (architecture, privacy/security, Room/migration, strict review), remaining risks, and next steps.
