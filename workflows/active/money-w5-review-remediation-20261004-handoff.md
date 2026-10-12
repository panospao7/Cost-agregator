# Wave 5 Review Remediation Handoff ? October 4, 2026

## Status

**Implementation remediation: authored; validation: pending; strict review: pending.**

This handoff records the source changes made in response to the independent Wave 5 review. It does not claim that Wave 5 is complete. Validation must run later on one stable, Wave-5-scoped fingerprint through `scripts/validation-runner.ps1`.

## Remediated areas

- Normalized money rows now represent exactly one transaction, reject duplicate transaction IDs, and reject aggregate-bucket counts at the typed boundary.
- Analytics slices enforce target-currency equality, reject implicit relabeling, and fail closed when excluded rows cannot be attributed to the requested dimension.
- Category, weekday, monthly, pace, totals, advanced analytics, and anomaly paths use normalized inputs or a checked homogeneous compatibility boundary; unavailable money is not projected as a valid zero result.
- Advanced category/merchant/statistical outputs omit unavailable slices instead of publishing fabricated totals, and histogram/sparkline paths fail closed when a component cannot be normalized.
- Receipt parsing no longer uses home currency as source-currency evidence. Missing or conflicting source currency produces unresolved money and routes through receipt review rather than an actionable expense path.
- OCR/manual receipt failure drafts now keep currency unresolved instead of labeling a failed receipt with the user home currency; the disabled legacy receipt-create API documents its unresolved sentinel contract.
- Receipt tax allocation uses deterministic source-currency minor-unit allocation with explicit residual/unallocated tax and per-item persistence; category totals are not copied to every item.
- Tax allocation and persistence now enforce both item-share conservation and category-plus-unallocated conservation against the requested tax, with a mixed classified/unclassified regression test.
- Remaining Insights daily totals, anomaly history, advanced compatibility aggregation, and internal personality compatibility paths now consume normalized values or return an unavailable/empty result; no changed analytics path retains direct `effectiveAmount` arithmetic.
- Analytics UI state now exposes typed money availability, reports unavailable current totals through the controlled error state, and the deprecated scalar money helper fails closed instead of fabricating EUR.
- Redacted export validation requires the exact `REDACTED_V1` root shape and bounded keyset batches for scanned-receipt cleanup.

## Validation queue

Not run in this remediation turn. The required later queue includes:

1. Compile.
2. Money foundation and adapter tests.
3. Receipt parser/source-currency tests.
4. Tax allocation/service/repository tests.
5. Daily/totals/monthly/pace tests.
6. Advanced/category/weekday/personality/insights tests.
7. Export anonymizer and backup/privacy tests.
8. Migration and architecture guards required by the active plan.
9. Current-fingerprint raw-money and full static-guard profiles.

The inherited `raw_money_aggregates` debt remains a separate track. No baseline, allowlist, exception, or ratchet was changed by this remediation.

## Remaining review gates

- Inspect the complete diff with `review-strict` after source changes settle.
- Resolve any compile/test/guard findings from the serialized validation queue.
- Keep unrelated Codex, campaign, verdict, coverage, generated, and Wave 4-only files outside the Wave 5 commit boundary.
- Do not mark M-0 through M-2d complete until current-fingerprint evidence and strict review pass.
