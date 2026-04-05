# Batch 25 — AI Assistant & Settings (H16-H18, M7-M8, M13, L6)

## Technical Plan (Advanced)
### Scope
- In:
  - AI cloud call lifecycle safety, retry-chain correctness, secret-handling posture, assistant/settings UX resilience, and indexing/perf hardening for:
    - `H16`, `H17`, `H18`, `M7`, `M8`, `M13`, `L6`
  - Regression tests for assistant/settings and affected cloud provider services.
- Out:
  - No model/provider migration.
  - No redesign of assistant information architecture.
  - No removal of existing AI capabilities beyond documented safety fallback behavior.

### Complexity Assessment
- Estimated files touched: **11–20**
  - `data/ai/provider/CloudReceiptItemCategorizationService.kt`
  - `data/ai/provider/SmartReceiptAssistService.kt`
  - cloud provider services under `data/ai/provider/Cloud*.kt`
  - `ui/screens/assistant/AssistantSheet.kt`
  - `ui/screens/assistant/AssistantViewModel.kt`
  - `ui/screens/aisettings/AiSettingsScreen.kt`
  - `ui/screens/aisettings/AiSettingsViewModel.kt`
  - entity/DAO + migration for recurring-related indexing
  - tests under `ui/screens/assistant/*`, `ui/screens/aisettings/*`
- Risk level: **high**
- Cross-module impact: **yes** (AI provider infra, security/network, UI state)

### Batch Plan
1. Batch name: **H16 — Enforce robust HTTP response lifecycle management for cloud categorization**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt`
   - objective:
     - Guarantee proper response/body closure on all paths and prevent leaks.
   - risks:
     - Refactor can alter retry exit behavior if branches change.
   - validation:
     - Response parsing and failure paths keep resources closed and behavior unchanged.

   **Root Cause Analysis**
   - Historical risk from manual response lifecycle handling; `.use {}` discipline must be enforced uniformly.

   **Implementation Strategy**
   1. Ensure all execute paths parse within `use` block and return structured outcomes.
   2. Keep retry semantics explicit and test-covered.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Medium.

   **Verification Plan**
   - Unit tests for success, retryable HTTP, non-retryable failure, and parse errors.

   **Estimated Effort**
   - **Low-Medium**

2. Batch name: **H17 — Align SmartReceiptAssist retry cascade with documented cross-route fallback policy**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt`
   - objective:
     - Ensure cloud and on-device attempts can chain per policy instead of being blocked by strict route gating.
   - risks:
     - Policy change can affect latency/cost/privacy expectations.
   - validation:
     - Retry matrix test proves expected attempt order by settings and capability availability.

   **Root Cause Analysis**
   - Current guards tie attempts to a single chosen route (`CLOUD` or `ON_DEVICE`), conflicting with multi-step fallback comments.

   **Implementation Strategy**
   1. Define explicit ordered fallback matrix from policy + runtime settings.
   2. Execute attempts conditionally in order with deterministic stop conditions.
   3. Persist attempt metadata faithfully in output.

   **Dependencies**
   - Depends on clarified product policy for privacy/cost preferences.

   **Risk Assessment**
   - High.
   - Mitigation: feature flag and runtime diagnostics during rollout.

   **Verification Plan**
   - Exhaustive test matrix for combinations of cloud/device availability and toggles.

   **Estimated Effort**
   - **High**

3. Batch name: **H18 — Enforce centralized cloud auth + URL/log redaction guarantees**
   - files:
     - cloud providers under `app/src/main/java/com/yourname/expensetracker/data/ai/provider/Cloud*.kt`
     - shared networking helper if needed
   - objective:
     - Ensure secrets are never included in URL query params or unredacted logs.
   - risks:
     - Inconsistent retrofit of all providers can leave gaps.
   - validation:
     - All cloud providers use header auth and safe diagnostic logging.

   **Root Cause Analysis**
   - Historical key-in-query approach and verbose logging patterns are high-risk for leakage.

   **Implementation Strategy**
   1. Standardize request builder for header-based auth.
   2. Remove/ban key-bearing query construction.
   3. Add static checks for `?key=` patterns and secret redaction tests.

   **Dependencies**
   - May depend on network utility extraction.

   **Risk Assessment**
   - High security risk if incomplete.

   **Verification Plan**
   - Grep-based policy check + provider smoke tests.

   **Estimated Effort**
   - **Medium-High**

4. Batch name: **M7 — Add recurring-expense table indexes for active/subscription scheduling paths**
   - files:
     - recurring expense entity/DAO
     - DB migrations
   - objective:
     - Improve recurring expense query performance and scheduler responsiveness.
   - risks:
     - Migration version conflicts.
   - validation:
     - Query plans use new composite indexes.

   **Root Cause Analysis**
   - Frequent filters/sorts on recurring expense fields lack dedicated indexes.

   **Implementation Strategy**
   1. Add composite and merchant indexes per hot queries.
   2. Create safe migration DDL and version update.

   **Dependencies**
   - Migration sequencing with M8.

   **Risk Assessment**
   - Low.

   **Verification Plan**
   - Migration + DAO explain-plan checks.

   **Estimated Effort**
   - **Low**

