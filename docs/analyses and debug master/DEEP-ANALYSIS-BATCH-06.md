# Deep Analysis — Batch 06: AI Models, Policies, Router (@reviewer)

## @reviewer Findings

### Issues Found
| # | File | Severity | Type | Description | Suggested Fix |
|---|------|----------|------|-------------|---------------|
| 1 | `domain/ai/model/AiModels.kt` | MAJOR | Policy / Defaults | `AiSettings` now defaults `aiEnabled = true`, which conflicts with the approved conservative/opt-in AI policy in the architecture plan. Any code path that uses `AiSettings()` as initial state can briefly treat AI as enabled before persisted settings load. | Restore opt-in defaults, or explicitly update the approved plan and all consumers/tests. Prefer one canonical defaults source instead of hardcoding constructor literals. |
| 2 | `domain/ai/policy/DefaultAiCapabilityRouter.kt` | MINOR | UX / Messaging | The disabled-path reason uses raw enum names (`"$capability is disabled in settings."`), so user-facing runtime messages can show internal identifiers like `REVIEW_EXPLANATION`. | Use the existing human-readable `displayName()` mapping for disabled reasons too. |
| 3 | `domain/ai/model/AiArtifactPresentation.kt` | MAJOR | Diagnostics / Observability | `toDiagnosticsOrNull()` drops all diagnostics for `AiMode.AUTO`, but fallback/disabled artifact writes are persisted as `AiMode.AUTO`. Persisted fallback artifacts therefore lose route provenance in UI even though runtime screens expose it. | Persist actual `AiRoute`, or at minimum render `AiMode.AUTO` fallback artifacts as a visible label such as `Deterministic fallback` instead of returning `null`. |
| 4 | `domain/ai/model/AiRuntimeStatusModels.kt` | MAJOR | Model Design | `AiCapabilityRuntimeStatus` requires a non-null `OnDeviceModelStatus` for every capability, so callers cannot represent "not applicable". That pushes misleading on-device state into runtime summaries for capabilities without local support. | Make on-device status nullable or add an explicit `NOT_APPLICABLE` state, and only populate local-runtime fields when the capability actually supports on-device execution. |

### Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | `domain/ai/model/AiModels.kt` + `data/repository/AiSettingsRepositoryImpl.kt` | MAJOR | Defaults are duplicated and inconsistent: `AiSettings()` enables `receiptAssistEnabled` and `receiptImageCloudEnabled`, while DataStore deserialization defaults both to `false`. Initial UI/routing state can therefore flip after the repository emits. | Define defaults once and reuse them for both constructor and repository deserialization. |
| 2 | `domain/ai/model/AiRuntimeStatusModels.kt` + `domain/ai/usecase/GetAiRuntimeStatusUseCase.kt` + `domain/ai/policy/DefaultAiCapabilityRouter.kt` | MAJOR | Runtime status/action generation is misleading for disabled or non-applicable capabilities: the use case always fetches on-device status and can surface actions like "Install required" even when the real problem is "feature disabled in settings" or "no on-device implementation exists". | Derive action labels from route/context first, and skip on-device probing for capabilities that do not support local execution. |
| 3 | `domain/ai/service/AiSettingsRepository.kt` + `data/repository/AiSettingsRepositoryImpl.kt` | MAJOR | The persistence boundary does not recover from DataStore read failures. `settings()` maps `dataStore.data` directly without the usual `IOException` recovery, so a corrupted prefs file can break all settings/routing consumers. | Add `catch`/`emptyPreferences()` recovery for `IOException` and keep non-IO failures fatal. |
| 4 | `domain/ai/model/AiArtifactPresentation.kt` + artifact-producing AI use cases | MAJOR | Use cases persist fallback/disabled executions as `AiMode.AUTO`, but presentation treats `AUTO` as "no diagnostics". Cached artifacts therefore lose the same routing context that live runtime status shows. | Store route metadata explicitly on artifacts and render it consistently in both live and persisted paths. |

### Summary
- Total issues: 8
- Files with issues: 4/11 reviewed target files (plus supporting runtime/persistence files outside the batch)
- Requirements met: no — 4 requested batch paths do not exist in the repo (`AiArtifactModels.kt`, `AiCapabilityModels.kt`, `AiRuntimeModels.kt`, `AiSettingsModels.kt`), and `AiSettingsRepository.kt` lives under `domain/ai/service`, not `domain/ai/policy`. Also, there were no uncommitted diffs in these target files, so this is a review of current implementation rather than pending patch content.
- Testing adequate: no — there are happy-path tests for router/runtime text, but no coverage for default consistency between model and DataStore, DataStore read failures, disabled-route action labeling, non-applicable on-device statuses, or persisted fallback artifact diagnostics.
