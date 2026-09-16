---
name: scope
description: Read-only scope discovery for a task or external plan. Use before implementing, to find relevant files, risks, and the recommended workflow.
---

# Scope

Perform read-only scope discovery for the task or plan described in the invoking message.

Delegate the discovery to the `scout` agent.

## Goal

Find the relevant files, architecture docs, tests, risk level, and recommended workflow before implementation.

## Instructions

1. Do not edit files.
2. Do not run bash.
3. Read architecture docs first when relevant:
   - `CODEBASE_SEGMENTS.md`
   - `CODEBASE_INVENTORY.md`
   - `LEGAL_PATHS.md`
   - `ENGINE_INTERACTION_MAP.md`
   - relevant files under `docs/`
4. Locate likely source files and tests.
5. Identify high-risk areas:
   - workers / WorkManager
   - privacy/security/permissions
   - Room/migrations/schema
   - money/currency
   - lifecycle paths
   - architecture guards
6. Recommend trivial, standard, or strict handling.

## Output format

```markdown
Scout findings:
- Relevant files:
  - `path`: why relevant

Architecture docs/rules:
- `path`: summary

Tests likely affected:
- `path` or test filter

Risk:
- low|medium|high

Recommended handling:
- trivial|standard|strict

Recommended next step:
- ...
```
