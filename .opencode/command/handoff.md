---
description: Summarize current branch state, changed files, validation, risks, and next steps.
agent: documentor
subtask: true
---

Create a concise handoff summary for the current branch/worktree.

Additional context:

```text
$ARGUMENTS
```

## Instructions

1. Inspect current git status and diff if allowed.
2. Do not edit files unless the user explicitly asks for a handoff document file.
3. Summarize:
   - changed files
   - behavior changed
   - tests/checks run
   - reviewer/guardian verdicts if present
   - remaining risks
   - next recommended action
4. Do not claim tests passed unless actually run.
5. Do not mark work complete unless gates passed.
6. If work is partial, use `pending`, `partial`, `blocked`, or `conditional`.

## Output format

```markdown
## Handoff Summary

Status:
- PASS|PARTIAL|BLOCKED|UNKNOWN

Changed files:
- `path`: summary

Behavior changed:
- ...

Validation:
- command: ...
- result: PASS|FAIL|NOT RUN

Review/guardian gates:
- Architecture: PASS|FAIL|NOT RUN
- Privacy/security: PASS|FAIL|NOT RUN
- Room/migration: PASS|FAIL|NOT RUN
- Strict review: PASS|FAIL|NOT RUN

Remaining risks:
- ...

Next steps:
1. ...
2. ...
```
