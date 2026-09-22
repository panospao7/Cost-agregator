# Cell E-03 Audit

Provenance: 2026-09-22; pinned commit `37601232b9778170c57a656a245b199ab6d7d965` (`git rev-parse --short HEAD` = `37601232`); direct astra session; cell E-03.

Scope: MerchantNormalizer and merchant DAO/entities; CategorizationEngine; HybridExpenseClassifier; SemanticKeywordMatcher; CategoryRepository mutations; TransactionSideEffectPlanner post-commit learning. Governing paths: Categorization / Merchant Learning; Category Assignment; Notification Capture; Receipt Mutations. No pre-identified issue IDs.

Coverage log:
- [x] Primary merchant/categorization implementation files and callers: `CategorizationEngine.kt`, `MerchantNormalizer.kt`, `HybridExpenseClassifier.kt`, `SemanticKeywordMatcher.kt`, `ReceiptParser.kt` was enumerated by scope; caller search covered production references.
- [x] DAO/entity mutation paths and uniqueness handling: `MerchantCategoryRepository.kt`, `MerchantCategoryDao.kt`, `MerchantCategory.kt`, `CategoryRepository.kt`; alias repository path inspected by `MerchantNormalizer.kt`.
- [x] TransactionSideEffectPlanner learning path: `planCreated`, `planUpdated`, and `makeMerchantCategoryLearningAction`.
- [x] Relevant tests/guards and cross-cell wiring: `RawDaoArchitectureGuardTest.kt`, `TransactionSideEffectPlannerTest.kt` references, production caller search, and historical debt registry.
- [x] All 15 defect classes considered against the covered paths; no new reportable defect established.

Findings:

None. The apparent direct seed write in `CategoryRepository.ensureDefaultCategories` (`merchantCategoryDao.insertAll`, lines 60-72 at the pin) is the already-tracked `E3-NOW-007` issue and is therefore excluded under the cell rules. The historical C01/C02/C04/C06/E3-NOW-002/E3-NOW-004/E3-NOW-005/E3-NOW-008/E3-NOW-009 items were checked against the pinned implementation and are either fixed or known debt; none is restated.

Key evidence checked:
- `CategorizationEngine.categorizeWithContext` (lines 98-225) reads through the six-layer cascade and has no persistence; `learnMerchantCategory` (483-503) creates the mapping and calls `MerchantCategoryRepository.insert`.
- `MerchantCategoryRepository.insert` (24-35) calls `DatabaseWriteBarrier.checkWritesAllowed`, interprets Room's non-positive conflict result, and invalidates categorization caches only after an inserted row. `MerchantCategoryDao` uses primary-key conflict-ignore semantics; `normalizedCanonicalName` remains a non-unique index, with deterministic query ordering documented and implemented.
- `HybridExpenseClassifier.classify` (84-162) validates ML category IDs and rethrows `CancellationException`; `learnFromCorrection` (226-255) gates global learning through `CategorizationEngine.learnMerchantCategory`.
- `TransactionSideEffectPlanner.planCreated/planUpdated` (35-87) gates learning with `SourceLearningPolicy` and category-change checks. `makeMerchantCategoryLearningAction` (228-262) runs as a post-commit action, rethrows cancellation, and maps other failures to retryable outcomes. Its raw exception message is carried in the retryable outcome, but this is an existing shared post-commit diagnostics debt documented in `docs/atomicity/TRANSACTIONAL_EVENT_POLICY.md`, not a new E-03 finding.
- `RawDaoArchitectureGuardTest.noRawDaoMutatorsOutsideRepositories` (54-91) protects direct merchant DAO writes outside the repository allowlist; the allowlist explicitly contains the known seeding exception.

All 15 classes (1 legal path, 2 barrier, 3 atomicity/TOCTOU, 4 idempotency, 5 cancellation, 6 side-effect timing, 7 money/currency, 8 time, 9 privacy, 10 worker hygiene, 11 data integrity, 12 error handling, 13 wiring/dead code, 14 test correctness, 15 fix-regression) were reviewed for the scoped files and caller traces. No additional pinned evidence met the report threshold.

Provenance: direct astra session; source pin verified exactly; production diff from `37601232..HEAD` under `app config scripts` was empty. No builds or tests were run (static-only audit).
