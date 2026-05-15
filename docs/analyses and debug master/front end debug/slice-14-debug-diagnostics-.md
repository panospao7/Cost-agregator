Here’s the Slice 14 report for the debug/diagnostics layer.

<slice-14-debug-diagnostics-report.md>
# Slice 14 Debug Report — Debug / Diagnostics Layer

Target repo/commit: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Primary sources:
- `ui/screens/debug/DebugScreen.kt`
- `ui/screens/debug/DebugViewModel.kt`
- `ui/screens/debug/DebugDataStorage.kt`
- `ui/screens/debug/CategorizationDebugScreen.kt`
- `ui/screens/debug/CategorizationDebugViewModel.kt`
- `domain/debug/DebugData.kt`
- `domain/debug/DebugIssue.kt`
- `domain/debug/AiRuntimeDiagnostics.kt`
- `domain/debug/ServiceDiagnostics.kt`

Tests found:
- `app/src/test/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModelStressTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/debug/ServiceDiagnosticsTest.kt`

## Executive summary

The debug layer is not just “dev-only UI”. It is a high-risk operational console that can:
- inspect raw notifications,
- show AI runtime/provider/model routing,
- export categorization traces,
- reset expenses/budgets/source stats,
- import/export/migrate databases,
- simulate notifications and mass data.

Main issues:
1. `DebugScreen` is a monolithic kitchen-sink screen.
2. Release protection exists in the screen, but route-level hiding should still be enforced.
3. `DebugDataStorage` persists highly sensitive raw parser data to disk with no retention/encryption policy.
4. `DebugData` export includes raw text, merchant names, amounts, currency, and logs.
5. `DebugDataStorage` falls back to `"EUR"` for missing currency.
6. AI runtime diagnostics expose provider/model/reason strings directly.
7. Service diagnostics are snapshot-based and not reactive.
8. Destructive actions are inconsistent: some have undo, some don’t, and none are transactional across stores.
9. Several async debug operations lack robust `try/finally` / error-state handling.
10. Categorization debug export copies raw merchant trace data to clipboard without a privacy guard.
11. Test coverage is thin and mostly stress/delegation-focused.

---

