# B6-8 map (scout 2026-10-19) + orchestrator scope decision (privacy, T3)

Files are under app/src/test/java/com/yourname/expensetracker/domain/privacy/. All are pure JUnit4 (no Room/Robolectric).
- RawStoragePolicyAuditTest (12)
- ReceiptOcrEmailStorageHardeningTest (12)
- RawStorageEndToEndTest (18)
- BankPrivacyHardeningTest (12)
- PR5PrivacyContractTest (15)
- RawPersistencePolicyConsolidationTest (20, UNTOUCHED)

Production is under app/src/main/java/.../domain/privacy/:
- RawStorageMode (STORE_RAW, STORE_REDACTED, STORE_METADATA_ONLY, DO_NOT_STORE)
- NotificationPersistencePayload :32-90
- ReceiptPersistencePayload :24-47
- EmailReceiptPersistencePayload :35-97
- BankTransactionPersistencePayload build :31-77 / buildWithHashing :82-99
- RawContentSanitizer.sanitizeBankDescription :100-105
- RawPersistencePolicyResolver :56-85

## Matrix (expected stored payload per source × mode; production line refs in the scout report)

**NOTIFICATION**
- RAW: all fields pass through.
- REDACTED: title, text, bigText, subText are "[REDACTED]"; extras is `{"redacted":true}`; pendingReview title/text are "[REDACTED]".
- METADATA_ONLY / DNS: all raw, extras and pendingReview fields null.
- All modes: dedupeFingerprint and notificationKeyHash pass through.

**RECEIPT_OCR**
- RAW: rawOcrText and parsedItemsJson raw; reviewSnippet = raw.take(200).
- REDACTED: rawOcrText null; snippet "[REDACTED]"; parsedItemsJson = numeric projection when currency is given, null without currency.
- METADATA_ONLY: rawOcrText and items null; snippet "Receipt OCR captured; raw text storage disabled."
- DNS: rawOcrText and items null; snippet "Receipt OCR captured; raw text not stored."

**EMAIL_RECEIPT**
- RAW: all fields pass through.
- REDACTED: subject and sender "[REDACTED]"; body and messageIdStored null; the 3 hashes kept; items = projection or null.
- METADATA_ONLY: raw fields null; the 3 hashes kept.
- DNS: raw fields null; messageIdHash and contentFingerprintHash kept; providerOrderIdHash null.

**BANK** (BANK_STATEMENT and BANK_API share the setting)
- RAW: description, reference, notes raw.
- REDACTED: "[REDACTED]" only for non-null input; null stays null.
- METADATA_ONLY / DNS: all null.
- All modes: hashes pass through.

**sanitizeBankDescription:** same 4-mode table, plus a null-input loop.

## Decision (standing approval): plan-conforming version (≈ −32/+1)
1. New `RawStoragePayloadMatrixTest` covering all 16 cells (4 sources × 4 modes) and the sanitizer sub-table. Rules:
   - Every field gets an exact expected value.
   - A unique sentinel per row and per field (e.g. `SECRET_<SOURCE>_<MODE>_<FIELD>_XYZ`). On non-RAW rows, the sentinel must be absent from `payload.toString()`.
   - Hashes and dedupe fields must pass through.
   - Guards: assertEquals(RawStorageMode.entries.size, rows per source); a row count of 16 (+ the sanitizer rows); unique row ids.
   - Add the uncovered gap rows:
     - notification REDACTED for text, bigText, subText and extras;
     - Receipt/Email REDACTED with null currency (items null, fail-closed);
     - Email DNS vs METADATA_ONLY providerOrderIdHash with a NON-null input;
     - Email REDACTED subject and sender sentinel-free;
     - Bank REDACTED null-in gives null-out.
2. Delete RawStoragePolicyAuditTest entirely. Move the email hash-stability test (:146-155) verbatim into ReceiptOcrEmailStorageHardeningTest. :191 and :219 are vacuous.
3. Shrink:
   - Hardening: drop the matrix duplicates; keep :163 (fingerprint hash), the moved :147 and :232 (debug-export resolver).
   - EndToEnd: drop the matrix duplicates. Keep :244 (bank DNS hash-not-raw), :261 and :274 (buildWithHashing). **Delete :302 as vacuous**: it asserts nothing about sentinels, and its name implies a diagnostics guarantee it does not give. Record that real SafePrivacyMetadata/diagnostics sanitizer coverage belongs to B6-12.
   - BankPrivacyHardening: drop the payload duplicates :26/:58/:77/:97. Keep :41, :113, the 4 token tests and the resolver tests :159/:182.
   - PR5: move the 5 sanitizer tests (:52-82) into the matrix. Keep :104/:145 (BankApiIntegration wiring) and everything else (B6-9 territory).
4. Never weaken an assertion that raw text is not persisted. Do not copy the old weak `contains("PII")` checks; use exact values plus whole-payload sentinel absence.
5. Flag for the reviewer, but do not fix: notification extras REDACTED is `{"redacted":true}` in the payload, while RawContentSanitizer.sanitizeNotificationExtras returns null for REDACTED. That is a production inconsistency → release follow-up.
6. Docs: the plan cite raw-storage-policy.md:135 is wrong (that line is the PrivacyGate carve-out). The relevant sections are :147-154 and :156+. Mention the new matrix test there in the final docs pass. PRIVACY_UI_ARCHITECTURE.md:105 describes PR5 wrongly; fix it in the final docs pass.

Reviews: reviewer-strict plus privacy-security-guardian. Validation: targeted-unit-test, all 6 FQNs (+ the new matrix test).
