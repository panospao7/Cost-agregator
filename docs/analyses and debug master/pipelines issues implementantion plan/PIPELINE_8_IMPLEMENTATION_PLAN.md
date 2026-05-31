# Pipeline 8 — Privacy / AI / Redaction: Post-Universal Implementation Plan

> **Generated:** 2026-05-31  
> **Pipeline:** Pipeline 8 — Privacy / AI / Redaction  
> **Universal fixes baseline:** U-PR1 ✅, U-PR2 ✅, U-PR3 ✅, U-PR4 ✅, U-PR5 ⏳, U-PR6 ✅, U-PR7 ✅, U-PR8 ✅  
> **Scope:** Pipeline-local remaining work after universal fixes

---

## 1. Executive Verdict

```
Pipeline: 8 — Privacy / AI / Redaction
Verdict: RED
Summary:
- 1 old issue FIXED, 1 PARTIAL, 10 TODO ONLY
- 0 issues directly fixed by universal (U-PR5 not yet landed)
- 19 pipeline-local issues remain (13 P1, 5 P2, 1 P3)
- Most P1 issues are fundamental privacy design gaps (TODO ONLY)
- Key new bugs: settings TOCTOU race, retention worker no checkpoint
- Heavily blocked by U-PR5 (RawStorageMode/Privacy contract)
- This pipeline has the most remaining design work of all pipelines
```

---

## 2. Sources Reviewed

**Docs:** `UNIVERSAL_ISSUE_TRACKER.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `PIPELINE_8_CONSOLIDATED_ISSUES.md`

**Source files:** `PrivacySettingsRepository.kt`, `DataRetentionWorker.kt`, `CloudPiiSanitizer.kt`, `PrivacyGate.kt`, `RawContentSanitizer.kt`

---

## 3. Universal Fix Impact Summary

| Universal ID | Impact on Pipeline 8 | Adapter Needed | Status |
|---|---|---|---|
| U-PR1 (CancellationException) | No direct impact (P8 not in affected list) | No | N/A |
| U-PR5 (Privacy/RawStorageMode) | **Critical** — defines authoritative privacy contract for P8 | Yes — full adapter | ⏳ Blocked |
| Others | No direct impact | No | N/A |

---

## 4. Consolidated Issue Reconciliation

| Pipeline Issue ID | Current Status | Universal Relation | Remaining Work |
|---|---|---|---|
| P8-P1-01 | ✅ FIXED | None | None |
| P8-P1-02 | 📝 TODO | U-PR5 (settings unification) | Unify PrivacySettings + AiSettings |
| P8-P1-03 | 📝 TODO | None | Semantic audit logging |
| P8-P1-04 | 📝 TODO | None | Sanitize audit context |
| P8-P1-05 | ⚠ PARTIAL | U-PR5 | Complete write-time sanitization |
| P8-P1-06 | 📝 TODO | None | Expand retention targets |
| P8-P1-07 | 📝 TODO | U-PR5 | Wire CloudPayloadRedactor |
| P8-P1-08 | 📝 TODO | U-PR5 | PreparedCloudPayload contract |
| P8-P1-09 | 📝 TODO | None | Already fixed in P1 (captureGate) |
| P8-P1-10 | 📝 TODO | None | Static gate coverage |
| P8-P1-11 | 📝 TODO | U-PR5 | Remove/gate raw export |
| P8-P1-12 | 📝 TODO | None | Unified denied UX |
| NEW-P8-001 | 🔴 OPEN | None | Atomic settings update |
| NEW-P8-002 | 🔴 OPEN | None | Add checkpoint per target |
| NEW-P8-003 through NEW-P8-008 | 🔴 OPEN | None | Various fixes |

---

## 5. New Issues / Regressions

No regressions from universal fixes. P8-P1-09 (notification gate too late) is effectively fixed by Pipeline 1's `captureGate` implementation — should be reclassified.

---

## 6. Open Issue Master List

| ID | Severity | Title | Area | Suggested PR |
|---|---|---|---|---|
| NEW-P8-001 | P1 | updateSettings() TOCTOU race | Settings | P8-PR1 |
| NEW-P8-002 | P1 | DataRetentionWorker no checkpoint | Retention | P8-PR1 |
| NEW-P8-005 | P2 | requireAllowed() ignores capability | Gate | P8-PR1 |
| NEW-P8-006 | P2 | DataRetentionWorker swallows purge failures | Retention | P8-PR1 |
| P8-P1-03 | P1 | Audit logging noisy | Audit | P8-PR2 |
| P8-P1-04 | P1 | Audit context stores sensitive data | Audit | P8-PR2 |
| P8-P1-12 | P1 | Denied states not visible | UX | P8-PR2 |
| NEW-P8-003 | P2 | MERCHANT_LINE_REGEX over-matches | Sanitizer | P8-PR3 |
| NEW-P8-004 | P2 | CloudPiiSanitizer missing patterns | Sanitizer | P8-PR3 |
| NEW-P8-007 | P2 | sanitizeRawOcr conflates null/empty | Sanitizer | P8-PR3 |
| NEW-P8-008 | P3 | detectRedactedFields misses truncation | Detection | P8-PR3 |
| P8-P1-02 | P1 | Settings disagreement | Design | Blocked by U-PR5 |
| P8-P1-05 | P1 | Raw stored first, purged later | Design | Blocked by U-PR5 |
| P8-P1-06 | P1 | Retention scope incomplete | Design | Blocked by U-PR5 |
| P8-P1-07 | P1 | Bank cloud sends raw | Design | Blocked by U-PR5 |
| P8-P1-08 | P1 | No PreparedCloudPayload | Design | Blocked by U-PR5 |
| P8-P1-10 | P1 | Geocoding gate gaps | Design | Blocked by U-PR5 |
| P8-P1-11 | P1 | Raw export reachable | Design | Blocked by U-PR5 |

---

## 7. PR Organization

### P8-PR1 — Critical Bugs (Settings Race, Retention Checkpoint, Gate)

```
PR name: fix(p8): atomic settings update, retention checkpoint, gate capability check
Goal: Fix P1 bugs that can be addressed without U-PR5
Issues fixed: NEW-P8-001, NEW-P8-002, NEW-P8-005, NEW-P8-006
Universal dependencies: None
Files likely touched:
  - PrivacySettingsRepository.kt
  - DataRetentionWorker.kt
  - PrivacyGate.kt
