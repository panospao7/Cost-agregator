# Batch 10: Export/Import Currency

Batch Overview
- Scope: Implement robust currency export/import flows including currency conversion, formatting, and reconciliation across zones.
- Complexity: Medium-High. Internationalization and external financial data dependencies.
- Estimated Effort: 10-14 person-days.

## Batch Plan (M34, M35, M36, M37, M38, M39, L16, C7-C9)

### M34 – Currency Data Model & Imports
- Objective: Extend internal currency model; support imports from external sources.
- Key Activities:
  - Define Currency, ExchangeRate, and Amount types; support multi-currency amounts.
  - Import endpoints for external currency data feeds; schema validation.
- Dependencies: Currency service, external data sources, import queue.
- Risks: Data mismatch between feeds; historical rate gaps.
- Acceptance Criteria:
  - Rates import reliably; amounts serialize with correct precision.
- Estimated Effort: 2-3 days.

### M35 – Export Path & Formatting
- Objective: Implement export formats (CSV/JSON/APIs) with currency normalization.
- Key Activities:
  - Support exporting expenses and balances with currency-aware formatting.
- Dependencies: Export module, formatting libraries.
- Risks: Locale-specific formatting issues.
- Acceptance Criteria:
  - Exports match locale expectations and currency rules.
- Estimated Effort: 2 days.

### M36 – Import Path & Validation
- Objective: Implement currency-aware import path for contributions and expenditures.
- Key Activities:
  - Validate incoming payloads; map to internal currency model; handle errors gracefully.
- Dependencies: Import module, currency service.
- Risks: Import conflicts; invalid currency codes.
- Acceptance Criteria:
  - Imports succeed with proper currency mapping; errors reported clearly.
- Estimated Effort: 2 days.

### M37 – Reconciliation & Rounding Rules
- Objective: Align rounding rules across export/import paths; ensure fiscal integrity.
- Key Activities:
  - Define and implement rounding strategies; preserve summation invariants.
- Dependencies: Financial rules, ledger.
- Risks: Rounding drift across batches.
- Acceptance Criteria:
  - Totals reconcile within tolerances; no negative balances.
- Estimated Effort: 2 days.

### M38 – Carry-over & Mapped Rules (Carry-over from M8, M38 Deliverable)
- Objective: Align across legacy M8 work and new M38 deliverables.
- Key Activities:
  - Migrate and map legacy currency rules to new model; ensure backward compatibility.
- Dependencies: Legacy data, migration scripts.
- Risks: Data loss during migration; edge-case currencies.
- Acceptance Criteria:
  - Legacy data remains consistent after migration; all currency paths available.
- Estimated Effort: 2 days.

### M39 – End-to-End Validation & Rollout
- Objective: Validate end-to-end currency flows and plan production rollout.
- Key Activities:
  - End-to-end tests for export/import, currency conversions, and reconciliation.
- Dependencies: QA environment, test data.
- Risks: Production data drift; post-release issues.
- Acceptance Criteria:
  - All tests pass; rollout plan approved with rollback.
- Estimated Effort: 2 days.

### L16 – Root Cause, Implementation Strategy, Dependencies, Risk, Verification, & Acceptance
- Root Cause:
  - Fragmented currency handling across modules leading to inconsistencies.
- Implementation Strategy:
  - Centralize currency model; unify import/export paths; add conversion utilities.
- Dependencies:
  - Currency service, data feeds, export/import modules.
- Risk Assessment:
  - Rate changes and data feed reliability; mitigated via caching and fallbacks.
- Verification Plan:
  - Integration tests; cross-checks with gold-standard currency data.
- Acceptance Criteria:
  - Consistent currency handling across all flows; verified by automated tests.
- Estimated Effort: 2-3 days.

## Rollback / Safety
- Feature flags for new currency flows; rollback to previous currency handling if needed.

## Dependencies
- Currency service, external data sources, import/export modules, QA tooling.

## Verification Plan
- Automated end-to-end tests and manual QA for edge cases.

## Acceptance Criteria (Summary)
- Currency export/import flows are reliable, precise, and auditable.
