# Money M-2a Handoff — Normalized Analytics Aggregate Foundation

Date: 2026-10-04
Status: IMPLEMENTED — REVIEW AND VALIDATION PENDING

## Scope

M-2a adds the narrow boundary needed before migrating analytics producers. It
does not migrate `DailyBucketEngine`, `TotalsAggregationEngine`, or any other
analytics caller.

## Implemented contract

- `MoneyAggregateBuilder.fromNormalizedRows` aggregates values that are already
  in one target currency and never invokes a second conversion.
- A normalized-currency mismatch is unavailable rather than silently converted.
- Slice-local exclusion counts are required as explicit quality context.
- Empty input is available and empty; all-excluded input is unavailable rather
  than a fake zero total.
- Requested and actual rate basis, source currencies, valid-rate dates, and
  included/excluded counts remain observable on the typed result.
- `NormalizedAnalyticsMoneyAdapter` rejects a quality count that does not match
  the supplied slice exclusions. Callers cannot silently attach a whole-window
  exclusion count to a smaller slice.

## Files

- `app/src/main/java/com/yourname/expensetracker/domain/core/money/NormalizedMoneyRow.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateMetadata.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsMoneyAdapter.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilderNormalizedRowsTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsMoneyAdapterTest.kt`

## Validation

NOT RUN by instruction. The user subsequently requested continued downstream
implementation with combined validation deferred. Run the M-2a and downstream
focused profiles through the durable validation runner after strict review;
implementation continuation does not mean this foundation's gates have passed.

## Remaining risk

At foundation handoff creation the adapter was not yet used by analytics
engines. M-2b/M-2c consumers are now authored but remain unvalidated; see
`money-m2c1-20261004-handoff.md` and `money-m2c2-20261004-handoff.md`. Their
slice-local exclusions and caller compatibility still require independent
review and the combined deferred validation queue.
