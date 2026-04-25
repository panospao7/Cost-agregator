# D3 Standalone Medium Final Summary

This document consolidates the D.3 standalone-medium review campaign across all 15 subbatches (D.1 through D.15).

Note: This summary collects per-subbatch findings and registry updates to inform remediation planning for the remaining issues.

Total subbatches reviewed: 15/15

Registry update status
- The MASTER-ISSUE-REGISTRY has been updated to reflect all D.3 review findings. See the D.3 section updates in the global registry for concrete wording changes and status markers.

Aggregate status across all subbatches (approximate, per-subbatch tallies are reported in individual reviews):
- RESOLVED: 58 (approximate total across D.1, D.2, D.3, D.4, D.5, D.6, D.13, D.14; D7–D12 and D15 contain additional resolutions not fully tallied here)
- PARTIALLY_RESOLVED: 11 (approximate; several subbatches report partial fixes)
- STILL_OPEN: 61 (approximate; remaining issues span multiple domains such as time determinism, domain boundaries, parser invariants, UI localization, and DB constraints)
- FALSE_POSITIVE: 0 (not observed in the consolidated per-subbatch reviews)

Summary of common open themes
- Time determinism and injected clocks: Several subbatches highlight the need to replace System.currentTimeMillis() with a TimeProvider or injected clock to achieve deterministic tests and rollover handling.
- Domain boundary and cross-layer DTOs: Recurrent need to decouple domain models from persistence entities and introduce domain DTOs, with mappers moved to data/adapter layers.
- Parser invariants and input validation: Ongoing requirements to harden numeric parsing, date parsing, and invariant checks to prevent NaN, negative values, or invalid dates from propagating.
- UI text localization and hard-coded strings: Replacing hard-coded English text with string resources and UI-localization-friendly patterns remains a frequent cleanup target.
- DB/DAO correctness and upserts: Reworking upserts, enforcing unique constraints, and centralizing transactional boundaries to reduce race conditions and data integrity issues.
- Async/concurrency and cancellation safety: A pattern of catching non-cancellation exceptions and not propagating CancellationException; the push is to rethrow CancellationException and improve structured concurrency.
- Planar code hygiene: Transitional improvements around formatting/parsing utilities, formatters, and shared utils to avoid locale drift and thread-safety issues.

Most successful cleanup areas (examples from subbatches)
- SubBatch D.4 and D.13 show multiple resolved items, including consolidations of date-time formatting (DateTimeFormatter usage) and domain-to-data layer refactors (DashboardWidgetUiMapper, DomainTransactionFilter domain enums).
- SubBatch D.3 and D.6 demonstrate early success in introducing deterministic time handling and async patterns in weathering concurrency and thread-safety concerns.
- SubBatch D.9 and D.11 show fixes for exception handling in asynchronous workflows and safer currency formatting via centralized formatters.

Impact and remediation planning
- This final summary will feed the detailed remediation planning for the remaining open issues across the 15 subbatches. The plan will prioritize: (1) enforcing deterministic time, (2) breaking domain/data boundary coupling, (3) hardening parser invariants, (4) removing UI hard-coding, and (5) tightening DB constraints and transactional boundaries.

Appendix
- This summary references per-subbatch reviews (D.1 through D.15) and the updated registry in MASTER-ISSUE-REGISTRY.md.

End of summary
