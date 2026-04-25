## Technical Plan

### Scope
- In: remaining standalone-medium issues tied to parser strictness, invalid enum handling, non-finite numeric rejection, model/write-path invariant enforcement, regex/date parsing strictness, and AI artifact cache identity hardening.
- In: open registry/review items from D.1-D.8 and D.13 that map to this theme, plus two directly-related live-code follow-ups discovered during planning: `CaptureAssistModels.kt` amount validation and `SuggestCategoryFallbackUseCase` cache identity alignment.
- Out: unrelated standalone-medium cleanup such as UI copy/resources, export formatting outside parser strictness, DB indexing/constraint epics already owned by B.4, category dictionary cleanup, OCR runtime wiring, and non-validation architectural refactors.
- Assumptions / unknowns:
  - `SuggestReceiptExtractionUseCase.stableSourceHash(...)` is the approved reference implementation for deterministic AI artifact identity.
  - D.7 Apple/Uber parser work must stay aligned with the existing B.11 email parsing plan; do not create a competing helper path.
  - `MileageTracking` is a Room entity, so constructor-level `require(...)` checks are only safe if they do not break read-path recovery for existing rows; repository/write-boundary validation is the safe default.
  - `CaptureAssistModels.kt` is outside the user’s explicit review-doc list, but it is a still-open validation gap of the exact requested class (`NaN`/`Infinity`/non-positive amount acceptance). If strict D3-only scope is required, this item can be split out before execution.
  - `SuggestCategoryFallbackUseCase` still uses `input.hashCode().toString()` in live code even though it is not called out in the D.1 artifact-hashing bullet; treat it as an audit-follow-up in the same family, not a blocking D3 acceptance item unless scope is expanded.

