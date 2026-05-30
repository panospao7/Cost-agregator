# ExpenseTracker Documentation Index

> **Last updated:** 2026-05-30  
> **Current DB version:** v141 (migrated through v139–v141 for recurring lifecycle hardening)  
> **Kotlin source files:** ~926 (388 domain, 280 data, 164 ui, 31 di, 63 other)  
> **DAOs:** 62 · **Entities:** 64 · **Hilt @Module files:** 31 · **ViewModels:** 39

---

## Core Architecture Docs

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/architecture/ARCHITECTURE.md` | Master architecture guide — layers, data flow, components, drift log | 2026-05-30 (P3/P4) |
| `docs/architecture/CODEBASE_INVENTORY.md` | Ground-truth inventory of all screens, VMs, routes, repos, entities, DAOs, DI modules | 2026-05-18 |
| `docs/architecture/CODEBASE_SEGMENTS.md` | 38-segment ownership map for targeted AI analysis | 2026-05-18 |
| `docs/architecture/DEPENDENCY_MAP.md` | 12 major dependency maps with call-chain consumer tables | 2026-05-10 |
| `docs/architecture/ENGINE_INTERACTION_MAP.md` | Engine-to-pipeline impact matrix with risk levels | 2026-05-30 (P3/P4) |
| `docs/architecture/LEGAL_PATHS.md` | Single allowed implementation path for each major operation — architecture law | 2026-05-30 (P3/P4) |

## Component Maps

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/architecture/dao-map.md` | Complete 62-DAO mapping to entities and consuming repositories | 2026-05-18 |
| `docs/architecture/hilt-bindings-map.md` | 31-module interface→implementation binding map | 2026-05-30 (P3/P4) |
| `docs/architecture/route-viewmodel-map.md` | NavigationDestination→ViewModel mapping for all 26+ screens | 2026-05-10 |
| `docs/architecture/VIEWMODEL_INJECTION_MAP.md` | 38 ViewModels with full Hilt injection lists + complexity heatmap | 2026-05-18 |

## Backend Maps (analyses and debug master)

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/analyses and debug master/COMPLETE-BACKEND-MAP.md` | Exhaustive file-by-file listing of all backend files (domain/data/DI) | 2026-05-07 (needs full refresh) |
| `docs/analyses and debug master/BACKEND-MAP-INDEX.md` | Quick navigation index for the backend map suite | 2026-05-07 (needs full refresh) |
| `docs/analyses and debug master/BACKEND-DEPENDENCIES.md` | Critical dependency chains with ASCII art diagrams | 2026-04-06 (needs full refresh) |
| `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md` | 130+ cross-pipeline issue tracker with universal contracts | 2026-05-11 |

## Database & Write Ownership

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/DB_WRITE_OWNERSHIP.md` | Table family→approved writer map with enforcement rules | 2026-05-30 (P3/P4) |
| `docs/development/DAO_ACCESS_GUARDRAILS.md` | ExpenseDao access approval tiers with CI enforcement | 2026-05-12 |

## Currency & Money

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/currency/money-aggregate-contract.md` | MoneyAggregate contract and builder rules | 2026-05-18 |
| `docs/currency/rate-basis-policy.md` | Rate basis selection policy (LATEST, TRANSACTION_DATE, etc.) | 2026-05-18 |
| `docs/currency/money-boundary-guard.md` | Money boundary guard rules (G-MONEY-01 through G-MONEY-21) | 2026-05-18 |

## Privacy & Security

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/architecture/PRIVACY_UI_ARCHITECTURE.md` | Privacy UI architecture and capability gating | 2026-05-10 |
| `docs/architecture/SENSITIVE_DIAGNOSTICS_POLICY.md` | Policy for sensitive diagnostic data handling | 2026-05-10 |

## Testing

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/testing/TESTING_MASTER_STATUS.md` | Master testing status across all pipelines | 2026-05-18 |
| `docs/testing/MASTER_TESTING_STRATEGY.md` | Overall testing strategy and coverage goals | 2026-05-15 |
| `docs/testing/testing guide.md` | Practical testing guide and patterns | 2026-05-12 |

## Features & Development

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/features/FEATURES.md` | Full feature inventory | 2026-05-15 |
| `docs/features/FEATURE_DOCUMENTATION_COMPLETE.md` | Feature documentation status | 2026-05-15 |
| `docs/development/TIME_SEMANTICS.md` | Time provider semantics and patterns | 2026-05-10 |
| `docs/development/DAO_ACCESS_GUARDRAILS.md` | DAO access guardrails and approved caller lists | 2026-05-12 |
| `docs/development/BUILD_GUIDE.md` | Build and development environment setup | 2026-05-12 |
| `docs/development/FUTURE-WORK.md` | Known gaps and future improvements | 2026-05-18 |

## Pipeline Plans & Evaluations

| Document | Purpose |
|----------|---------|
| `pipeline3_implementation_plan.md` | Pipeline 3 (Receipt) implementation plan |
| `pipeline3_evaluation.md` | Pipeline 3 post-implementation evaluation |
| `pipeline4_implementation_plan.md` | Pipeline 4 (Recurring) implementation plan |
| `pipeline4_evaluation.md` | Pipeline 4 post-implementation evaluation |

---

## Quick Reference

**I need to understand the architecture →** Start with `ARCHITECTURE.md`  
**I need to find which file owns a segment →** Start with `CODEBASE_SEGMENTS.md`  
**I need to trace a dependency chain →** Start with `DEPENDENCY_MAP.md`  
**I need to add a new feature →** Read `ARCHITECTURE.md` → `LEGAL_PATHS.md` → `CODEBASE_INVENTORY.md`  
**I need to fix a bug →** Read `CODEBASE_SEGMENTS.md` → `CODEBASE_INVENTORY.md` → `ENGINE_INTERACTION_MAP.md`  
**I need to change a DAO →** Check `DB_WRITE_OWNERSHIP.md` → `DAO_ACCESS_GUARDRAILS.md`  
**I need to update DI bindings →** Read `hilt-bindings-map.md`
