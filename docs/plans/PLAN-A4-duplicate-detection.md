# PLAN A.4 — Duplicate Detection Logic Inconsistencies

## 1. Objective & Blast Radius
- **The Core Issue:** Duplicate detection currently disagrees across domain logic, DAO-level checks, notification auto-accept, statement import, review approval, and AI candidate building. Some paths are currency-blind, some use a 24-hour window while others use a ~5-minute window, some ignore transaction type, and `Expense.generateDedupeKey()` is locale-sensitive, so the same real-world transaction can be treated as duplicate in one path and distinct in another.
- **Blast Radius:**
  - **Notification ingestion / auto-accept / needs-review:** `NotificationProcessingPipeline.kt`, `ExpenseDao.kt`, `PendingReviewDao.kt`, source-stats side effects
  - **Statement import / receipt-assisted review flows:** `ReceiptRepository.kt`, pending review replacement logic, statement-import review queue behavior
  - **Review approval / final expense creation:** `ReviewQueueRepository.kt`, duplicate-to-approved status transitions, atomic insert fallback behavior
  - **Domain duplicate matching engine:** `DetectDuplicateExpenseUseCase.kt`, `CrossSourceDeduplication.kt`, merchant normalization and candidate scoring
  - **Persistence / repository contract:** `ExpenseRepository.kt`, `ExpenseDao.kt`, `Expense.generateDedupeKey()` call sites, supporting pending-review duplicate queries
  - **AI duplicate assist surfaces:** `DedupeJudgeInputBuilder.kt`, `CaptureAssistModels.kt`, `CloudDedupeJudgeService.kt`, `OnDeviceDedupeJudgeService.kt`, and downstream pending-review duplicate-assist UI surfaces such as the review queue / dedupe-assist card if model shape changes
- **Assumptions / Unknowns:**
  - `AppConfig.DUPLICATE_WINDOW_MS` (currently 5 minutes) is the intended canonical blocking window unless existing tests or current UX requirements prove a different named policy value is required.
  - The epic explicitly requires adding **currency** to the dedupe key; it does **not** explicitly require adding transaction type to the persisted key. Verify whether type-blind unique-index collisions still exist after policy/query fixes before changing the persisted key shape further.
  - Existing rows already stored with the old dedupe-key format may coexist with new rows. The execution path must remain correct during mixed old/new data without relying on Room entity/schema changes in this epic.

## 2. The Single Source of Truth (The Standard)
- Define one canonical duplicate-policy utility in the domain layer, preferably `app/src/main/java/com/yourname/expensetracker/domain/intelligence/DuplicateDetectionPolicy.kt`.
- That policy must own **all** of the following for blocking duplicate decisions:
  1. **Window:** one named canonical duplicate window for strict duplicate blocking (default to the shared ~5-minute policy instead of local 24-hour literals).
  2. **Merchant normalization:** use `MerchantKeyGenerator.generate(...)` as the canonical merchant identity, not ad-hoc lowercase/regex normalization for blocking decisions.
  3. **Currency normalization:** compare normalized currency codes case-insensitively, but persist / generate keys in one deterministic canonical form.
  4. **Amount tolerance:** one tolerance constant (currently `0.01`) shared by all duplicate checks.
  5. **Compatible transaction types:** purchases only match purchases, deposits only match deposits, transfers only match transfers, etc.; incompatible types must never be candidate matches.
  6. **Candidate scoring / tie-breaks:** among hard-match candidates, rank deterministically by smallest time delta, then amount delta, then exact merchant/canonical-key confidence, with optional location proximity only as a secondary signal.
  7. **Dedupe key generation:** include currency and use locale-invariant amount formatting.
- If a request/criteria object is needed, place it under `domain/model/` or `domain/dto/`; do **not** create a data-layer DTO for shared duplicate-policy rules.
- All affected ingestion/review/AI paths must consume this one standard instead of carrying their own `24h`, `300_000`, `0.01`, merchant-normalization, or scoring rules.

## 3. File-by-File Execution Checklist

### Execution order / safe batches
1. **Batch 1 — Canonical policy + dedupe-key standardization**
   - **Scope:** `Expense.kt`, new shared duplicate-policy utility, `DetectDuplicateExpenseUseCase.kt`, `CrossSourceDeduplication.kt`
   - **Why first:** this settles the canonical window/currency/type rules before DAO and repository call sites are rewritten.
   - **Validation:** `DedupeKeyTest.kt`, `ExpenseEntityStressTest.kt`, `DetectDuplicateExpenseUseCaseTest.kt`, `DuplicateLogicConsistencyIntegrationTest.kt`
   - **Complete when:** one shared policy owns the window/tolerance/merchant normalization/type compatibility rules and the dedupe key is currency-aware + locale-invariant.
