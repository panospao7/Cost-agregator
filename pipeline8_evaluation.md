# Pipeline 8 Unified Evaluation — Privacy / AI / Redaction

**Date:** 2026-05-11
**HEAD:** `d915b10c` (post pipeline 7)
**Combines:** Debug report + pre-existing evaluation + fresh code verification

## Verdict: Substantially improved — mostly stable at HEAD

Many issues flagged in the debug report and pre-existing evaluation (from HEAD `c424274`) have been fixed between then and now:

### Confirmed FIXED at HEAD

| ID | Issue | Status |
|----|-------|--------|
| P8-P1-01 | Privacy change cancels workers | ✅ FIXED — `applyPrivacyChange()` cancels 6+ workers |
| P8-P1-04 | Audit context stores sensitive data | ✅ FIXED — allowlisted keys, drops long values |
| P8-P1-05 | emailReceiptStorageMode | ✅ FIXED — in PrivacySettings, persisted, read in coordinator |
| P8-P1-09 | Notification observer fail-open | ✅ FIXED — `capturePrivacyDenied = true` on observer error (line 291) |

### Remaining gaps (architectural — lower priority)

| ID | Issue | Status |
|----|-------|--------|
| P8-P1-02 | PrivacySettings vs AiSettings drift | ⚠ OPEN — architectural, needs unified policy resolver |
| P8-P1-06 | Retention scope incomplete | ⚠ OPEN — AI artifacts, chat, diagnostics, email not purged |
| P8-P1-12 | Denied privacy states inconsistent | ⚠ OPEN — providers return null/disabled/failure inconsistently |

## Bottom line

Pipeline 8 has a solid privacy framework with capability gates, fail-closed composition, sanitized audit context, runtime worker cancellation, and email storage mode propagation. The remaining gaps are architectural improvements rather than critical bugs — no P0 issues remain.
