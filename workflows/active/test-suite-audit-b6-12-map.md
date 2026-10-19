# B6-12 map (scout 2026-10-19) + orchestrator decision (privacy, T3)

Files are in app/src/test/java/com/yourname/expensetracker/diagnostics/. Counts:
- DDL512RegressionTest: 20
- DurableDiagnosticsA8RegressionTest: 12
- GlobalDurableDiagnosticsGoldenTest: 19
- DurableDiagnosticsAcceptanceTest: 24
- DurableDiagnosticsRegressionTest: 17

Total 92 (the audit's counts are stale). The full scout inventory is in the orchestrator transcript; key points are below.

## Production (app/src/main/java/.../domain/diagnostics/)
- EventMetadataSanitizer:
  - canonicalizeKey (lowercase; drops anything outside a-z0-9);
  - isDangerousKey, evaluated in this order: SAFE_EXACT, then a hash suffix (dangerous unless in SAFE_HASH_KEYS), then BLOCKED_EXACT, then BLOCKED_SUBSTRINGS;
  - sanitizeValue (dangerous key → [REDACTED]; safe-hash key needs `^[a-fA-F0-9]{8,128}$`, else [REDACTED]; Bool/Number pass through; Enum → name; Map/Iterable recurse; unknown object → toString → sanitizeStringValue);
  - sanitizeStringValue: >512 → [REDACTED]; Bearer; JWT; path → [PATH]; IBAN; \d{12,}; then truncate to 256;
  - sanitizeJsonString (null/blank/"{}"/parse failure/all-empty → null; recurses);
  - sanitizeExceptionMessage.
- SafeEventMetadata: put / putHashed (sha256Prefix(16); a non-approved key ending in "hash" → [REDACTED]; null skipped) / putRedacted / merge / empty / toJson.
- Traps to pin as rows: substring false positives ("mapping"→pin, "discard"→card, currencyAccountNumber); exact-safe beats substring (source, reason, status); statusToken is dangerous; accessTokenHash is dangerous (the hash rule comes first); accountIdHash and counterpartyHash are dangerous in the sanitizer but approved in SafePrivacyMetadata (an inconsistency → release follow-up). Untested behaviours to add: 512 truncated vs 513 redacted, a non-String value in a hash slot, Enum, Map recursion.

## Decision (standing approval)
**W1 — sanitizer consolidation.** Edits only the 5 files plus the new `diagnostics/EventMetadataSanitizerTest.kt`.
- The new file has table families, each with exact expected outputs, row-named messages and size guards:
  1. key classification (≈45 rows incl. traps);
  2. hash-key values;
  3. string values / exception messages (incl. 256/512/513 boundaries);
  4. sanitizeJsonString (nested, array-of-arrays, null/empty/{}/parse failure);
  5. unknown-object toString;
  6. SafeEventMetadata builder (put blocked, putHashed exact 16-hex not raw, putHashed rawTextHash → [REDACTED], null skipped, merge, empty).
- Move every sanitizer assertion from the 5 files into these rows. Old conditional checks (DDL-512-07a's `if (result != null)`) become exact assertions.
- KEEP:
  - DDL-512-04 and DDL-512-11 (source pins) and DDL-F876-11;
  - A8 restore_journal_diagnostics_json_strips_internal_paths;
  - Acceptance: the 3 side-effect recorder tests, the 2 merge tests and bank_sync_metadata_hashes_provider_transaction_id;
  - Golden: the 2 correlation-id tests, the 3 DiagnosticEvent default tests and the 3 enum pins.
- DELETE as provably vacuous (list each in the report):
  - Regression's assertTrue(true) tests at :37/:45. The plan's "must keep :38/:46" was a stale anchor;
  - constructor read-back tautologies (restart_required isTerminal, CreateExpenseRequest/TransactionEvent correlationId ×3/×2, model-exists, eventId tests, trace non-null);
  - CapturingWriter emit-and-read-back self-tests (DDL512 ×3, Acceptance ×3);
  - DDL512 journal-file tests that only exercise the test's own file I/O (01×2, 02×2, 03, 10);
  - duplicates (Regression side-effect ×2, the UUID duplicate).
- Remove unused helpers/imports. Delete DurableDiagnosticsRegressionTest if it ends up empty (orchestrator deletes the file).
- Update docs/architecture/SENSITIVE_DIAGNOSTICS_POLICY.md:109 to name EventMetadataSanitizerTest plus the remaining files.

**W2 — call-site sentinel tests (R3).** New files only, plus one extension of BankApiIntegrationTest. Use unique sentinels; assert no sentinel appears in any serialized event field and that the metadata keys stay within an allow-list.
- Email: new data/email/EmailReceiptIngestionServiceDiagnosticsPrivacyTest. Use the primary constructor with a capturing DiagnosticEventWriter. Cover parser failure, coordinator Error and a thrown exception with a sentinel path. The RECEIVED event's messageId and sender must be 16-hex.
- Receipt intake: domain/receipt/lifecycle/ReceiptLifecycleCoordinatorDiagnosticsPrivacyTest. Capture emitIntakeDiagnostic events for INPUT_RECEIVED / VALIDATION_FAILED / VALIDATION_PASSED; reasonCode must come from the fixed set; the email coordinator `reason` must be a fixed code.
- Bank sync: extend BankApiIntegrationTest with a recording OperationRunHandle. providerTransactionId must be 16-hex ≠ raw; no description/merchant/IBAN sentinel; key allow-list.
- Persistence layer: check whether the real Room/SafeSink diagnostic writers sanitize exception and metadataJson, and whether a test covers that. If not, add one test driving the real writer with a sentinel exception message/path.

Validation: com.yourname.expensetracker.diagnostics.* (list FQNs), then the W2 classes. Reviews: reviewer-strict plus privacy-security-guardian.