Implementation steps:
  1. NEW-P8-001: Use Mutex or synchronized block around settings read-modify-write; or use atomic compare-and-swap pattern
  2. NEW-P8-002: Add per-target checkpoint (SharedPreferences key per purge target); on restart, resume from last incomplete target
  3. NEW-P8-005: In requireAllowed(), check the specific capability parameter (not just global enabled)
  4. NEW-P8-006: On purge failure, log error, mark target as failed, continue to next target; report partial success
Tests:
  - concurrent_settings_updates_dont_corrupt
  - retention_worker_resumes_from_checkpoint_after_crash
  - gate_checks_specific_capability
  - purge_failure_doesnt_block_other_targets
Risks: Medium — settings race fix needs careful concurrency testing
Acceptance criteria:
  - No settings corruption under concurrent writes
  - Retention worker crash-safe with per-target progress
  - Gate respects individual capabilities
  - Partial purge failures reported (not swallowed)
```

### P8-PR2 — Audit & UX Improvements

```
PR name: fix(p8): semantic audit logging, sanitize audit context, denied UX
Goal: Fix audit quality and privacy-denied visibility
Issues fixed: P8-P1-03, P8-P1-04, P8-P1-12
Universal dependencies: None
Files likely touched:
  - PrivacyAuditLogger.kt
  - PrivacyGate.kt
  - UI privacy-denied composables
Implementation steps:
  1. P8-P1-03: Only log final decision (Allowed/Denied) per request; remove intermediate capability logs; add structured decision reason
  2. P8-P1-04: Sanitize audit context map — hash or redact values that could contain PII; use SafeEventMetadata pattern
  3. P8-P1-12: Create unified PrivacyDeniedState sealed class; UI shows consistent "feature disabled by privacy settings" with link to settings
