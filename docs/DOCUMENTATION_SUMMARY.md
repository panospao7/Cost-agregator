# ExpenseTracker Documentation Index

> **Last updated:** 2026-09-21  
> **Current DB version:** v148 (`APP_DATABASE_SCHEMA_VERSION = 148`, `data/database/AppDatabase.kt`; schema policy + migration baseline v145 in `data/database/DatabaseSchemaPolicy.kt`)  
> **Kotlin source files:** ~1075 (538 domain, 306 data, 166 ui, 35 di)  
> **DAOs:** 68 · **Entities:** 70 registered · **Hilt @Module files:** 33 · **ViewModels:** 40 · **Segments:** 39

---

## Core Architecture Docs

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/architecture/ARCHITECTURE.md` | Master architecture guide — layers, data flow, components, drift log | 2026-09-07 |
| `docs/architecture/CODEBASE_INVENTORY.md` | Ground-truth inventory of screens, VMs, routes, repos, entities, DAOs, DI modules | 2026-09-07 (re-verified 2026-09-21) |
| `docs/architecture/CODEBASE_SEGMENTS.md` | 39-segment ownership map for targeted AI analysis | 2026-09-21 |
| `docs/architecture/DEPENDENCY_MAP.md` | Major dependency maps with call-chain consumer tables | 2026-09-07 |
| `docs/architecture/ENGINE_INTERACTION_MAP.md` | Engine-to-pipeline impact matrix with risk levels | 2026-09-07 |
| `docs/architecture/LEGAL_PATHS.md` | Single allowed implementation path for each major operation — architecture law | 2026-09-07 |

## Component Maps

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/architecture/dao-map.md` | DAO mapping to entities and consuming repositories | 2026-09-07 |
| `docs/architecture/hilt-bindings-map.md` | Module interface→implementation binding map | 2026-09-07 |
| `docs/architecture/route-viewmodel-map.md` | NavigationDestination→ViewModel mapping for all screens | 2026-09-07 |
| `docs/architecture/VIEWMODEL_INJECTION_MAP.md` | ViewModels with full Hilt injection lists + complexity heatmap | 2026-09-07 |

## Architecture Map Suite (backend, UI, DB structural)

The backend maps moved here from the former `docs/analyses and debug master/` location.

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/architecture/COMPLETE-BACKEND-MAP.md` | Exhaustive file-by-file listing of backend files (domain/data/DI) | 2026-09-07 |
| `docs/architecture/BACKEND-MAP-INDEX.md` | Quick navigation index for the backend map suite | 2026-09-07 |
| `docs/architecture/BACKEND-DEPENDENCIES.md` | Critical dependency chains with ASCII art diagrams | 2026-09-07 |
| `docs/architecture/DB_STRUCTURAL_ANALYSIS_MODEL.md` | DB structural analysis model (PR-GR-11, shadow-only) | 2026-09-03 |
| `docs/architecture/COMPREHENSIVE_UI_MAP.md` | Frontend inventory: screens, components, navigation, theming | 2026-09-07 |
| `docs/architecture/COMPREHENSIVE_UI_VISUAL_MAP.md` | Visual navigation / application flow map | 2026-09-07 |
| `docs/architecture/UI_COMPONENT_LIBRARY.md` | `ui/components/` component library reference | 2026-09-07 |
| `docs/architecture/UI_REFERENCE_INDEX.md` | Frontend quick-reference index | 2026-09-07 |
| `docs/architecture/SHARED_UI_PRIMITIVES.md` | Shared UI primitives architecture | 2026-09-07 |
| `docs/architecture/NAVIGATION_ARCHITECTURE.md` | Navigation architecture | 2026-09-07 |
| `docs/architecture/HISTORICAL_CATEGORY_IDENTITY_PLAN.md` | Historical category identity plan (text preserved; status re-verified) | 2026-09-07 |
| `docs/architecture/import-graph.json` | Import/dependency graph data (`dependency-graph-v1`) | 2026-09-07 |

## Database & Write Ownership

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/DB_WRITE_OWNERSHIP.md` | Table family→approved writer map with enforcement rules | 2026-09-06 |
| `docs/development/DAO_ACCESS_GUARDRAILS.md` | DAO access guardrails and approved caller lists with CI enforcement | 2026-05-01 |

