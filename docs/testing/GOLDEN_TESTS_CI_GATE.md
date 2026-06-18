# Golden Tests CI Gate

## Overview

Golden tests are **release-blocking** tests that verify the app's most critical business contracts. They must pass before any merge to main.

## Running Golden Tests

```bash
# Run all golden tests (strict mode — missing golden file = FAIL)
./gradlew app:testDebugUnitTest --tests "com.yourname.expensetracker.golden.*"

# Run with update mode (regenerate expected outputs after intentional changes)
./gradlew app:testDebugUnitTest --tests "com.yourname.expensetracker.golden.*" -PupdateGoldens=true
```

## CI Configuration

Add to your CI pipeline (GitHub Actions / GitLab CI / etc.):

```yaml
# GitHub Actions example
golden-tests:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'
    - name: Run Golden Tests
      run: ./gradlew app:testDebugUnitTest --tests "com.yourname.expensetracker.golden.*"
    - name: Upload test report on failure
      if: failure()
      uses: actions/upload-artifact@v4
      with:
        name: golden-test-report
        path: app/build/reports/tests/testDebugUnitTest/
```

## Golden Test Suite (13 tests)

| Test | Contract |
|------|----------|
| MulticurrencyAnalyticsDashboardBudget | Multi-currency conversion, partial rates |
| RecurringPlannedActualNoDoubleCount | Occurrence claim, no double-count |
| AnalyticsDashboardBudgetParity | Dashboard == analytics == budget |
| ReceiptMatchingNoDoubleCount | Receipt link unique, analytics once |
| BackupRestoreRoundtrip | Write barrier, data integrity |
| PrivacyGateEnforcement | Deny when disabled, audit persisted |
| NotificationReviewDashboardBudget | Dedup, events, dashboard total |
| GroupSettlementBudgetOffset | myShareAmount, isNotMine excluded |
| TransactionLifecycleFullContract | CRUD + events + dedup |
| StaleRateCurrencyConversion | 24h+ rate → RATE_STALE |
| ForecastSynthesis | Deterministic Monte Carlo |
| CsvExportImportRoundtrip | Formula injection neutralized |
| MerchantCategorizationDedup | Greek→Latin normalization |

## Rules

1. **Missing golden file = FAIL.** Never silently pass.
2. **Review diffs carefully** before committing updated goldens.
3. **Only update goldens** when behavior intentionally changes.
4. **Golden files are committed** to version control (not gitignored).
5. **Numeric tolerance** is 0.01 for money values, 1.0 for Monte Carlo percentiles.

## Updating Golden Files

When production behavior intentionally changes:

1. Run with `-PupdateGoldens=true` to regenerate
2. Review the diff in each `.json` file
3. Confirm the new values are correct
4. Commit the updated golden files with the code change

## File Structure

```
app/src/test/resources/golden/
  multicurrency_analytics_dashboard_budget.json
  recurring_planned_actual_no_double_count.json
  analytics_dashboard_budget_parity.json
  receipt_matching_no_double_count.json
  backup_restore_roundtrip.json
  privacy_gate_enforcement.json
  notification_review_dashboard_budget.json
  group_settlement_budget_offset.json
  transaction_lifecycle_full_contract.json
  stale_rate_currency_conversion.json
  forecast_synthesis.json
  csv_export_import_roundtrip.json
  merchant_categorization_dedup.json
```
