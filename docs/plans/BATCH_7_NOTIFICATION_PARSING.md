# Batch 7: Notification Parsing

Batch Overview
- Scope: Implement a robust parsing pipeline for inbound notifications (including Google Wallet INR support) to extract standardized events and amounts for reconciliation and reporting.
- Complexity: High. Multiple formats, currency handling, and edge case scenarios.
- Estimated Effort: 10-14 person-days.

## Batch Plan (M22, M23, M24, L6-L9)

### M22 – Google Wallet INR Support (renamed from Batch 7 M22)
- Objective: Add INR-aware parsing support for Google Wallet notifications and ensure currency normalization.
- Key Activities:
  - Extend notification schema handlers to detect INR currency and convert to standard internal representation.
  - Implement currency normalization pipeline for INR to internal cents representation.
  - Add tests for INR-specific cases (rupees, paisa, symbol variations).
- Dependencies: Currency conversion module, intl libraries, test fixtures for Google Wallet.
- Risks: Currency edge cases; mis-parsing of INR formatting in legacy notifications.
- Acceptance Criteria:
  - INR amounts parsed correctly in 99% of INR examples.
  - No regression for non-INR flows.
- Estimated Effort: 3-4 days.

### M23 – Notification Parsing Core
- Objective: Implement core parsers for various notification formats (email, push, webhook) and harmonize to a single event schema.
- Key Activities:
  - Implement modular parsers per format; unify output to EventModel.
  - Normalize timestamps, currencies, and category mapping.
  - Add end-to-end tests with sample payloads.
- Dependencies: Parser framework, test payloads, event schema.
- Risks: Format drift from providers; versioning concerns.
- Acceptance Criteria:
  - All supported formats produce consistent EventModel instances.
- Estimated Effort: 3-4 days.

### M24 – Validation, Enrichment & Verification
- Objective: Validate parsed events, enrich with metadata, and verify end-to-end throughput.
- Key Activities:
  - Enrich events with merchant name, location, and category hints.
  - Implement validation checks (missing fields, currency correctness).
  - End-to-end integration tests from inbound notification to downstream consumers.
- Dependencies: Enrichment data sources, validation rules, downstream sinks.
- Risks: Missing enrichment data; data quality issues.
- Acceptance Criteria:
  - All events pass validation; enrichment populated consistently.
- Estimated Effort: 4 days.

### L6-L9 – Root Cause, Implementation Strategy, Dependencies, Risk, Verification, & Acceptance
- Root Cause:
  - Previous parsing relied on brittle formats; INR-specific flows were not supported.
- Implementation Strategy:
  - Introduce INR-aware parsing module, add format adapters, and implement strict schema.
- Dependencies:
  - Currency module, provider payload specs, testing fixtures.
- Risk Assessment:
  - External format changes; mitigated by contract testing and versioned parsers.
- Verification Plan:
  - Contract tests against known payloads; performance tests for large bursts.
- Acceptance Criteria:
  - INR flows parsed with accuracy; overall pipeline stable under load.
- Estimated Effort: 2-3 days.

## Rollback / Safety
- Feature flag for enabling INR parsing; quick rollback if parsing degrades results.

## Dependencies
- Notification payload specs, currency conversion utilities, test datasets, downstream consumers.

## Verification Plan
- End-to-end tests, simulated notification feeds, and regression tests for existing formats.

## Acceptance Criteria (Summary)
- INR-aware notifications parsed reliably; cross-format normalization achieved; no regressions.
