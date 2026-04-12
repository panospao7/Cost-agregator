# Deep Analysis — Batch 12: Database - Core Entities (@reviewer)

> **[B.4 SYNC]** All B.4-scope issues in this file have been resolved. See `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-12.md` and `docs/reviews/REVIEW-B4.md` for evidence and waivers.

## Scope
- data/database/entity/Expense.kt
- data/database/entity/Category.kt
- data/database/entity/Budget.kt
- data/database/entity/RecurringExpense.kt (not found — exists as ManualRecurringExpense.kt)
- data/database/entity/SavingsGoal.kt
- data/database/entity/Subscription.kt (not found in codebase)
- data/database/entity/Warranty.kt
- data/database/entity/ReturnWindow.kt
- data/database/entity/Recommendation.kt (exists as RecommendationEntity.kt)
- data/database/entity/PendingReview.kt
- data/database/entity/ScannedReceipt.kt
- data/database/entity/ReceiptItemCategorization.kt

## @reviewer Findings

### Issues Found
| # | File | Severity | Type | Description | Suggested Fix |
|---|------|----------|------|-------------|---------------|
| 1 | `data/database/entity/Expense.kt` | MAJOR | Data integrity | `generateDedupeKey()` uses locale-sensitive `("%.2f").format(amount)`. On locales like `el_GR`, this can emit `,` instead of `.`, producing dedupe keys that do not match SQL/migration-generated keys and weakening the unique dedupe guarantee. **[RESOLVED BY A.4]** `Expense.generateDedupeKey()` delegates to `DuplicateDetectionPolicy.generateDedupeKey()` using `Locale.ROOT`. | Use locale-invariant formatting (`String.format(Locale.ROOT, "%.2f", amount)`) or a dedicated money normalizer. |
| 2 | `data/database/entity/Expense.kt` | MAJOR | Constraint | `mySharePercentage` / `myShareAmount` have no invariant enforcement. Negative values, percentages over 100, or share amounts larger than `amount` will corrupt `effectiveAmount`, and the same formula is reused in SQL aggregations. **[RESOLVED BY B.4 — Batch 8]** Share fields validated on insert/update; DB CHECK constraints added. | Add constructor/repository validation and DB `CHECK` constraints for `0..100`, `0..amount`, and mutual exclusivity. |
| 3 | `data/database/entity/Budget.kt` | MAJOR | Uniqueness | Schema allows multiple active budgets for the same category and multiple active overall budgets, but DAO code reads them as singular (`LIMIT 1`). That makes budget evaluation and notifications nondeterministic. **[RESOLVED BY B.4 — Batch 4]** Partial unique indexes added; transactional deactivation enforced. | Enforce one active budget per category plus one active overall budget via partial unique indexes or transactional repository guards. |
| 4 | `data/database/entity/PendingReview.kt` | MAJOR | Foreign key | `suggestedCategoryId` is a raw `Long?` with no FK to `categories`. Category deletion/merge can leave stale IDs in review records that downstream UI/AI flows still treat as valid. DOWNGRADED | Add nullable FK to `Category(id)` with `ON DELETE SET NULL` and index it. |
| 5 | `data/database/entity/ScannedReceipt.kt` | MAJOR | Foreign key | `suggestedExpenseId` is relation-like but has no FK or index. Suggested matches can point to deleted expenses, and approving a stale suggestion can create a broken link. DOWNGRADED | Add FK to `Expense(id)` with `ON DELETE SET NULL` and index `suggestedExpenseId`. |
| 6 | `data/database/entity/ReceiptItemCategorization.kt` | MAJOR | Foreign key | `suggestedCategoryId` and `userCorrectedCategoryId` are stored without FKs. Category cleanup leaves orphaned item categorizations and breaks correction history integrity. DOWNGRADED | Add nullable FKs to `Category(id)` for both fields with `ON DELETE SET NULL`; retain indexes. |
| 7 | `data/database/entity/Warranty.kt` | MAJOR | Relationship constraint | `Index(["receiptId"], unique = true)` forces at most one warranty per receipt, but the entity is product-level (`productName`) and receipts can contain multiple warrantied items. DOWNGRADED | Remove the one-per-receipt uniqueness or scope uniqueness to a finer key such as `(receiptId, productName)` or a receipt-item identifier. |
| 8 | `data/database/entity/RecommendationEntity.kt` | MAJOR | Data type / FK | `sourceArtifactId` is a required `String`, while `AiArtifactEntity.id` is `Long`; no FK can be enforced, and empty-string sentinels are allowed despite the field being documented as a link. DOWNGRADED | Change `sourceArtifactId` to `Long?` with FK `ON DELETE SET NULL`, or rename/document it as a non-relational external ID and make it nullable. |
| 9 | `data/database/entity/ManualRecurringExpense.kt` | MAJOR | Default value | `isSubscription` defaults to `true`. Any path creating a recurring expense without explicitly overriding it will silently classify all recurring expenses as subscriptions. **[RESOLVED BY B.4 — Batch 4]** Default changed to `false`; subscription flows now explicitly opt in. | Default new rows to `false`; only migration/backfill should preserve legacy values, and subscription flows should opt in explicitly. |

### Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | Batch plan vs actual entity set (`RecurringExpense.kt`, `Subscription.kt`, `Recommendation.kt`) | MAJOR | The approved batch lists 3 entity files that do not exist as such. Current code uses `ManualRecurringExpense.kt`, `RecommendationEntity.kt`, and has no `Subscription.kt` entity. This reviewable surface no longer matches the approved plan. **[RESOLVED BY B.4]** Plan/doc notes updated in REVIEW-B4.md; name mismatch documented as acceptable. | Align the plan/docs with the actual schema, or restore the intended entity split/naming so migrations, reviews, and tests target the right artifacts. |
| 2 | `Expense`, `Budget`, `SavingsGoal`, `Warranty`, `ReturnWindow`, `PendingReview`, `ScannedReceipt`, `ReceiptItemCategorization` | MAJOR | Many business invariants are not enforced at the persistence layer: positive amounts/durations, `confidence` in `0..1`, valid thresholds, and status/date consistency. Invalid rows can still be inserted through DAOs/repositories/tests and will flow into analytics/UI. **[RESOLVED BY B.4 — Batch 8]** Repository validation and DB CHECK constraints added for applicable fields. | Add repository validation plus migration-time `CHECK` constraints where possible. |

### Summary
- Total issues: 11
- Files with issues: 6/12 listed files (plus 2 equivalent replacement files: `ManualRecurringExpense.kt`, `RecommendationEntity.kt`)
- Requirements met: no — the batch defines the required entities, but several key integrity rules are not enforced at schema level (uniqueness, same-group relationships, FK coverage, safe defaults).
- Testing adequate: no — I do not see evidence here of migration/entity tests covering the broken invariants above (single current user, unique expense linkage, canonical key uniqueness, non-null area key, subscription default correctness).

No material issues found in:
- `Category.kt`
- `SavingsGoal.kt`
- `ReturnWindow.kt`
