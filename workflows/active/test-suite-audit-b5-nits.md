# B5 nit pass (collected from B5 reviews; test-only; one combined validation)

Rules: test-only. Strengthen, never weaken. Re-verify each line reference before editing (files moved during B5).
Size guards use `assertEquals("<table> size", N, rows.size)` so the actual size is printed.
Skip an item if it is already done, and report it.

## N-A (writer 1)
1. MerchantKeyContractTest :198-199 `contains || isNotEmpty` → exact expected value (derive from production). :382 loop only asserts when both values are non-null → add assertNotNull guards first.
2. GenericTransactionParserTest :258 and GreekBankParserTest :210 determinism tests: add assertNotNull on the results before comparing them.
3. MerchantCleanerTest :79 literal NBSP character → Kotlin unicode escape (backslash, u, 00A0) in the string literal. The :78 double-space row only checks a negative → assert the exact cleaned value. The 'consecutive cleaning' and "McDonald's" rows only check non-empty → exact values.
4. NotificationCaptureServiceFallbackTest: size guards use assertTrue → assertEquals with the actual size. FINANCE_PACKAGES loop: add an emptiness guard.
5. B5-10 row-count guards: CsvCellSanitizerNegativeAmountTest :35,:60; CsvEscapingTest :50,:112,:144; JsonExpenseImporterTest :70; RoundtripContractTest :95,:142,:206; CsvExpenseImporterTest :209.
6. RecommendationCacheServiceTest dedup table: assertEquals(12, rows.size).
7. B5-15 size guards: CategoryTest :28, ConvertersTest :27, MerchantNormalizationRepositoryTest ~:49, ExpenseWithCategoryFormattedAmountTest, RecurringExpenseRepositoryTest tables.

## N-B (writer 2)
8. OnDeviceAiServiceErrorContractTest and CloudAiServiceErrorContractTest `checkAllRows`: before rethrowing a CancellationException, attach the row failures recorded so far as suppressed exceptions. CE semantics stay unchanged.
9. SpendingHeatmapEngineTest: total-spend expected value → literal 600.0. The determinism check should compare the full cell lists, not just counts and totals.
10. LocationInsightsEngineTest sorting test: assert the exact order (placeName "A" first, and so on) and the exact spends, not just `>=`.
11. SideEffectMetadataFactoryTest: remove the vacuous double-contains (`contains("\"idempotencyKey\"")` after `contains("idempotencyKey")` → keep the stricter one) and the unused SafeEventMetadata import. DiagnosticSideEffectEventWriterTest: remove the unused diagnosticWriter/timeProvider mocks, only if they are truly unused.
12. BankConnectionLifecycleCoordinatorOutcomeTest barrier-denial table: add a comment "do not add an allowed row without clearing answers".
13. FinancialHealthCalculatorTransactionTypeTest: `composite in 87..88` → assertEquals(88, ...).
14. MonteCarloSpendingSimulatorTest zero-history: add assertNull(result.probabilityUnderBudget), after confirming production returns null.
15. BusinessExpenseReportGeneratorTest ~:605: literal euro-sign character → Kotlin unicode escape (backslash, u, 20AC) in the string literal.

Validation: one multi-filter targeted-unit-test run over every touched class, with per-class JUnit counts.

## N-B additions from the B5-19 reviewer-strict review (Python; validate with pytest-subset seed_rows, expecting 252 passed)
16. scripts/test_migrate_db_policy_seed_rows.py: pin the parameter tuple on the two l2 rows (~:10416 `l2-exact-identity-0`, ~:10426 `l2-wrong-overload`) to `("Long", "String", "String?", "String?", "String?")`, which is the entry chosen today. Then change `_select_target` (~:9815-9816) to require exactly one candidate: `assert len(candidates) == 1, f"selector={...!r} matched {len(candidates)}"`. Include the selector in the message.
17. In `test_match_cases_table_size_guard`, also assert the ids are unique: `len({p.id for p in _MATCH_CASES}) == len(_MATCH_CASES)`.
18. Trim the temporary W1/W2/W3 bookkeeping comment (~:10866-10871) to "update `_EXPECTED_MATCH_CASE_COUNT` when rows change".
