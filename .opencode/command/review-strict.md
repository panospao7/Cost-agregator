---
description: Strict review of the current uncommitted diff.
agent: reviewer-strict
subtask: true
---

Review the current uncommitted worktree diff.

Additional context or plan:

```text
$ARGUMENTS
```

## Instructions

1. Inspect `git status`.
2. Inspect `git diff`.
3. Identify changed files.
4. Read surrounding code and relevant call sites.
5. Compare against the provided plan/context if any.
6. Check architecture, privacy/security, tests, and regression risk.
7. Do not edit files.
8. Report only concrete, evidence-backed issues.
9. If no approved plan is available, say so and review against the diff and repository rules.

## Strict focus

Always check carefully if the diff touches:

- workers / WorkManager
- privacy/security/permissions
- diagnostics/logging persistence
- Room entities/DAOs/migrations/schema
- backup/restore/export/cloud AI
- money/currency
- transaction/receipt/recurring lifecycle
- static architecture guards
- cross-layer/cross-module changes

## Output format

```markdown
VERDICT: PASS | FAIL

Summary:
- Changed scope: ...
- Plan available: yes|no
- Main risk areas checked: ...
- Architecture docs/rules checked: ...

Issues:
- [ISSUE-1] [CRITICAL|MAJOR|MINOR] problem - `file` - why it matters - minimal fix

Coverage:
- Requirements met: yes|no|unknown
- Testing adequate: yes|no
- Regression risk: low|medium|high

Questions:
- ...

Notes:
- ...
```

If there are no issues:

```markdown
Issues:
- None
```