## Currency & Money

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/currency/money-aggregate-contract.md` | MoneyAggregate contract, builder rules, RP-06c money-quality conversion contracts | 2026-09-21 |
| `docs/currency/rate-basis-policy.md` | Rate basis selection policy (LATEST, TRANSACTION_DATE, etc.) | 2026-05-30 |
| `docs/currency/money-boundary-guard.md` | Money boundary guard rules (G-MONEY-01 through G-MONEY-21) | 2026-05-20 |

## Privacy & Security

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/architecture/PRIVACY_UI_ARCHITECTURE.md` | Privacy UI architecture and capability gating | 2026-09-07 |
| `docs/architecture/SENSITIVE_DIAGNOSTICS_POLICY.md` | Policy for sensitive diagnostic data handling | 2026-09-07 |

## Testing

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/testing/TESTING_MASTER_STATUS.md` | Master testing status across all pipelines | 2026-05-06 |
| `docs/testing/MASTER_TESTING_STRATEGY.md` | Overall testing strategy and coverage goals | 2026-05-12 |
| `docs/testing/testing guide.md` | Practical testing guide and patterns | 2026-04-04 |

`docs/testing/` also holds coverage reports, E2E plan, golden-test/CI-gate docs, known-hanging-tests and test-ignore classifications.

## Features & Development

| Document | Purpose | Last Updated |
|----------|---------|-------------|
| `docs/features/FEATURES.md` | Full feature inventory | 2026-05-31 |
| `docs/features/FEATURE_DOCUMENTATION_COMPLETE.md` | Feature documentation status | 2026-04-04 |
| `docs/development/TIME_SEMANTICS.md` | Time provider semantics and patterns | 2026-08-09 |
| `docs/development/DAO_ACCESS_GUARDRAILS.md` | DAO access guardrails and approved caller lists | 2026-05-01 |
| `docs/development/BUILD_GUIDE.md` | Build and development environment setup | 2026-04-04 |
| `docs/development/FUTURE-WORK.md` | Known gaps and future improvements | 2026-05-04 |
| `docs/development/VALIDATION_RUNNER.md` | Serialized validation runner — all Gradle/tests/guards run through it | 2026-09-16 |

## Major Sub-Doc Trees

- `docs/ci/` — CI guard framework standard, GR campaign ledgers and evidence, `db-mediation/` and `db-structural/` (GR-12/GR-13) evidence, guard policy/commands, CI verification records.
- `docs/currency/` — money contracts: MoneyAggregate contract, rate-basis policy, money boundary guard.
- `docs/atomicity/` — cancellation safety and transactional event consistency policies (plus cancellation atomicity baseline).
- `docs/workers/` — worker execution policy, worker requirements matrix, worker baseline inventory.
- `docs/development/` — build guide, DAO access guardrails, time semantics, future work, serialized validation runner.
- `docs/guardrails/` — guardrail program charter and state plus per-PR campaign plans (`PR-GR-*`, `PR-MIG-*`, `PR-REL-*`, `PR-CI-11`, `PR-FIN-00`).

Other trees: `docs/privacy/` (raw storage policy), `docs/reference/`, `docs/time/` (time boundary guard), `docs/releases/` (changelog/release notes), `docs/analyses and debug master/` (now 2026-09 audit findings + `remediation/`), `docs/archive/superseded-campaign-docs/` (archived trackers incl. `PIPELINE_ISSUES_MASTER_TRACKER.md`, engine campaigns, pipeline validations).

## Historical Note: Pipeline Plans & Evaluations

The former `pipeline3_implementation_plan.md`, `pipeline4_implementation_plan.md` and their evaluation files no longer exist at the repo root. Pipeline campaign material is archived under `docs/archive/superseded-campaign-docs/` (pipeline validations in `fresh-debug-sessions-2026-09-04/`, issue trackers in `trackers/`).

---

## Quick Reference

**I need to understand the architecture →** Start with `ARCHITECTURE.md`  
**I need to find which file owns a segment →** Start with `CODEBASE_SEGMENTS.md`  
**I need to trace a dependency chain →** Start with `DEPENDENCY_MAP.md`  
**I need to add a new feature →** Read `ARCHITECTURE.md` → `LEGAL_PATHS.md` → `CODEBASE_INVENTORY.md`  
**I need to fix a bug →** Read `CODEBASE_SEGMENTS.md` → `CODEBASE_INVENTORY.md` → `ENGINE_INTERACTION_MAP.md`  
**I need to change a DAO →** Check `DB_WRITE_OWNERSHIP.md` → `DAO_ACCESS_GUARDRAILS.md`  
**I need to update DI bindings →** Read `hilt-bindings-map.md`  
**I need to run builds/tests/guards →** Only via `docs/development/VALIDATION_RUNNER.md` (`scripts/validation-runner.ps1`)