5. Batch name: **M8 — Harden email receipt source message-id integrity semantics**
   - files:
     - email receipt source entity/DAO/ingestion service
     - DB migration for nullable/partial unique index strategy (if selected)
   - objective:
     - Prevent blank/invalid IDs from breaking uniqueness and source linkage.
   - risks:
     - Schema changes can affect import compatibility.
   - validation:
     - Invalid message IDs are rejected/handled deterministically; no accidental replace conflicts.

   **Root Cause Analysis**
   - Non-null default empty IDs with unique index can cause collisions and silent replacement issues.

   **Implementation Strategy**
   1. Validate message ID non-empty at ingestion boundary.
   2. Optionally migrate to nullable + partial unique index (policy decision).
   3. Add deterministic fallback dedupe key when ID missing.

   **Dependencies**
   - Migration sequencing with M7.

   **Risk Assessment**
   - Medium.

   **Verification Plan**
   - Ingestion tests for blank/null/valid IDs and conflict scenarios.

   **Estimated Effort**
   - **Medium**

6. Batch name: **M13 — Ensure cloud error-body consumption happens once and is reused**
   - files:
     - `CloudReceiptAssistService.kt`
     - `CloudReviewExplanationService.kt`
     - `CloudDedupeJudgeService.kt`
     - any additional cloud providers with similar pattern
   - objective:
     - Preserve diagnostics consistency and avoid consumed-stream issues.
   - risks:
     - Refactoring error handling could accidentally drop correlation metadata.
   - validation:
     - Error payload logged and returned consistently from a single captured body string.

   **Root Cause Analysis**
   - Known pattern risk: response body stream can be consumed once; repeated reads lose content.

   **Implementation Strategy**
   1. Capture body once per response path.
   2. Feed same captured value into logs and `HttpError` payloads.
   3. Add provider-level tests for non-empty error message propagation.

   **Dependencies**
   - Align with H18 logging/auth cleanup.

   **Risk Assessment**
   - Medium.

   **Verification Plan**
   - Unit tests for HTTP error paths across providers.

   **Estimated Effort**
   - **Medium**

7. Batch name: **L6 — Localize remaining Assistant/AI Settings UI literals and prompts**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/assistant/AssistantSheet.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/assistant/AssistantViewModel.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsScreen.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsViewModel.kt`
     - `app/src/main/res/values/strings.xml`
   - objective:
     - Remove inline strings in AI assistant/settings UX and status messaging.
   - risks:
     - Test assertions relying on exact hardcoded text will need updates.
   - validation:
     - No targeted hardcoded English literals remain in these screens/VM user-facing messages.

   **Root Cause Analysis**
   - Multiple user-facing strings remain inline in assistant prompts, error messages, and settings actions.

   **Implementation Strategy**
   1. Extract literals to resources.
   2. Keep VM messages resource-driven where UI-facing.
   3. Update tests to assert resource-backed outcomes.

   **Dependencies**
   - Independent, but best sequenced after H17/H18 message-contract stabilization.

   **Risk Assessment**
   - Low-Medium.

   **Verification Plan**
   - Existing assistant/settings tests + grep audit.

   **Estimated Effort**
   - **Medium**

### Dependencies
- Recommended order: `H18` (security baseline) → `H16/M13` (provider reliability) → `H17` (retry policy behavior) → `M7/M8` (DB perf/integrity) → `L6` (copy hygiene).
- `H17` requires explicit policy confirmation for cross-route fallback precedence.
- `M7` and `M8` can run in parallel if migrations are coordinated in one version sequence.

### Rollback / Safety
- Separate security/auth commits from retry-policy behavior commits.
- Keep retry-policy change (`H17`) behind configuration gate for staged rollout.
- For migration work (`M7/M8`), provide downgrade-safe fallback or explicit rollback script.
- Maintain correlation-ID logging while redacting all sensitive content.

### Acceptance Criteria
- [ ] H16: Cloud categorization response lifecycle is safely closed on all paths.
- [ ] H17: Smart receipt assist executes documented fallback matrix correctly under each settings profile.
- [ ] H18: Cloud providers use secure auth transport and no secret-bearing URL/log output.
- [ ] M7: Recurring-expense indexes added with validated migration and query-plan improvement.
- [ ] M8: Email message-id integrity is enforced; blank/invalid IDs no longer corrupt linkage.
- [ ] M13: Cloud error-body handling reads once and preserves diagnostics.
- [ ] L6: Assistant/AI Settings user-facing literals are localized and test-updated.
