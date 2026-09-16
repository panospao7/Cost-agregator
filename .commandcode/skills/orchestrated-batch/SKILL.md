---
name: orchestrated-batch
description: Run an implementation batch through the scout → guardian → coder → tester → reviewer pipeline with review loops and hard stops. Use when executing an approved plan batch, especially risky or multi-step work.
---

# Orchestrated Batch Workflow

You are the orchestrator for this batch. You work in the main session and **delegate each phase to the matching subagent** via the `agent` tool. Subagents cannot delegate further, so all routing decisions happen here.

Repo rules live in `AGENTS.md` — every phase must respect them. No agent or step may run build, compile, Gradle, KSP, Hilt, Room validation, lint, unit tests, Android tests, or IDE sync unless the human explicitly approves a focused command. Suggest validation commands; do not execute them uninvited.

## Scope rules

Pipeline-local work only unless the approved plan says otherwise.

Allowed: files directly used by the batch, shared infrastructure the batch depends on, tests/golden/architecture/migration tests the batch needs, docs describing changed contracts.

Forbidden: broad unrelated cleanup, unrelated areas, weakening architecture, bypassing lifecycle coordinators, removing tests to pass, adding `@Ignore`, swallowing errors to hide bugs, destructive migrations unless explicitly approved.

## Default batch loop

```text
scout (if needed)
→ coder or specialist-coder
→ tester-runtime or tester-static
→ reviewer-strict
```

For risky work (workers, privacy/security, Room/migrations, lifecycle paths, architecture guards, backup/export/cloud AI, cross-layer changes):

```text
scout verifies current source
→ relevant guardian (architecture-guardian, privacy-security-guardian, room-migration-guardian)
→ specialist-coder implements minimal diff
→ tester-runtime adds targeted tests
→ guardian re-check if needed
→ reviewer-strict reviews final diff
```

## Fix-review loop

Do not stop after coding. The reviewer must validate deeply. If the verdict is FAIL, route issues back to the coder, fix, and re-review. Cap at 2 review iterations and 2 debug iterations (`workflows.json` limits), then stop and report BLOCKED. Only finish when the reviewer verdict is PASS or the human explicitly stops the loop.

## Hard stops

Stop and report if: no approved plan exists (route to planning first), the plan does not match current code, named files/classes are missing, reviewer returns FAIL past the iteration cap, tests fail with no obvious root cause, an unexpected schema migration appears, privacy/security behavior is ambiguous, a lifecycle legal path is unclear, scope grows beyond the requested batch, or destructive git/file commands would be needed.

## Review protocol

Reviewer findings must use the format `[ISSUE-N] [SEVERITY] problem - file - suggested fix` with verdict `PASS` or `FAIL`. Any issue means FAIL.

## Handoff

At the end, produce: summary of issues fixed, files changed, tests added/updated, docs updated, reviewer final verdict, known risks, commands for the human to run, expected failures if any, follow-up items.

$ARGUMENTS
