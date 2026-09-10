---
description: Execute one approved implementation-plan batch with appropriate gates.
agent: orchestrator
---

Execute one approved batch only.

Batch/request:

```text
$ARGUMENTS
```

## Instructions

1. Identify the exact batch to execute.
2. Confirm an approved plan exists in the conversation or provided file path.
3. If no approved plan exists, stop and delegate to `@planner` or `@planner-advanced`.
4. Use the smallest safe workflow:
   - fast for trivial low-risk edits
   - standard for normal work
   - strict for workers/privacy/security/Room/lifecycle/static guards/cross-layer work
5. Do not implement unrelated batches.
6. Do not broaden scope.
7. Require targeted tests for behavior changes.
8. Require strict review for risky batches.
9. Stop on reviewer fail, test fail, privacy ambiguity, architecture ambiguity, schema surprises, or unexpected broad diff.

## Default batch loop

```text
@scout if needed
→ @coder or @specialist-coder
→ @tester-runtime or @tester-static
→ @reviewer-fast or @reviewer-strict
```

For risky work:

```text
@scout
→ relevant guardian
→ @coder/@specialist-coder
→ @tester-runtime
→ relevant guardian re-check if needed
→ @reviewer-strict
```

## Output format

```markdown
## Batch Execution Plan
- Batch: ...
- Mode: fast|standard|strict
- Cost posture: cheap|balanced|quality-gated

## Steps
1. ...
2. ...

## Gates
- Test gate: required|optional
- Review gate: required|optional
- Architecture/privacy/Room gate: required|optional

## Stop Condition
- Complete when: ...
- Blocked when: ...
```
