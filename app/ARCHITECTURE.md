# ExpenseTracker - Legacy Architecture Notes

> **Historical reference only.**
>
> This document is no longer the canonical architecture source.
> Use these docs instead:
> - `docs/architecture/ARCHITECTURE.md`
> - `docs/architecture/CODEBASE_INVENTORY.md`
> - `docs/architecture/CODEBASE_SEGMENTS.md`

## Why this file exists

This file is kept to preserve older design notes and migration context.
Its navigation, schema, and module descriptions may be stale.

## Current references

- Navigation is destination-driven through `NavigationDestination`.
- Bottom chrome uses 6 shell destinations; Assistant is an overlay/entry surface, not a tab.
- The Room schema is currently version 110.
- DI is split across many feature modules under `di/`.

## Recommended action

When you need current architecture details, follow the canonical docs in `docs/architecture/`.
