# Pipeline 8 — Privacy/AI/Redaction: Consolidated Issue Registry

> **Last validated:** 2026-05-31 against local HEAD code  
> **Status:** 1 FIXED, 1 PARTIAL, 10 TODO, 8 NEW open issues  
> **Total open items:** 19

---

## Old Issues (from master tracker) — Validated Status

| ID | Sev | Title | Tracker Said | **Actual Status** | Notes |
|----|-----|-------|-------------|-------------------|-------|
| P8-P1-01 | P1 | Privacy setting changes don't stop active workers | ✅ FIXED | ✅ **FIXED** | `updateSettings()` now cancels `ai_daily_briefing`, `location_backfill`, `data_retention` workers |
| P8-P1-02 | P1 | `PrivacySettings` and `AiSettings` can disagree | 📝 TODO ONLY | 📝 **TODO ONLY** | Split cloud privacy; providers check non-uniformly |
| P8-P1-03 | P1 | Audit logging noisy and not semantically precise | 📝 TODO ONLY | 📝 **TODO ONLY** | Gates log `Allowed` for unrelated capabilities; final decision unclear |
| P8-P1-04 | P1 | Audit context stores caller-provided sensitive data | 📝 TODO ONLY | 📝 **TODO ONLY** | Arbitrary `context: Map<String, String>` serialized to JSON |
| P8-P1-05 | P1 | Raw notification/OCR/email data stored first, purged later | ⚠ PARTIAL | ⚠ **PARTIAL** | `RawStorageMode` enum controls write-time sanitization; gaps remain in some paths |
| P8-P1-06 | P1 | Retention worker scope incomplete | 📝 TODO ONLY | 📝 **TODO ONLY** | Only purges raw notification + OCR; misses AI artifacts, chats, email bodies |
| P8-P1-07 | P1 | Bank-statement cloud text path sends raw prompt | 📝 TODO ONLY | 📝 **TODO ONLY** | `suggestFromText(prompt)` no `CloudPayloadRedactor` applied |
| P8-P1-08 | P1 | Redaction not a formal purpose-aware payload contract | 📝 TODO ONLY | 📝 **TODO ONLY** | No standardized `PreparedCloudPayload` |
| P8-P1-09 | P1 | Notification privacy gate too late; runtime state not cached | 📝 TODO ONLY | 📝 **TODO ONLY** | Text extracted before gate; setting changes do not stop service |
| P8-P1-10 | P1 | Geocoding/location gate coverage not statically guaranteed | 📝 TODO ONLY | 📝 **TODO ONLY** | Multiple external geocoding providers; not all gate-checked |
| P8-P1-11 | P1 | Raw backup/export remains reachable | 📝 TODO ONLY | 📝 **TODO ONLY** | `exportDatabase()` deprecated but exists in production |
| P8-P1-12 | P1 | Denied privacy states not consistently visible | 📝 TODO ONLY | 📝 **TODO ONLY** | Providers return null/failure; no unified privacy-denied UX model |

---

## New Issues (from deep audit 2026-05-31)

| ID | Sev | Title | File | Status |
|----|-----|-------|------|--------|
| NEW-P8-001 | P1 | `updateSettings()` TOCTOU race | PrivacySettingsRepository.kt | 🔴 OPEN |
| NEW-P8-002 | P1 | DataRetentionWorker loop no checkpoint for 5 targets | DataRetentionWorker.kt | 🔴 OPEN |
| NEW-P8-003 | P2 | `MERCHANT_LINE_REGEX` over-matches | CloudPiiSanitizer.kt | 🔴 OPEN |
| NEW-P8-004 | P2 | CloudPiiSanitizer missing patterns | CloudPiiSanitizer.kt | 🔴 OPEN |
| NEW-P8-005 | P2 | `requireAllowed()` ignores capability | PrivacyGate.kt | 🔴 OPEN |
| NEW-P8-006 | P2 | DataRetentionWorker swallows purge failures | DataRetentionWorker.kt | 🔴 OPEN |
| NEW-P8-007 | P2 | `sanitizeRawOcr` conflates null with empty | RawContentSanitizer.kt | 🔴 OPEN |
| NEW-P8-008 | P3 | `detectRedactedFields` misses truncation | RedactionDetector.kt | 🔴 OPEN |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 1 |
| ⚠ PARTIAL (old issues) | 1 |
| 📝 TODO ONLY (old issues) | 10 |
| 🔴 OPEN (new issues) | 8 |
| **Total open work** | **19** |

---

## Priority Order for Remaining Work

### P1 (must fix)
1. **NEW-P8-001** — `updateSettings()` TOCTOU race (concurrent settings writes can corrupt state)
2. **NEW-P8-002** — DataRetentionWorker loop no checkpoint for 5 targets (crash restarts all purges)
3. **P8-P1-02** — `PrivacySettings` and `AiSettings` can disagree
4. **P8-P1-03** — Audit logging noisy and not semantically precise
5. **P8-P1-04** — Audit context stores caller-provided sensitive data
6. **P8-P1-05** — Raw data stored first, purged later (remaining gaps)
7. **P8-P1-06** — Retention worker scope incomplete
8. **P8-P1-07** — Bank-statement cloud text path sends raw prompt
9. **P8-P1-08** — Redaction not a formal purpose-aware payload contract
10. **P8-P1-09** — Notification privacy gate too late
11. **P8-P1-10** — Geocoding/location gate coverage not statically guaranteed
12. **P8-P1-11** — Raw backup/export remains reachable
13. **P8-P1-12** — Denied privacy states not consistently visible

### P2 (should fix)
14. **NEW-P8-003** — `MERCHANT_LINE_REGEX` over-matches
15. **NEW-P8-004** — CloudPiiSanitizer missing patterns
16. **NEW-P8-005** — `requireAllowed()` ignores capability
17. **NEW-P8-006** — DataRetentionWorker swallows purge failures
18. **NEW-P8-007** — `sanitizeRawOcr` conflates null with empty

### P3 (cleanup)
19. **NEW-P8-008** — `detectRedactedFields` misses truncation