Tests:
  - audit_logs_only_final_decision
  - audit_context_contains_no_raw_pii
  - denied_state_shows_consistent_ui
Risks: Low — additive improvements
Acceptance criteria:
  - Audit log volume reduced; only meaningful decisions logged
  - No PII in audit storage
  - User sees clear explanation when feature is privacy-denied
```

### P8-PR3 — Sanitizer Improvements

```
PR name: fix(p8): regex precision, missing PII patterns, null vs empty, truncation detection
Goal: Improve PII sanitization quality
Issues fixed: NEW-P8-003, NEW-P8-004, NEW-P8-007, NEW-P8-008
Universal dependencies: None
Files likely touched:
  - CloudPiiSanitizer.kt
  - RawContentSanitizer.kt
  - RedactionDetector.kt
Implementation steps:
  1. NEW-P8-003: Narrow MERCHANT_LINE_REGEX to require merchant-context keywords (not just any line with a name-like pattern)
  2. NEW-P8-004: Add patterns for: IBAN, phone numbers, email addresses, Greek tax IDs (AFM), card numbers
  3. NEW-P8-007: Distinguish null (no data available) from empty string (data explicitly cleared); use sealed type or nullable with explicit CLEARED sentinel
  4. NEW-P8-008: In detectRedactedFields, check for truncation markers (e.g. "..." at end, length == max_length)
Tests:
  - merchant_regex_doesnt_match_normal_text
  - sanitizer_catches_iban_phone_email
  - null_ocr_distinct_from_empty_ocr
  - truncated_fields_detected_as_redacted
Risks: Low — sanitizer improvements
Acceptance criteria:
  - No false-positive merchant redaction on normal text
  - All common PII patterns caught
  - Null vs empty semantics preserved through pipeline
```

---

## 8. Detailed Implementation Plan

### P8-PR1 Step-by-Step

1. **Open** `PrivacySettingsRepository.kt` — find `updateSettings()`; add Mutex around read-current → modify → write-new sequence
2. **Open** `DataRetentionWorker.kt` — find purge loop; add `SharedPreferences.edit().putString("retention_checkpoint", targetName).apply()` after each target completes; on start, read checkpoint and skip completed targets
3. **Open** `PrivacyGate.kt` — find `requireAllowed()`; ensure it checks `capability` parameter against settings (not just `isEnabled`)

### P8-PR2 Step-by-Step

1. **Open** audit logger — reduce to single log per gate decision; remove per-capability intermediate logs
2. **Find** audit context serialization — apply `SafeEventMetadata.builder()` pattern (hash sensitive values)
3. **Create** `PrivacyDeniedState` sealed class; wire to UI composables that show denied features

---

## 9. Pipeline-Local Follow-up After Universal Work

| Universal PR | Pipeline 8 Adapter/Follow-up |
|---|---|
| U-PR5 (Privacy/RawStorageMode) | **Critical adapter required:** Wire EffectiveCloudAiPolicy as authoritative gate; implement PreparedCloudPayload contract; expand retention targets; unify PrivacySettings/AiSettings; gate raw export; apply per-source-type storage modes. This is the largest adapter of any pipeline. |

---

## 10. Validation Commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# Pipeline 8 targeted tests
./gradlew :app:testDebugUnitTest --tests "*Privacy*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Retention*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Sanitizer*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Redaction*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*CloudPii*" --stacktrace

./gradlew :app:check --stacktrace
```

---

## 11. Final Definition of Done

- [ ] P8-PR1: Settings race-free; retention checkpointed; gate checks capability
- [ ] P8-PR2: Audit semantic; no PII in audit; denied UX consistent
- [ ] P8-PR3: Regex precise; PII patterns complete; null/empty distinct; truncation detected
- [ ] U-PR5 adapter landed: Full privacy contract implemented (7 blocked issues closed)
- [ ] All existing tests pass
- [ ] Build succeeds
- [ ] Pipeline 8 status upgraded to GREEN in master tracker
