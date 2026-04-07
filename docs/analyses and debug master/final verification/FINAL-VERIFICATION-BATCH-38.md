# Final Verification — Batch 38: Categorization, Challenges & Config

## Scope
- `com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt`
- `com/yourname/expensetracker/domain/categorization/ContextualInferenceEngine.kt`
- `com/yourname/expensetracker/domain/categorization/MerchantCanonicalizer.kt`
- `com/yourname/expensetracker/domain/categorization/SemanticKeywordMatcher.kt`
- `com/yourname/expensetracker/domain/categorization/CategoryKeywords.kt`
- `com/yourname/expensetracker/domain/categorization/GreeklishNormalizer.kt`
- `com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt`
- `com/yourname/expensetracker/domain/config/AppConfig.kt`
- `com/yourname/expensetracker/domain/currency/CurrencyConverter.kt`
- `com/yourname/expensetracker/data/repository/CategoryRepository.kt`
- `com/yourname/expensetracker/data/repository/MerchantCategoryRepository.kt`
- `com/yourname/expensetracker/data/database/dao/MerchantCategoryDao.kt`
- `com/yourname/expensetracker/ui/screens/challenge/SpendingChallengesViewModel.kt`
- `com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
- `com/yourname/expensetracker/data/database/entity/Expense.kt`
- `com/yourname/expensetracker/data/database/entity/MerchantCategory.kt`
- `com/yourname/expensetracker/domain/util/StringDistanceUtils.kt`
- `com/yourname/expensetracker/ui/screens/challenge/SpendingChallengesScreen.kt`
- `com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt`
- `com/yourname/expensetracker/domain/usecase/expense/CategorizeExpenseUseCase.kt`
- `com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
- `com/yourname/expensetracker/domain/intelligence/ml/MerchantNormalizer.kt`
- `com/yourname/expensetracker/domain/intelligence/CrossSourceDeduplication.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceSemanticDuplicateDetector.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt:46-67` | High | Logic / Performance | `checkNoSpendStreak()` walks backward forever with one DB read per day until it finds spending. For empty or very sparse histories, this can run unbounded. | B | CONFIRMED | Stop at the oldest expense date or a capped lookback window, or replace the loop with an aggregate query for the previous spend day. |
| 2 | `com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt:38,57,113,172` | High | Logic | Challenge spend calculations use `expense.amount` instead of `expense.effectiveAmount`, so shared/not-mine semantics diverge from the rest of the app and challenges overcount spending. | D | CONFIRMED | Sum `effectiveAmount` or move challenge totals to DAO aggregates that already encode share logic. |
| 3 | `com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt:118-146` | High | Logic | Budget-style challenges use remaining-budget percentage as both progress and completion/success state. They can mark a fresh under-budget challenge completed immediately at 0 spend, and later mark a successful under-budget run unsuccessful once any spending occurs. | B | CONFIRMED | Separate display progress from completion/success rules; budget/category challenges should complete on time and succeed when `spent <= target`. |
| 4 | `com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt:160-162` | Medium | Logic | `getLastSpendDate()` is a stub that always returns “yesterday”, so `NoSpendStatus.lastSpendDate` is wrong whenever the last real purchase was earlier. | D | CONFIRMED | Query the latest purchase date before `before` instead of returning a placeholder. |
| 5 | `com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt:95` | Low | Arithmetic safety | `durationDays * 24 * 60 * 60 * 1000L` performs most multiplication in `Int`, so very large durations overflow before widening to `Long`. | D | DOWNGRADED | Cast `durationDays` to `Long` before multiplying. |
| 6 | `com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt:134` | Low | UI logic | `daysRemaining` can go negative after expiry, leaking invalid values to the UI. | D | DOWNGRADED | Clamp with `coerceAtLeast(0)`. |
| 7 | `com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt:91` | Low | ID generation | Challenge IDs use `System.currentTimeMillis()`, which can collide for rapid consecutive creations. | D | DOWNGRADED | Use a UUID or persisted auto-generated ID. |
| 8 | `com/yourname/expensetracker/domain/categorization/MerchantCanonicalizer.kt:39-45,72-76` | Medium | Canonicalization | The single-character prefix `"s"` is treated as a removable business prefix, so names beginning with `s ` are incorrectly stripped (`"s market" -> "market"`). | D | DOWNGRADED | Remove `"s"` from the prefix list or require multi-character business prefixes. |
| 9 | `com/yourname/expensetracker/domain/categorization/ContextualInferenceEngine.kt:79-97` | Medium | Logic | `isLikelySurname()` drops all words shorter than 3 chars before checking `BUSINESS_INDICATORS`, so 2-letter legal suffixes like `AE`/`SA`/`AB` are ignored and business names can be misclassified as surnames. | D | CONFIRMED | Check business indicators before length filtering, or allow 2-character tokens for that check. |
| 10 | `com/yourname/expensetracker/domain/categorization/CategoryKeywords.kt:163-166,217-218,231-243`; `com/yourname/expensetracker/domain/categorization/SemanticKeywordMatcher.kt:107-116` | High | Incorrect categorization | Equal-confidence duplicate keywords across categories (`pharmacy`, `farmakeio`, `netflix`, `spotify`, `prime video`, etc.) are resolved by declaration/order effects, so common merchants are routed to the earlier category instead of the intended one. | B | DOWNGRADED | Remove overlaps, add explicit precedence, or fail ties explicitly instead of relying on ordering. |
| 11 | `com/yourname/expensetracker/domain/categorization/GreeklishNormalizer.kt:156-164` | Medium | Logic | `getVariations()` compares normalized input against raw alias lists, so case/spacing/Greek-script variants can miss and the Greeklish fallback silently skips valid alternatives. | R | DOWNGRADED | Normalize aliases up front and compare normalized-to-normalized values only. |
| 12 | `com/yourname/expensetracker/domain/currency/CurrencyConverter.kt:174-207` | Medium | Validation | `storeRate()` / `storeRates()` accept zero, negative, `NaN`, and infinite exchange rates, which can later poison conversions. | R | CONFIRMED | Reject non-finite/non-positive rates at write time and skip invalid bulk entries. |
| 13 | `com/yourname/expensetracker/domain/config/AppConfig.kt:55` | Medium | Privacy / Config | The Nominatim User-Agent hardcodes a personal email address in shipped source/config. | R | CONFIRMED | Move the contact string to injected/build config and use a project-owned support address. |
| 14 | `com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt:95-97,218-220,429-438` | Low | Performance | Each categorization call reloads cache fragments via three separate `getCacheData()`-backed accessors, taking the cache mutex repeatedly for the same snapshot. | D | CONFIRMED | Fetch one `CacheData` snapshot per categorization/debug call and reuse it. |
| 15 | `com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt:500-506` | Low | Matching accuracy | The fuzzy matcher prefilters candidates by the first two characters, so a typo in those first two characters prevents otherwise-valid edit-distance matches from ever being considered. | D | CONFIRMED | Loosen the prefix heuristic or fall back to a wider candidate set when the strict prefix filter yields none. |
| 16 | `com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt:441-445` | Low | Concurrency | `getCategoryIdByName()` populates cache under lock, then reads `cachedCategoryNameToId` outside the returned snapshot; a concurrent invalidation can still null out the map between those steps. | D | CONFIRMED | Return/use the name-to-id map from the `CacheData` snapshot itself. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt:124-127,203` | High | Unimplemented feature logic | `REDUCE_SPENDING` is documented as “Spend less than previous period”, but its progress formula is identical to a simple absolute budget cap and there is no stored baseline/reference period. | Add explicit baseline/reference-period data to the model and compute reduction against prior spend, or remove/rename the challenge type until implemented correctly. |
| 2 | `com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt:107,167` | Medium | Hidden truncation | Challenge progress and 30-day “saved today” calculations call `ExpenseDao.getExpensesBetween(...)` without overriding the DAO’s default `limit = 2000`, so heavy accounts can get silently incomplete challenge totals. | Page to exhaustion or add uncapped aggregate DAO queries for challenge calculations. |
| 3 | `com/yourname/expensetracker/domain/categorization/SemanticKeywordMatcher.kt:81`; `com/yourname/expensetracker/domain/categorization/CategoryKeywords.kt:242` | Medium | Regex matching | Keyword matching wraps every keyword in `\b...\b`, which breaks exact matches for keywords ending in non-word characters such as `disney+`; that keyword never matches as written. | Build boundary rules that handle punctuation-at-edge tokens instead of blindly using `\b` on every keyword. |
| 4 | `com/yourname/expensetracker/domain/categorization/CategoryKeywords.kt:46,68` | Low | Data definition bug | `"roasters"` is declared twice in the Food keyword map; Kotlin keeps the last entry, silently downgrading the intended confidence from `0.85` to `0.70`. | Deduplicate keyword literals and add a test/static check that fails on repeated keys within a category map. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | D4 | `SpendingChallengeManager.kt:42,61` | The code only adds purchase amounts; without subtraction, floating-point drift cannot turn a positive spend total into a false zero/non-zero boundary in the way described. The report’s reproduction assumes unsupported negative purchase rows. |
| 2 | D8 | `CategorizationEngine.kt:165` | `SemanticKeywordMatcher` already lowercases and Greeklish-normalizes its input, and the cited example still matches. Passing the raw merchant preserves potentially useful semantic context; the report did not show an actual failing case. |
| 3 | D9 | `CategoryKeywords.kt:17` | `SemanticKeywordMatcher` uses `\bbox\b`, so it does not match `Xbox`, `Dropbox`, or `BoxNow` as claimed. This is a tuning opinion, not a demonstrated code defect. |
| 4 | D10 | `CategoryKeywords.kt:82` | The same boundary matching prevents false hits inside strings like `ABN`; the report identifies possible ambiguity, but not a concrete implementation bug. |
| 5 | D14 | `ContextualInferenceEngine.kt:58-63` | The duplicated `"idis"` entry has no runtime effect beyond redundant data. |
| 6 | D15 | `GreeklishNormalizer.kt:127-134` | The current accent-strip → diphthong pipeline still produces the intended transliteration; this is a maintainability observation, not an actual malfunction. |
| 7 | D16 | `GreeklishNormalizer.kt:73-75` | `toGreek()` is not used anywhere in the analyzed code path, so this does not produce an observed defect in Batch 38 behavior. |
| 8 | D17 | `CategorizationEngine.kt:92-93` | Letting dependency failures surface is not itself a logic bug; the proposed fallback would hide underlying data/DB errors rather than fix a demonstrated defect. |
| 9 | D18 | `CurrencyConverter.kt:28` | Keeping `HRK` in a supported-currency enum can be intentional for historical/manual data; the code does not assume current API support for every enum value. |
| 10 | D19 | `CurrencyConverter.kt:242` | `formatAmount()` is display-oriented, and locale-sensitive decimal separators are expected behavior on user devices, not a bug. |
| 11 | D22 | `MerchantCanonicalizer.kt:95-102` | The suffix-removal branch already handles non-letter/digit separators, including the report’s example class of input; no concrete failing case was demonstrated. |
| 12 | D-C6 | `FeatureFlags.kt / FeatureToggles.kt` | The files are absent, but no enforceable requirement in the codebase shows that a feature-flag system had to exist for this batch. This is an architecture wish, not a verified defect. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `SpendingChallengeManager` → `SpendingChallengesViewModel` → challenge UI | High | Architecture gap | The UI exposes `activeChallenges`, but nothing loads, persists, or publishes created challenges. `createChallenge()` only returns an in-memory object, so the screen’s active-challenge section remains permanently empty. | `domain/challenge/SpendingChallengeManager.kt`, `ui/screens/challenge/SpendingChallengesViewModel.kt`, `ui/screens/challenge/SpendingChallengesScreen.kt` | Add repository-backed challenge persistence and expose an observable active-challenges flow to the ViewModel. |
| 2 | `CategoryRepository` → merchant-category storage → `CategorizationEngine` cache | Medium | Data flow inconsistency | `CategoryRepository.learnMerchantCategory()` inserts directly into `merchant_categories` without `normalizedCanonicalName` and without invalidating the engine cache. Actual call sites are limited (debug seeding), but writes through this path can stay stale for up to the cache TTL and bypass the schema’s canonical-name field. | `data/repository/CategoryRepository.kt`, `domain/categorization/CategorizationEngine.kt`, `data/database/dao/MerchantCategoryDao.kt`, `data/repository/MerchantCategoryRepository.kt`, `ui/screens/debug/DebugViewModel.kt` | Route all learning through the engine/repository path that normalizes, populates canonical fields, and invalidates cache. |
| 3 | Challenge spending model ↔ app-wide spending model | High | Cross-module logic drift | Challenges total raw `amount`, while budgets/analytics/forecasts rely on `effectiveAmount` or equivalent SQL share logic. Shared expenses therefore show one spend number in challenges and another everywhere else. | `domain/challenge/SpendingChallengeManager.kt`, `data/database/entity/Expense.kt`, `data/database/dao/ExpenseDao.kt` | Standardize challenge calculations on `effectiveAmount` or shared DAO aggregates. |
| 4 | `CategoryKeywords` → `SemanticKeywordMatcher` | High | Cross-layer classification error | Ambiguous keyword tables and matcher tie resolution together create systematic category misroutes for overlapping merchant terms. | `domain/categorization/CategoryKeywords.kt`, `domain/categorization/SemanticKeywordMatcher.kt` | Add explicit category precedence/tie handling and remove equal-confidence overlaps. |
| 5 | Merchant canonicalization/context inference boundary | Medium | Cross-layer false positives | Business suffix handling and surname inference are not aligned: short legal suffixes can survive into Layer 4 logic and let business merchants be treated as surname-like personal merchants. | `domain/categorization/ContextualInferenceEngine.kt`, `domain/categorization/CategorizationEngine.kt`, `domain/categorization/MerchantCanonicalizer.kt` | Preserve/check business-entity signals before surname inference, including 2-character corporate abbreviations. |
| 6 | String-distance utilities across categorization/deduplication modules | Low | Maintainability | Levenshtein distance is duplicated in multiple modules instead of reusing `StringDistanceUtils`, increasing drift risk for future fixes. | `domain/categorization/GreeklishNormalizer.kt`, `domain/util/StringDistanceUtils.kt`, `domain/intelligence/CrossSourceDeduplication.kt`, `data/ai/provider/OnDeviceSemanticDuplicateDetector.kt` | Consolidate all implementations onto the shared utility. |

## Summary
- Total verified issues: 16
- Confirmed: 16 (Critical: 0, High: 4, Medium: 6, Low: 6)
- False positives: 12
- Missed issues found: 4
- Files affected: 9/13

## Key Patterns
- The challenge subsystem is only partially implemented: core calculations are wrong for multiple challenge types, spending semantics diverge from the rest of the app, and the UI has no persistence-backed active-challenge pipeline.
- The categorization stack has several brittle handoff points: canonicalization, Greeklish expansion, and keyword matching all rely on heuristics, but tie handling and normalization contracts are inconsistent.
- Multiple defects come from silent assumptions rather than explicit contracts: cache ordering, default DAO limits, and hardcoded config values all create behavior that looks correct until edge cases appear.