2. **Batch 2 — DAO / repository duplicate-candidate contract**
   - **Scope:** `ExpenseDao.kt`, `PendingReviewDao.kt`, `ExpenseRepository.kt`
   - **Why second:** ingestion/review callers need a dedicated typed/currency-aware candidate query before their local logic can be simplified safely.
   - **Validation:** `ExpenseDaoTest.kt`, `ExpenseDaoBoundaryConsistencyTest.kt`, plus any new focused pending-review duplicate query test if current DAO coverage is missing
   - **Complete when:** duplicate lookup no longer depends on generic reporting queries and every candidate query can express currency + transaction-type compatibility.
3. **Batch 3 — Ingestion / review / AI adoption**
   - **Scope:** `NotificationProcessingPipeline.kt`, `ReceiptRepository.kt`, `ReviewQueueRepository.kt`, `DedupeJudgeInputBuilder.kt`, `CaptureAssistModels.kt`, AI provider compile-neighbors, dedupe-key call-site audits
   - **Why third:** this batch converts the actual production entry points to the shared standard after the policy and DAO contracts are stable.
   - **Validation:** `NotificationProcessingPipelineReliabilityTest.kt`, `ReviewQueueRepositoryTest.kt`, `DedupeJudgeInputBuilderTest.kt`, `JudgePendingReviewDuplicateUseCaseTest.kt`, `CloudDedupeJudgeServiceTest.kt`, `OnDeviceDedupeJudgeServiceTest.kt`, and focused notification/statement duplicate tests if absent
   - **Complete when:** notification auto-accept, statement import, review approval, and AI duplicate-assist candidate building all use the same currency-aware, type-compatible rules.
4. **Batch 4 — Documentation / registry / report sync**
   - **Scope:** A.4 registry block, affected batch reports, matching deep-analysis mirrors
   - **Validation:** only A.4-linked rows/sections are tagged resolved; unrelated A.1/A.2/A.3/A.x findings stay untouched
   - **Complete when:** docs explicitly reflect the unified duplicate-policy standard without over-reporting unrelated resolutions.

### Domain Layer
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/intelligence/DuplicateDetectionPolicy.kt` **(create)**
  - Centralize the canonical duplicate window, amount tolerance, currency normalization, merchant normalization, compatible-type rules, and deterministic scoring/tie-breaks.
  - If the parameter list gets unwieldy, introduce one small domain request/criteria DTO under `domain/model/` or `domain/dto/`.
  - Keep this utility pure/domain-focused; do **not** embed DAO/Room APIs here.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt`
  - Update `generateDedupeKey(...)` so the key includes currency and uses locale-invariant amount formatting.
  - Preserve the existing bucket-based dedupe semantics unless the new shared policy explicitly renames/owns that same bucket logic.
  - Audit and update every call site before removing/changing the old signature.
  - **Do not change** `@Entity`, indices, `effectiveAmount`, or unrelated entity fields in this epic.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/usecase/expense/DetectDuplicateExpenseUseCase.kt`
  - Remove the local source-specific 24-hour drift from the blocking duplicate path.
  - Route duplicate evaluation through the shared policy and pass explicit currency + transaction type from real callers.
  - Replace the current generic range-query dependency with a dedicated duplicate-candidate repository path if Batch 2 introduces one.
  - Preserve call-site compatibility with overloads or additive parameters if required; **do not** keep a silent currency-blind fallback.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/intelligence/CrossSourceDeduplication.kt`
  - Stop owning its own `TIME_WINDOW_MS`, merchant normalizer, and ad-hoc blocking-score heuristics.
  - Delegate hard-match eligibility and candidate ranking to the shared policy so expense and pending-review checks use the same rules.
  - Require compatible transaction type + normalized currency for hard matches.
  - Keep `checkSemanticDuplicate(...)` and AI semantic fallback behavior intact unless a tiny compile-safe signature extension is needed.
  - **Do not** widen this batch into a refactor of `isCrossSourceDuplicate()` unless A.4 regression tests prove that specific API is on the failing production path.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilder.kt`
  - Build candidate sets with the shared duplicate-policy window/tolerance/type-compatibility rules.
  - Exclude deposits/transfers from purchase duplicate candidates (and vice versa).
  - Include transaction type in the AI candidate summary/prompt if that is the safest way to preserve judge accuracy.
  - **Do not** fold in the separate batch-07 “single candidate bypass” behavior change unless the shared-policy refactor makes it unavoidable and the test update explicitly covers it.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt` **(supporting dependency)**
  - Add only the minimum additional duplicate-context field(s) needed by the AI judge (for example, transaction type) if the builder/provider prompts require it.
  - Keep target IDs, amount, currency, dates, and redaction semantics stable.

