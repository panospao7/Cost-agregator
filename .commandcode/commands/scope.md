Perform read-only scope discovery by delegating to the `scout` subagent via the `agent` tool.

Task or plan to scope:

```text
$ARGUMENTS
```

## Goal

Find the relevant files, architecture docs, tests, risk level, and recommended workflow mode before implementation.

## Instructions

1. Delegate to `scout`; do not edit files or run shell commands yourself.
2. The scout should read architecture docs first when relevant:
   - `CODEBASE_SEGMENTS.md`
   - `CODEBASE_INVENTORY.md`
   - `LEGAL_PATHS.md`
   - `ENGINE_INTERACTION_MAP.md`
   - relevant files under `docs/`
3. Flag high-risk areas: workers / WorkManager, privacy/security/permissions, Room/migrations/schema, money/currency, lifecycle paths, architecture guards.
4. Recommend fast, standard, or strict mode.
5. Report the scout's findings: relevant files, architecture rules, tests likely affected, risk (low|medium|high), recommended mode, recommended next step.
