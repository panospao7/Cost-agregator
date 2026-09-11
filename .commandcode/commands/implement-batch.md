Execute one approved batch only, following the /orchestrated-batch skill workflow.

Batch/request:

```text
$ARGUMENTS
```

## Instructions

1. Identify the exact batch to execute.
2. Confirm an approved plan exists in the conversation or provided file path.
3. If no approved plan exists, stop and plan first (built-in Plan agent).
4. Use the smallest safe workflow:
   - fast for trivial low-risk edits (implement directly, no subagents)
   - standard for normal work (scout → coder → tester → reviewer)
   - strict for workers/privacy/security/Room/lifecycle/static guards/cross-layer work (scout → guardian → specialist-coder → tester-runtime → guardian re-check → reviewer-strict)
5. Do not implement unrelated batches.
6. Do not broaden scope.
7. Require targeted tests for behavior changes.
8. Require strict review for risky batches.
9. Stop on reviewer fail, test fail, privacy ambiguity, architecture ambiguity, schema surprises, or unexpected broad diff.

Delegate each phase to the matching subagent via the `agent` tool. Cap at 2 review iterations and 2 debug iterations, then stop and report BLOCKED.