> [!WARNING]
> Do **not** change Room entity definitions, `@Entity` annotations, or migration strategy just to land A.4. This epic should standardize duplicate logic first, not reshape persistence.

> [!WARNING]
> Do **not** leave a dangerous legacy overload like `generateDedupeKey(amount, merchant, date)` if it silently defaults currency and reintroduces currency-blind behavior.

> [!WARNING]
> Do **not** reintroduce any local `24 * 60 * 60 * 1000L`, `300_000`, or `0.01` duplicate-policy literals in new code. If a second exploratory window is truly needed for AI review assistance, it must still live in the same shared policy with a named reason.

### Data Layer
- [ ] `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
  - Add dedicated duplicate-candidate query APIs that accept currency and transaction-type inputs (or an exact type plus policy-managed compatibility rules).
  - Update `existsBy...InRange`, `getDuplicateCandidate...`, and/or `isDuplicate(...)` so notification/review/statement callers can ask one currency-aware, type-aware duplicate question.
  - Keep analytics/export/reporting queries unchanged; duplicate detection should stop piggybacking on generic range APIs.
  - **Do not** change unrelated ownership/business-expense/location queries in this epic.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt` **(supporting dependency discovered during audit)**
  - Extend pending-review duplicate queries to include compatible `suggestedType` filtering in addition to existing currency matching.
  - Preserve merchant-key fallback behavior for legacy rows where `suggestedMerchantKey` is null.
  - **Do not** change the `PendingReview` entity shape here.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
  - Add a dedicated duplicate-candidate retrieval API for use cases/engines instead of reusing `getExpensesBetween(...)`.
  - Preserve the existing `getExpensesBetween(...)` contract for analytics/export callers.
  - Keep public repository APIs backward-compatible; prefer additive methods/overloads rather than breaking existing callers.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
  - Replace the currency-blind/type-blind blocking checks with shared-policy candidate lookup + judgment before auto-accept and review creation.
  - Ensure `handleAutoAcceptInTransaction()` and `handleNeedsReviewInTransaction()` both pass explicit amount, currency, merchant key, transaction type, and event date into the same policy flow.
  - Preserve routing/source-stats/classifier/budget/anomaly behavior.
  - **Do not** broaden this epic into the oversized-fallback dedupe issue unless the shared helper can be reused trivially without changing behavior beyond A.4.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
  - Remove the local 24-hour statement dedupe literal from the blocking duplicate path; derive the canonical window/tolerance/type/currency rules from the shared policy.
  - Use the same expense-duplicate and pending-review duplicate rules before inserting statement-import reviews or receipt-created expenses.
  - Preserve OCR/parser/classification/warranty behavior and the existing replace/keep/discard pending-review resolution semantics.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`
  - Before approving a review into a real expense, check duplicates with the same shared policy using amount + currency + merchant + transaction type + date.
  - Keep `insertAtomic()` as race protection, but do **not** rely on it as the only duplicate-policy decision point.
  - Preserve correction logging, pending-review status transitions, and `Result.Duplicate` behavior.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt` **(compile-neighbor audit)**
  - Update the `generateDedupeKey(...)` call signature if Batch 1 changes it.
  - If mixed old/new dedupe-key rows can bypass duplicate protection in this path, adopt the same shared pre-insert duplicate helper here; otherwise keep this file to a signature-only update.
  - **Do not** refactor manual-entry validation, recommendation generation, or anomaly hooks in A.4.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt` **(compile-neighbor audit)**
  - Update the `generateDedupeKey(...)` call signature if Batch 1 changes it.
  - **Do not** rewrite the email-receipt fingerprint strategy in A.4 unless a failing regression test proves it conflicts with the new duplicate-policy standard.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt` **(supporting dependency)**
  - Update the prompt body only if `DedupeCandidateSummary` gains extra duplicate-context fields such as transaction type.
  - Keep JSON schema, provider selection, and parsing semantics unchanged.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceDedupeJudgeService.kt` **(supporting dependency)**
  - Mirror the same prompt-shape update as the cloud provider if the candidate summary changes.
  - Keep response parsing and service contract stable.

> [!WARNING]
> Do **not** repurpose `ExpenseRepository.getExpensesBetween(...)` / `ExpenseDao.getExpensesBetween(...)` into dedupe-specific behavior. Those APIs already serve reporting/export paths and must remain backward-compatible.

> [!WARNING]
> Do **not** “fix” A.4 by deleting the current unique dedupe protection, loosening `OnConflictStrategy`, or shipping a blind schema migration. Query/policy alignment comes first; DB insert conflicts remain only the last line of defense.

> [!WARNING]
> Do **not** fold in unrelated findings from the same batches (subscription detection races, source-stats reset drift, ownership-filter semantics, markAsRelevant duplicate handling) unless a failing A.4 regression test proves the change is necessary.

### UI Layer
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/components/ai/DedupeAssistCard.kt` **(audit only / only touch if compile or rendered diagnostics require it)**
  - Re-run compile/regression if duplicate-assist model shape changes.
  - If the UI already renders candidate metadata, thread through any new transaction-type field without redesigning the card.
  - **Do not** change user-facing copy, layout, or review workflow behavior as part of A.4.