### Files
- modify: `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceCategorizationAssistService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceDedupeJudgeService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/ai/provider/DashboardBriefingResponseParser.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/model/NotificationParsingModels.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/model/WarrantyExtractionModels.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/BusinessExpenseRepository.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/database/entity/MileageTracking.kt` *(only if Room-safe invariants are proven; otherwise keep validation in repository/helper)*
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/GenerateTransactionInsightUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCase.kt` *(audit/align only if scope approved)*
- create: `app/src/main/java/com/yourname/expensetracker/data/ai/provider/StrictAiJsonParsing.kt`
- create: `app/src/main/java/com/yourname/expensetracker/domain/ai/util/AiArtifactSourceHash.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/util/ClipboardAmountParser.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/util/AmountUtils.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/email/provider/EmailReceiptParser.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/email/provider/AppleReceiptParser.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceCategorizationAssistServiceTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceDedupeJudgeServiceTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceDashboardBriefingServiceTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/ai/provider/DashboardBriefingResponseParserTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/ai/model/NotificationParsingModelsTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/ai/model/WarrantyExtractionModelsTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/ai/model/CategorizationAssistInputTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/ai/util/AiArtifactSourceHashTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/ui/util/ClipboardAmountParserTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/util/CsvExpenseImporterTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/util/AmountUtilsTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/repository/BusinessExpenseRepositoryTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/database/entity/MileageTrackingValidationTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/JudgePendingReviewDuplicateUseCaseTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCaseTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/GenerateTransactionInsightUseCaseTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCaseTest.kt` *(only if scope approved)*
- modify: `app/src/test/java/com/yourname/expensetracker/data/email/provider/AppleReceiptParserTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/email/provider/UberReceiptParserTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/receipt/WarrantyTextExtractorTest.kt`
- modify: `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` *(closeout only, after code/tests land)*

### Implementation Steps

#### Grouped issue list
1. **AI parser strictness / invalid enum coercion**
   - D.3: `OnDeviceDashboardBriefingService` confidence parsing is lenient.
   - D.3 + D.13: `OnDeviceDedupeJudgeService` trusts `optLong`, `optDouble`, and raw `Enum.valueOf()`.
   - D.4: `OnDeviceCategorizationAssistService` can emit `categoryId = 0` and `confidence = NaN`.
   - D.2: `ReviewScreen` still calls `TransferDirection.valueOf(...)` directly.
2. **Invariant enforcement / non-finite numeric rejection**
   - D.1: `WarrantyExtractionModels.kt` lacks validation.
   - D.1: `NotificationParsingModels.kt` lacks validation.
   - D.12-related follow-up: `CategorizationAssistInput.amount` still accepts `NaN` / `Infinity` / non-positive values.
   - D.3: `MileageTracking` validation is only partially resolved; write-path enforcement is still missing.
3. **Artifact cache/hash identity hardening**
   - D.1: `JudgePendingReviewDuplicateUseCase`, `ExplainPendingReviewUseCase`, `GenerateDashboardBriefingUseCase`, `GenerateTransactionInsightUseCase`, and `CategorizeReceiptItemsUseCase` still derive `sourceHash` from `hashCode().toString()`.
   - Audit note: `SuggestCategoryFallbackUseCase` shows the same live-code pattern and should be aligned if scope allows.
4. **Regex / numeric / date parsing strictness**
   - D.1: `CsvExpenseImporter` splits CSV with `line.split(",")` and silently rewrites failed dates to `System.currentTimeMillis()`.
   - D.8: `ClipboardAmountParser` can partial-match grouped amounts.
   - D.8: `AmountUtils` still accepts malformed comma grouping like `1,0000`.
   - D.7: `AppleReceiptParser` / `UberReceiptParser` currency detection uses unbounded substring checks.
   - D.7: `UberReceiptParser.parseUberDate()` still infers year from current time instead of `receivedAt`.
   - D.7: `WarrantyTextExtractor` line-start date regex is missing `MULTILINE` anchoring semantics.

#### Shared utility opportunities
1. **Strict AI JSON parsing helper**
   - Preferred new helper: `StrictAiJsonParsing.kt` in the AI provider layer.
   - Responsibilities: safe enum lookup (case-sensitive or case-insensitive per contract), finite float parsing, positive/non-zero ID parsing, nullable long parsing that rejects coercive `0`, and bounded confidence parsing (`0f..1f`).
   - Consumers: `OnDeviceCategorizationAssistService`, `OnDeviceDedupeJudgeService`, `DashboardBriefingResponseParser`, and optionally cached-payload deserializers in AI use cases.
2. **Deterministic artifact identity helper**
   - Preferred new helper: `AiArtifactSourceHash.kt` in domain AI util.
   - Responsibilities: canonical field ordering, locale-invariant serialization, explicit null markers, SHA-256 hashing, and per-use-case builders so `sourceHash` no longer depends on Kotlin/JVM object identity semantics.
   - Reference implementation: current `SuggestReceiptExtractionUseCase.stableSourceHash(...)`.
3. **Do not create a second amount/date parsing stack**
   - Tighten `AmountUtils` and reuse it from `ClipboardAmountParser`, `CsvExpenseImporter`, and email parsers.
   - Reuse `BaseEmailParser` helpers for Apple/Uber fixes instead of ad hoc `SimpleDateFormat`/substring logic.
4. **Safe write-boundary validation instead of risky Room read-path breaks**
   - For `MileageTracking`, prefer repository/helper validation first.
   - Only add entity constructor invariants if test proof shows Room hydration and legacy data reads remain safe.

#### Batching strategy
1. **Batch 1 — AI parser strictness and safe enum handling**
   - Scope:
     - Introduce strict JSON parsing helper.
     - Harden `OnDeviceCategorizationAssistService`, `OnDeviceDedupeJudgeService`, `DashboardBriefingResponseParser`, and `ReviewScreen` transfer-direction parsing.
   - Dependencies:
     - None.
     - This batch should land before source-hash work to reduce merge conflicts in the AI surface.
   - Execution plan:
     - Replace `optLong()`/`optDouble()` trust with explicit required/nullable parsers.
     - Reject non-finite confidence values and invalid IDs.
     - Replace raw `Enum.valueOf()` calls with safe parsing that returns `null` or failure, depending on contract.
     - Decide whether UI-safe parsing should live in `ReviewScreen` directly or in a tiny helper extracted for unit coverage.
   - Validation:
     - `:app:compileDebugKotlin`
     - Focused tests for on-device AI parser services and any new parser helper tests.
   - Completion criteria:
     - Invalid AI JSON no longer becomes `0`, `NaN`, or a thrown enum parse.
     - `ReviewScreen` no longer crashes on unexpected transfer-direction strings.

2. **Batch 2 — Artifact `sourceHash` identity hardening**
   - Scope:
     - Replace remaining `hashCode().toString()` artifact identities in the five D.1 use cases.
     - Optionally align `SuggestCategoryFallbackUseCase` if scope is approved.
   - Dependencies:
     - Reuse the shared SHA-256 utility introduced in this batch.
     - Keep stable field ordering per use case; do not use reflection or generic `toString()` serialization.
   - Execution plan:
     - Create canonical deterministic hash builders for:
       - dedupe-judge input
       - review explanation input
       - dashboard briefing input
       - transaction insight source expense
       - receipt-item categorization input
     - Preserve current cache semantics except for expected cold misses from hash migration.
     - If cached payload deserializers are touched in the same files, opportunistically align them to the strict JSON helper from Batch 1.
   - Validation:
     - `:app:compileDebugKotlin`
     - Existing AI use-case tests plus new hash utility tests asserting stability across equivalent inputs.
   - Completion criteria:
     - No scoped D.1 artifact path derives `sourceHash` from `hashCode().toString()`.
     - Equivalent business inputs produce the same hash across runs; changed business inputs produce different hashes.

3. **Batch 3 — Model and write-boundary invariant enforcement**
   - Scope:
     - Add safe invariants to `NotificationParseResult`, `WarrantyExtractionResult`, and `CategorizationAssistInput`.
     - Close `MileageTracking` write-path validation gap without reopening DB schema work.
   - Dependencies:
     - Prefer repository/helper validation for `MileageTracking` unless entity-level requires are proven safe.
   - Execution plan:
     - Add finite/positive/bounded checks to domain-only models where no persistence hydration risk exists.
     - For `MileageTracking`, add a single reusable validation path invoked by `BusinessExpenseRepository.addMileage(...)`.
     - If entity constructor validation is added, back it with explicit tests for Room-style reconstruction safety or clearly documented migration assumptions.
   - Validation:
     - `:app:compileDebugKotlin`
     - New model invariant tests.
     - Repository tests proving invalid mileage rows are rejected before DAO insert.
   - Completion criteria:
     - Non-finite or out-of-range values are rejected at construction/write boundaries.
     - Mileage impossible states cannot be inserted through the repository path.

4. **Batch 4 — CSV and numeric parser strictness**
   - Scope:
     - Fix `CsvExpenseImporter`, `ClipboardAmountParser`, and `AmountUtils`.
   - Dependencies:
     - This batch should land before Batch 5 because email/provider parsing also relies on tightened amount normalization behavior.
   - Execution plan:
     - Replace naive CSV splitting with a quote-aware parser strategy appropriate for the existing importer scope.
     - Change date-parse failure handling from silent “rewrite to now” to explicit line rejection / counted error.
     - Anchor clipboard extraction to whole-token matches so grouped amounts do not partial-tail match.
     - Tighten comma-group validation in `AmountUtils` to require 3-digit post-separator groups when the comma is being interpreted as a grouping separator rather than a decimal separator.
   - Validation:
     - `:app:compileDebugKotlin`
     - `CsvExpenseImporterTest`, `AmountUtilsTest`, and new clipboard parser tests.
   - Completion criteria:
     - Grouped values like `1,234.56` are captured as a whole token, not `234.56`.
     - Malformed formats like `1,0000` are rejected.
     - CSV import no longer corrupts quoted fields or rewrite historical dates to “today.”

5. **Batch 5 — Email/warranty regex and date/currency strictness**
   - Scope:
     - Fix D.7 Apple/Uber currency detection, Uber year derivation, and WarrantyTextExtractor multiline anchor behavior.
   - Dependencies:
     - Reuse Batch 4 amount behavior and the existing `BaseEmailParser` helper path.
     - Coordinate with B.11 execution to avoid diverging helper logic.
   - Execution plan:
     - Replace raw substring currency detection with bounded-token matching or explicit tokenization that cannot misread fragments like `MUSIC`, `ORDER`, or `DETAILS`.
     - Derive yearless Uber dates from `receivedAt` year instead of wall-clock current year.
     - Add `MULTILINE` semantics to the “date at start of line” pattern in `WarrantyTextExtractor` and cover it with regression tests.
   - Validation:
     - `:app:compileDebugKotlin`
     - `AppleReceiptParserTest`, `UberReceiptParserTest`, `WarrantyTextExtractorTest`.
   - Completion criteria:
     - Apple/Uber currency detection no longer fires on incidental region-like substrings.
     - Uber yearless date parsing is deterministic relative to the source email.
     - Warranty extraction finds line-start dates beyond the first line.

6. **Batch 6 — Regression sweep and registry closeout**
   - Scope:
     - Run targeted cross-batch regression suite.
     - Update registry wording only after code and tests prove resolution/partial-resolution status.
   - Dependencies:
     - All prior batches complete.
   - Execution plan:
     - Re-run focused tests for AI parsers, AI use cases, CSV/amount parsing, email parsing, and warranty extraction.
     - Audit for any remaining scoped `hashCode().toString()`, `optLong()` trust, raw `Enum.valueOf()` hot spots, or current-time fallback on parse failure.
     - Update `MASTER-ISSUE-REGISTRY.md` with exact status markers backed by merged code.
   - Validation:
     - Focused unit suite from Batches 1-5.
     - One `grep`-style closeout audit for scoped anti-patterns before registry updates.
   - Completion criteria:
     - Registry language matches merged code.
     - No scoped issue remains open because of missed validation/test coverage.

### Risks
- **Room/entity read safety risk:** adding `require(...)` directly to `MileageTracking` can turn legacy or partially corrupt rows into read-time crashes. Preferred mitigation: repository/write-boundary validation first.
- **Cold-cache behavior risk:** moving from `hashCode()` to deterministic SHA-256 will intentionally invalidate old cache identity keys. This is acceptable, but the rollout should be documented as “safe cache miss,” not regression.
- **Over-strict parser regression risk:** tightening AI JSON parsing may increase `null`/failure paths for malformed provider output. Mitigation: update prompts/tests in the same batch and ensure failure paths are graceful.
- **Scope bleed risk:** D.7 email parser items overlap the B.11 plan. Mitigation: reuse `BaseEmailParser` utilities and keep fixes limited to standalone-medium rows only.
- **UI-test cost risk:** `ReviewScreen` may not have an existing Compose test harness. Mitigation: prefer extracting a tiny safe enum parser helper if unit-level coverage is easier than full-screen testing.
- **Locale/serialization drift risk:** any generic hash builder that relies on `toString()`, default locale, or map iteration order will recreate the same instability under a different name. Mitigation: canonical ordered field lists only.

### Acceptance Criteria
- [ ] All scoped AI parser surfaces reject invalid enums, non-finite confidences, and coerced zero IDs instead of accepting them or throwing.
- [ ] `ReviewScreen` no longer calls `TransferDirection.valueOf(...)` directly on untrusted data.
- [ ] Scoped model/write-boundary invariants reject non-finite, negative, zero, or out-of-range values as required by their documented contracts.
- [ ] No scoped D.1 AI artifact path still uses `hashCode().toString()` for `sourceHash`; stable SHA-256 identity is used instead.
- [ ] `CsvExpenseImporter` is quote-aware and does not rewrite failed dates to `System.currentTimeMillis()`.
- [ ] `ClipboardAmountParser` and `AmountUtils` reject malformed grouped numeric input while preserving valid localized amounts.
- [ ] Apple/Uber email parsing uses strict/bounded currency detection, and Uber yearless dates are derived deterministically from `receivedAt`.
- [ ] `WarrantyTextExtractor` line-start date detection works beyond the first line.
- [ ] Each batch has focused regression tests proving both the original defect and the intended hardened behavior.
- [ ] `MASTER-ISSUE-REGISTRY.md` is updated only after the implementation/test evidence confirms final status.