## Recommended first commands

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Debug*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ServiceDiagnostics*" --stacktrace
```

---

## Issues and fix plan

### S14-001 — DebugScreen is a monolith
**Where:** `DebugScreen.kt`

It mixes:
- notification inspector,
- service diagnostics,
- AI runtime diagnostics,
- destructive reset tools,
- test notification simulation,
- categorization debug launcher,
- database maintenance.

**Fix strategy**
Split into:
- `DebugRoute`
- `DebugScreenContent`
- `NotificationInspectorSection`
- `ServiceDiagnosticsCard`
- `AiRuntimeDiagnosticsCard`
- `DebugDestructiveActionsSection`
- `DebugSimulationSection`
- `CategorizationDebugLauncher`

---

### S14-002 — Route gating should happen before the screen
**Where:** navigation entry to debug

The screen itself checks `BuildConfig.DEBUG`, which is good, but the route should also be hidden/gated so release builds cannot navigate there at all.

**Fix strategy**
- Gate debug route registration in navigation.
- Keep the in-screen release fallback as defense in depth.

---

### S14-003 — Raw debug parser data is persisted to disk
**Where:** `DebugDataStorage.kt`, `DebugData.kt`

`DebugDataStorage` writes `last_debug_data.json` in app files. The JSON includes:
- raw text preview,
- parsed merchant names,
- amounts,
- currency,
- confidence,
- issues,
- parsing logs.

This is sensitive even in app-private storage.

**Fix strategy**
- Make debug storage explicit/debug-only.
- Add retention/TTL and clear-on-export policy.
- Consider optional encryption if it must persist.
- Default to redacted summaries, not raw payloads.

---

### S14-004 — DebugData uses unsafe fallback currency
**Where:** `DebugDataStorage.kt`

When imported data lacks currency, it falls back to `"EUR"`.

**Fix strategy**
- Preserve `null` or use an explicit “unknown currency” state.
- Never invent EUR for debug payloads.
- Add a test for missing currency import.

---

### S14-005 — AI runtime diagnostics expose internal provider/model details
**Where:** `AiRuntimeDiagnostics.kt`, `DebugScreen.kt`

Route decisions and runtime refresh messages include provider/model names and reasons.

**Fix strategy**
- Split diagnostics into:
  - user-safe summary,
  - verbose/debug-only details.
- Keep provider/model/reason strings out of normal UI.
- Allow only debug build or explicit diagnostics mode to show verbose details.

---

### S14-006 — Service diagnostics card is snapshot-based and stale
**Where:** `DebugScreen.kt`, `ServiceDiagnostics.kt`

`diagnosticsStats` is initialized with `remember { viewModel.getServiceDiagnostics() }`, so it only changes on manual refresh.

**Fix strategy**
- Expose a `StateFlow` for service stats or
- refresh automatically on relevant events.
- Keep manual refresh, but don’t rely on stale snapshot state.

---

### S14-007 — Destructive reset flows are inconsistent and not transactional
**Where:** `DebugViewModel.kt`

Examples:
- `clearAll()` directly deletes notifications.
- `clearAllWithUndoSupport()` snapshots notifications and expenses separately.
- `undoClearAll()` restore is explicitly not a single transaction.
- `resetBudgetsWithUndoSupport()` and `resetSourceStatsWithUndoSupport()` are separate patterns.

**Fix strategy**
- Centralize destructive operations in one coordinator.
- Define atomicity policy per action.
- Use typed mutation state and success/failure events.
- Keep undo consistent or remove partial-undo flows.

---

### S14-008 — Async debug actions lack robust failure/reset handling
**Where:** `DebugViewModel.kt`

Potentially affected:
- `refreshAiRuntimeStatuses()`
- `simulateMassData()`
- `exportDatabase()`
- `importDatabase()`
- `migrateLegacyDatabase()`
- `resetDatabase()`

Some of these do not appear to use `try/finally` or a typed loading/error state. If they throw, flags like `_isSimulating` may remain stuck.

**Fix strategy**
- Add per-operation state:
  - loading,
  - error,
  - success,
  - in-flight guard.
- Use `try/finally` for all debug mutations.

---

### S14-009 — Categorization debug export leaks raw merchant trace data
**Where:** `CategorizationDebugViewModel.kt`, `CategorizationDebugScreen.kt`

`exportTraceToJson()` includes:
- input merchant,
- normalized merchant,
- canonical merchant,
- stripped parts,
- layer details,
- final decision.

The UI copies this JSON to clipboard with one tap.

**Fix strategy**
- Add explicit “copy raw trace” confirmation.
- Offer redacted export by default.
- Separate debug-only raw export from share-safe export.
- Add tests for no raw export in non-debug or redacted mode.

---

### S14-010 — DebugDataStorage and DebugIssueDetector are layering smells
**Where:** `ui/screens/debug/DebugIssueDetector.kt`

The UI package contains deprecated typealiases to domain/debug types.

**Fix strategy**
- Remove the UI alias layer once callers are migrated.
- Keep debug logic in domain/debug, UI only in UI package.

---

### S14-011 — Test coverage is too narrow
**Where:** `DebugViewModelStressTest.kt`, `ServiceDiagnosticsTest.kt`

Current tests mostly cover:
- service diagnostics delegation,
- package filter state,
- simulated notifications,
- some destructive repository delegation,
- AI runtime status exposure.

Missing:
- privacy of debug data storage,
- route gating,
- runtime diagnostics redaction,
- destructive action atomicity,
- export/import/migration failure handling,
- categorization trace export behavior,
- stale snapshot behavior.

---

## Implementation plan

### Phase 1 — Split UI
- Extract `DebugRoute` and section composables.
- Keep state collection out of the root screen.

### Phase 2 — Harden data handling
- Remove raw EUR fallback in debug storage.
- Add debug-data TTL/clear policy.
- Redact raw text by default.

### Phase 3 — Harden diagnostics
- Separate user-safe vs verbose AI runtime diagnostics.
- Make service stats reactive or clearly manual-refresh only.

### Phase 4 — Harden destructive ops
- Add typed mutation state.
- Centralize delete/reset/import/export/migration operations.
- Use `try/finally` and explicit failure states.

### Phase 5 — Harden categorization debug
- Add redacted export and confirmation.
- Keep raw trace clipboard export debug-only.

### Phase 6 — Tests
Add:
- `DebugScreenContentTest`
- `DebugViewModelTest`
- `DebugDataStorageTest`
- `AiRuntimeDiagnosticsTest`
- `CategorizationDebugViewModelTest`
- `DebugRouteGatingTest`

---

## Acceptance checklist

- [ ] Debug route hidden in release navigation.
- [ ] Debug screen split into sections.
- [ ] Raw parser/debug data not persisted unredacted by default.
- [ ] No fallback `"EUR"` in debug storage/import.
- [ ] AI runtime diagnostics have user-safe and verbose modes.
- [ ] Service diagnostics are not stale.
- [ ] Destructive actions are typed, guarded, and recoverable.
- [ ] Async debug ops cannot leave loading flags stuck.
- [ ] Categorization trace export is explicit and privacy-aware.
- [ ] Debug tests cover privacy, gating, mutation, and failure cases.

---

## Useful starting files for the agent

- `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugDataStorage.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/CategorizationDebugScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/CategorizationDebugViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/debug/DebugData.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/debug/AiRuntimeDiagnostics