- [ ] Review queue / receipt-review presentation flows **(no direct file change expected)**
  - Validate that duplicate outcomes still map to existing statuses/messages after the backend policy is unified.
  - If no compile/runtime UI change is required, leave presentation files untouched.

## 4. Verification Plan
- **Unit Tests:** update and/or run the following as the minimum A.4 verification set.
  - `app/src/test/java/com/yourname/expensetracker/data/database/entity/DedupeKeyTest.kt`
    - Update expected key format to include currency and assert locale-invariant behavior.
  - `app/src/test/java/com/yourname/expensetracker/data/database/entity/ExpenseEntityStressTest.kt`
    - Convert the documented locale bug assertions from `assertNotEquals(...)` to regression assertions that verify identical keys under different locales.
    - Update the “basic dedupe key format unchanged” expectation to the new currency-aware format.
  - `app/src/test/java/com/yourname/expensetracker/domain/usecase/expense/DetectDuplicateExpenseUseCaseTest.kt`
    - Add currency-aware / type-compatible filtering coverage and verify the canonical shared window is used.
  - `app/src/test/java/com/yourname/expensetracker/consistency/DuplicateLogicConsistencyIntegrationTest.kt`
    - Add regressions for:
      - same merchant/amount/time but different currency → **distinct**
      - same merchant/amount/time but purchase vs deposit/transfer → **distinct**
      - canonical window alignment between expense matching and pending-review matching
  - `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoTest.kt`
    - Add currency-aware and transaction-type-aware duplicate query coverage while preserving current within-window/outside-window checks.
  - `app/src/test/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt`
    - Run as boundary regression after DAO duplicate-query changes.
  - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilderTest.kt`
    - Verify incompatible transaction types are excluded from candidate lists and the builder uses the shared window.
  - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/JudgePendingReviewDuplicateUseCaseTest.kt`
    - Update fixture construction if `DedupeCandidateSummary` gains transaction-type context.
  - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeServiceTest.kt`
    - Update sample prompt/input expectations if candidate summaries change.
  - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceDedupeJudgeServiceTest.kt`
    - Mirror the same prompt/input regression coverage as the cloud provider.
  - `app/src/test/java/com/yourname/expensetracker/data/repository/ReviewQueueRepositoryTest.kt`
    - Add approval-path coverage for cross-currency distinct rows, incompatible-type distinct rows, and same-currency/type duplicate transitions.
  - `app/src/test/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipelineReliabilityTest.kt`
    - Add focused coverage for the shared duplicate-policy inputs used by auto-accept and pending-review creation.
  - `app/src/test/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipelineStressTest.kt`
    - Run as regression if constructor wiring or helper usage changes.
  - `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryStressTest.kt`
    - Add/adjust statement-import duplicate assertions if practical.
  - **Create if no focused test already exists:**
    - `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryStatementDedupeTest.kt`
      - Focus on statement import duplicate suppression with currency + type compatibility.
    - `app/src/test/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipelineDuplicatePolicyTest.kt`
      - Focus on notification auto-accept / needs-review duplicate policy behavior without unrelated routing noise.
