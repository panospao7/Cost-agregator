# Test Suite Inventory

Generated from audit of 489 test files across all source sets.

**Date**: 2026-05-12
**Commit**: Current HEAD
**Source**: `app/src/test/java`, `app/src/test/kotlin`, `app/src/androidTest/java`, `app/src/androidTest/kotlin`

---

## Quick Stats

| Metric | Count |
|--------|-------|
| Total test files | 489 |
| Source set `test` (unit) | 463 |
| Source set `androidTest` (instrumented) | 26 |
| Total test methods (estimated) | ~3,500+ |
| Files with `@Ignore` | ~25 |
| Ignored test methods/classes | ~80+ |

### By Action

| Action | Count | % |
|--------|-------|---|
| KEEP | 385 | 79% |
| DELETE | 26 | 5% |
| REWRITE | 17 | 3% |
| MOVE | 5 | 1% |
| MOVE_TO_NIGHTLY | 32 | 7% |
| UNKNOWN_NEEDS_LOCAL_RUN | 24 | 5% |

### By Value

| Value | Count | % |
|-------|-------|---|
| P0_CRITICAL | ~45 | 9% |
| P1_HIGH | ~155 | 32% |
| P2_MEDIUM | ~140 | 29% |
| P3_LOW | ~120 | 25% |
| P4_NEGATIVE_VALUE | ~28 | 6% |

### By Test Type

| Type | Count | Description |
|------|-------|-------------|
| PURE_ENGINE | ~80 | Pure calculation/logic tests, no DB/mocks |
| DAO_ROOM_CONTRACT | ~25 | Room in-memory DAO tests |
| REPOSITORY_INTEGRATION | ~45 | Repository with DAO mocks |
| LIFECYCLE_CONTRACT | ~15 | Lifecycle coordinator tests |
| MULTI_PIPELINE_SCENARIO | ~12 | E2E multi-pipeline tests |
| GOLDEN | ~20 | Pre-calculated fixture-based tests |
| MIGRATION_SCHEMA | ~3 | Room migration/schema tests |
| VIEWMODEL_STATE | ~30 | ViewModel state verification |
| PARSER | ~15 | Notification/statement parser tests |
| FIXTURE_INFRASTRUCTURE | ~25 | Test helpers, fixtures, base classes |
| STRESS_PERFORMANCE | ~30 | Heavy stress/concurrency tests |
| TRIVIAL_MODEL | ~15 | Data class tautology tests |
| SOURCE_TEXT_ASSERTION | ~3 | Tests that grep production source |
| MOCK_ORCHESTRATION | ~20 | Tests that mainly verify mock calls |
| PRIVACY_SECURITY | ~10 | Privacy gate/redaction tests |
| BACKUP_RESTORE | ~5 | Backup/restore contract tests |
| WORKER_RUNTIME | ~8 | Worker execution/idempotency tests |
| ANDROID_SMOKE | ~3 | Android framework smoke tests |
| COMPOSE_UI | ~0 | No Compose UI tests found |
| UNKNOWN | ~120 | Mixed/ambiguous |

---

## Key Findings

### Strengths
1. **Money/Currency domain** — exceptionally well-tested with 11+ dedicated test files including golden fixtures
2. **Room Migrations** — 71 migration tests + fresh-install parity tests protect schema integrity
3. **Analytics Engine** — ~130 tests across TotalsAggregationEngine, InsightsEngine, AdvancedAnalyticsEngine
4. **Parser Coverage** — ~15 parser test files covering Greek banks, Revolut, Google Wallet, SMS, generic parsers
5. **Group Lifecycle** — DB-backed scenario tests with real Room in-memory for group operations
6. **Privacy Gate** — Strong contract tests for CloudAiPrivacyGate, CompositePrivacyGate, fail-closed behavior
7. **Recurring No-Double-Count** — Dedicated scenario test protecting critical financial invariant

### Weaknesses
1. **~28 tests have NEGATIVE value** — data-class tautologies, source-text assertions, dead stubs
2. **Mock-verification addiction** — ~20 ViewModel tests only verify mock calls were made, not actual state
3. **E2E pipeline tests are shallow** — mock-heavy, use deprecated APIs, don't exercise real pipelines
4. **Investment domain undertested** — InvestmentDaoTest is a TODO skeleton
5. **Bank sync has no real integration test** — BankApiIntegrationTest is mock-only
6. **~80+ tests are @Ignore'd** — some valid (stress tests), some permanently dead
7. **Source-text assertion tests break on any refactor** — 3 files grep production Kotlin source

### Structural Issues
1. **Money tests in `scenarios/` package** should be in `domain/core/money/`
2. **MultiCurrencyTestFixture** is a shared fixture but lives in `domain/currency/`
3. **Duplicate tests** — PrivacyGateEnforcementScenarioTest duplicates PrivacyCloudLocationDeniedScenarioTest
4. **Dead utility** — FlowTestUtils.kt (8 lines, no code)

---

## Recommended First Cleanup PR (Target: ~30 files, ~600 lines removed)

### Phase 1: Delete Now (immediate, no replacement)
1. Delete 3 source-text assertion tests
2. Delete data-class tautology tests (CategoryBreakdownTest, PeriodTotalTest, AnalyticsStateStressTest)
3. Delete dead stubs (BudgetForecastingEngineStubTest, FeatureExtractorTest, SpeechInputGatewayLifecycleTest)
4. Delete dead utility (FlowTestUtils.kt)
5. Delete duplicate (PrivacyGateEnforcementScenarioTest)

### Phase 2: Move to Nightly (preserves value, reduces CI time)
6. Move 32 stress tests to nightly suite
7. Configure Gradle `nightlyTest` task with relaxed timeouts

### Phase 3: Move to Correct Package
8. Move 5 money/currency tests from `scenarios/` to appropriate `domain/` packages
9. Move MultiCurrencyTestFixture to `testfixtures/`

### Phase 4: Rewrite Targeted Files
10. Rewrite BankApiIntegrationTest with real test bank endpoint
11. Rewrite InvestmentDaoTest from skeleton
12. Merge duplicate parser registry tests

---

## Batch Detail Links

- [Batch 001](test-batches/batch-001.md) — androidTest DAOs + consistency + contracts + AI providers + DB
- [Batch 002](test-batches/batch-002.md) — Domain analytics + bank + budget + business + carbon + cashflow
- [Batch 003](test-batches/batch-003.md) — Domain categorization + challenge + config + core + currency
- [Batch 004](test-batches/batch-004.md) — Domain debug through util + scenarios (first half)
- [Batch 005](test-batches/batch-005.md) — Scenarios (second half) + service + UI + verification + workers

---

## See Also

- [Pruning Candidates](TEST_PRUNING_CANDIDATES.md) — Detailed DELETE/REWRITE/MOVE rationale
- [Coverage Matrix](TEST_COVERAGE_MATRIX.md) — Area-by-area coverage assessment
- [Batch Review Index](TEST_BATCH_REVIEW_INDEX.md) — Quick navigation to all batch documents
