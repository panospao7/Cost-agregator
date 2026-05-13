# Golden Test Expected Outputs

This directory contains expected JSON outputs for golden scenario tests.

## Structure

```
golden/
  multicurrency_analytics_dashboard_budget/
    expected.json
  notification_review_dashboard_budget/
    expected.json
  receipt_matching_analytics/
    expected.json
  recurring_planned_actual/
    expected.json
```

## Usage

- Tests compare actual production output against these JSON files.
- Missing file = test FAILURE (strict mode).
- To regenerate: run tests with `-DupdateGoldens=true`.
- Review diffs carefully before committing updated goldens.

## Rules

- All money values use home currency display amount.
- Numeric tolerance: 0.01 (for floating point).
- Timestamps and auto-generated IDs should be in `ignoredFields`.
- Arrays are compared in order unless `sortArraysByField` is set.