- **Syntax/Lint:**
  - Ensure no targeted file still carries a local duplicate-policy literal (`24h`, `300_000`, `0.01`) once the shared policy is introduced, except where the literal already belongs to the canonical policy/config owner.
  - Ensure no targeted dedupe path still uses locale-default amount formatting (`"%.2f".format(amount)` or `String.format(...)` without an explicit locale).
  - Ensure no imports were broken by adding shared policy classes, new DTO/model fields, or prompt-shape updates.
  - Rebuild after each micro-batch; minimum bar is a clean `:app:compileDebugKotlin`.
  - Run `:app:testDebugUnitTest` after all A.4 changes land.
  - Re-run the relevant DAO/instrumentation tests after `ExpenseDao` duplicate-query changes.

## 5. Documentation & Registry Updates (CRITICAL)
- **Registry Update:**
  - In `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, append `[RESOLVED BY A.4]` to **only** the exact six-line A.4 block supplied for this task:
    1. `### A.4: Duplicate Detection Logic Inconsistencies`
    2. `**Batches affected:** 05, 07, 12, 33, 41, 43`
    3. `**Severity:** HIGH`
    4. `**Description:** Duplicate detection is currency-blind across notification auto-accept, statement import, and review approval. The 24-hour cross-source dedupe window is too broad for legitimate repeat purchases. DB-level dedupe uses a ~5-minute window. Candidate filtering ignores transaction type, so deposits/transfers can match as purchase duplicates. Dedupe key generation uses locale-sensitive amount formatting.`
    5. `**Affected files:** \`DetectDuplicateExpenseUseCase.kt\`, \`Expense.generateDedupeKey()\`, \`ExpenseDao.kt\`, \`ExpenseRepository.kt\`, \`NotificationProcessingPipeline.kt\`, \`ReceiptRepository.kt\`, \`ReviewQueueRepository.kt\`, \`CrossSourceDeduplication.kt\`, \`DedupeJudgeInputBuilder.kt\``
    6. `**Suggested fix:** Include currency in the dedupe key. Centralize duplicate policy (window, merchant normalization, amount tolerance, scoring) behind one shared component. Filter candidates by compatible transaction type. Make dedupe key generation locale-invariant.`
  - Do **not** mark adjacent A.x epics as resolved.
- **Batch Reports:**
  - Update only the A.4-related issue rows/summary sentences in these final verification files:
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-05.md`
      - Mark only the `DetectDuplicateExpenseUseCase` transaction-type candidate-pruning issue as `[RESOLVED BY A.4]`.
      - Do **not** mark the `isNotMine`/ownership-filter duplicate-query finding unless separately fixed.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-07.md`
      - Mark only the `DedupeJudgeInputBuilder` transaction-type candidate-filter issue as `[RESOLVED BY A.4]`.
      - Do **not** mark the single-candidate AI-bypass issue unless it was intentionally fixed and fully tested inside A.4.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-12.md`
      - Mark only the locale-sensitive `Expense.generateDedupeKey()` findings as `[RESOLVED BY A.4]`.
      - Do **not** touch the unrelated `PendingReview.suggestedType` type-safety issue.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-33.md`
      - Mark only the currency-blind duplicate-identity row(s) and matching high-level “transaction identity is fragmented” summary as `[RESOLVED BY A.4]`, **if and only if** the shared currency-aware policy is fully implemented.
      - Do **not** mark subscription, source-stats reset, or `markAsRelevant` duplicate issues.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-41.md`
      - Mark only the 24-hour window / scoring drift row(s) and the explicit “cross-source dedupe policy ↔ DB duplicate policy” summary as `[RESOLVED BY A.4]`.
      - Mark the `isCrossSourceDuplicate()` source-name-only issue only if that API was actually fixed as part of A.4.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-43.md`
      - Re-read the file and mark only the explicit duplicate-policy drift note if it is present.
      - If no A.4-specific note exists, leave unrelated sections untouched; do **not** manufacture a resolution tag for a different issue family.
  - Update matching deep-analysis mirrors only where the same A.4 issue family is explicitly described:
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-05.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-05-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-07.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-07-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-12.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-12-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-33.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-33-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-41.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-41-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-43.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-43-DEBUGGER.md`
  - In every report file, append `[RESOLVED BY A.4]` only to the exact row/summary sentence that maps to the implemented A.4 fix. Do **not** bulk-edit unrelated findings.
