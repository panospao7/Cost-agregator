# Deep Analysis — Batch 38: Categorization, Challenges & Config (@reviewer)

## Scope
- Primary batch sources reviewed:
  - `app/src/main/java/com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/categorization/ContextualInferenceEngine.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/categorization/MerchantCanonicalizer.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/categorization/SemanticKeywordMatcher.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/config/AppConfig.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt`
- Supporting categorization/challenge/currency files reviewed to verify interactions:
  - `app/src/main/java/com/yourname/expensetracker/domain/categorization/CategoryKeywords.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/categorization/GreeklishNormalizer.kt`
  - `app/src/main/java/com/yourname/expensetracker/data/repository/CategoryRepository.kt`
  - `app/src/main/java/com/yourname/expensetracker/data/repository/MerchantCategoryRepository.kt`
  - `app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantCategoryDao.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/screens/challenge/SpendingChallengesViewModel.kt`
- Requested files not present in the current tree:
  - `domain/categorization/CategorizationModels.kt`
  - `domain/challenge/SpendingChallengeModels.kt`
  - `domain/challenge/SpendingChallengeOrchestrator.kt`
  - `domain/challenge/SpendingChallengeRepository.kt`
  - `domain/config/FeatureFlags.kt`
  - `domain/config/FeatureToggles.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/challenge/SpendingChallengeManager.kt:46-67` | HIGH | Logic / Performance | `checkNoSpendStreak()` walks backwards one day at a time with no lower bound. If the user has no purchase history, or a very long no-spend gap before the first purchase, this loop never terminates and will keep issuing DB reads indefinitely. | Stop at the oldest purchase date (or a configurable lookback cap), or replace the loop with an aggregate/day-bucket query that returns the first prior spend day directly. |
| 2 | `domain/challenge/SpendingChallengeManager.kt:118-146` | HIGH | Logic | Budget-style challenges are judged with `progress >= 100`, but progress is computed as remaining-budget percentage. A user who stays under budget but spends anything at all will finish with `progress < 100` and be marked unsuccessful at challenge end. | Separate “remaining budget” from “completion/success” logic. For budget-limit/category-limit challenges, success should be based on `spent <= target` once the challenge window ends. |
| 3 | `domain/categorization/CategoryKeywords.kt:163-166,214-218,227-232,240-246`; `domain/categorization/SemanticKeywordMatcher.kt:104-116` | HIGH | Incorrect categorization | The keyword tables assign the same high-confidence tokens to competing categories (`pharmacy`/`farmakeio` in Shopping and Health; `netflix`/`spotify`/`prime video` in Entertainment and Subscriptions). The matcher then breaks ties by iteration order, so common merchants are deterministically routed to the earlier category rather than the semantically correct one. | Remove overlapping keywords, or add explicit precedence rules/context filters before final sorting. At minimum, fail ties explicitly instead of relying on map/order stability. |
| 4 | `domain/categorization/GreeklishNormalizer.kt:156-164` | HIGH | Logic | `getVariations()` compares the normalized input against the raw alias list (`normalized in alts`). Because the aliases are not normalized first, many Greek/case/spacing variants never match and the Greeklish layer silently misses valid alternatives. | Pre-normalize all aliases (and ideally precompute a reverse lookup map) before membership checks. Compare normalized input against normalized alias values only. |
| 5 | `domain/currency/CurrencyConverter.kt:174-207` | MEDIUM | Validation | `storeRate()` / `storeRates()` accept zero, negative, `NaN`, and infinite rates without validation. Those values can later propagate into conversions and produce invalid amounts silently. | Reject non-finite or non-positive rates at write time (`require(rate.isFinite() && rate > 0.0)`), and skip/ log invalid bulk entries. |
| 6 | `domain/config/AppConfig.kt:55` | MEDIUM | Security / Config | The Nominatim User-Agent embeds a personal email address directly in source. That leaks personal contact information into the shipped config and makes environment-specific contact data impossible to rotate without code changes. | Move the contact/User-Agent string to injected configuration or build config, and use a project-owned support address rather than personal data. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 7 | `SpendingChallengeManager` ↔ `SpendingChallengesViewModel` | HIGH | Missing pipeline / Architecture | `SpendingChallengesViewModel` exposes `activeChallenges`, but nothing ever loads or persists them (`ui/screens/challenge/SpendingChallengesViewModel.kt:23-43`). `SpendingChallengeManager.createChallenge()` only returns an in-memory object (`domain/challenge/SpendingChallengeManager.kt:82-100`). In practice the feature cannot surface any created challenges, and the repository/orchestrator files requested for this batch do not exist in the codebase. | Introduce a real repository/orchestrator-backed challenge pipeline, persist created challenges, and expose them as a flow consumed by the ViewModel. |
| 8 | `CategoryRepository` ↔ `CategorizationEngine` ↔ merchant-category storage | HIGH | Inconsistent data flow | There are two merchant-learning paths. `CategorizationEngine.learnMerchantCategory()` writes `normalizedCanonicalName` and invalidates the cache (`domain/categorization/CategorizationEngine.kt:450-472`), but `CategoryRepository.learnMerchantCategory()` bypasses that path and inserts only `merchantPattern` (`data/repository/CategoryRepository.kt:75-78`). Because the engine caches mappings for 5 minutes (`domain/categorization/CategorizationEngine.kt:411-446`), direct repository writes are stale immediately and also lose canonical-name data that the DAO schema was designed to store (`data/database/dao/MerchantCategoryDao.kt:14-18`). | Consolidate learning through a single path (preferably the engine/repository pair that also invalidates cache), and always populate canonical-name fields consistently. |

## Summary
- Total issues: 8
- Critical: 0, High: 6, Medium: 2, Low: 0
- Files with issues: 8/13

## Key Patterns
- The challenge subsystem is structurally incomplete: it computes status/progress, but has no persistence/orchestration path for active challenges, so the UI contract is effectively stubbed.
- Categorization logic is split across multiple layers, but the data contracts between those layers are inconsistent: canonical-name data is stored in the schema yet often bypassed, and Greeklish/keyword logic contains silent false-negative and false-positive paths.
- Config and validation responsibilities are too loose: unsafe runtime values (currency rates) and environment-specific identifiers (User-Agent contact info) are accepted directly instead of being validated/injected.
