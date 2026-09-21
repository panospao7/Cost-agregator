# RP-15 Truth Table — RawPersistencePolicy resolution (15-A)

Derived from source truth of `RawPersistencePolicyResolver`, `RawPersistencePolicy`,
`PrivacySettings`, `PrivacySettingsRepositoryImpl`, `RawContentSanitizer`, and the three
call sites. This table is the spec the 15-A implementation and its tests are built from.

## 1. Policy derivation rules (unchanged semantics)

For settings `s` and source `t`:

- `mode(t, s)`:
  - NOTIFICATION → `s.rawNotificationStorageMode`
  - RECEIPT_OCR → `s.rawOcrStorageMode`
  - EMAIL_RECEIPT → `s.emailReceiptStorageMode`
  - BANK_STATEMENT → `s.rawBankStatementStorageMode`
  - BANK_API → `s.rawBankStatementStorageMode`
  - AI_ARTIFACT → `STORE_REDACTED` if `s.debugDataPersistenceEnabled` else `DO_NOT_STORE`
  - EXPORT_DEBUG → `STORE_RAW` if `s.debugDataPersistenceEnabled` else `DO_NOT_STORE`
- `allowRawBody` = mode == STORE_RAW
- `allowRedactedBody` = mode ∈ {STORE_RAW, STORE_REDACTED}
- `allowParsedAmountDateCurrency` = mode != DO_NOT_STORE OR t == EMAIL_RECEIPT
- `allowParsedMerchant` = mode ∈ {STORE_RAW, STORE_REDACTED}
- `allowParsedItems` = mode ∈ {STORE_RAW, STORE_REDACTED}
- `allowExternalIdHash` = mode != DO_NOT_STORE OR t ∈ {NOTIFICATION, EMAIL_RECEIPT, BANK_STATEMENT, BANK_API}
- `allowDebugBody` = mode == STORE_RAW AND s.debugDataPersistenceEnabled

Dedupe-hash sources (`needsDedupeHash`): NOTIFICATION, EMAIL_RECEIPT, BANK_STATEMENT, BANK_API.

## 2. Full matrix — 7 sources × 4 modes

Legend: `✓` true, `✗` false. `dbg` column = `debugDataPersistenceEnabled` (affects
`allowDebugBody` and, for AI_ARTIFACT/EXPORT_DEBUG, the resolved mode).

| # | Source | Setting input | Mode | rawBody | redactedBody | amountDateCcy | merchant | items | extIdHash | debugBody (dbg=true) | debugBody (dbg=false) |
|---|--------|--------------|------|---------|--------------|---------------|----------|-------|-----------|----------------------|-----------------------|
| 1 | NOTIFICATION | rawNotification=STORE_RAW | STORE_RAW | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ |
| 2 | NOTIFICATION | rawNotification=STORE_REDACTED | STORE_REDACTED | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ |
| 3 | NOTIFICATION | rawNotification=STORE_METADATA_ONLY | STORE_METADATA_ONLY | ✗ | ✗ | ✓ | ✗ | ✗ | ✓ | ✗ | ✗ |
| 4 | NOTIFICATION | rawNotification=DO_NOT_STORE | DO_NOT_STORE | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ |
| 5 | RECEIPT_OCR | rawOcr=STORE_RAW | STORE_RAW | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ |
| 6 | RECEIPT_OCR | rawOcr=STORE_REDACTED | STORE_REDACTED | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ |
| 7 | RECEIPT_OCR | rawOcr=STORE_METADATA_ONLY | STORE_METADATA_ONLY | ✗ | ✗ | ✓ | ✗ | ✗ | ✓ | ✗ | ✗ |
| 8 | RECEIPT_OCR | rawOcr=DO_NOT_STORE | DO_NOT_STORE | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| 9 | EMAIL_RECEIPT | emailReceipt=STORE_RAW | STORE_RAW | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ |
| 10 | EMAIL_RECEIPT | emailReceipt=STORE_REDACTED | STORE_REDACTED | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ |
| 11 | EMAIL_RECEIPT | emailReceipt=STORE_METADATA_ONLY | STORE_METADATA_ONLY | ✗ | ✗ | ✓ | ✗ | ✗ | ✓ | ✗ | ✗ |
| 12 | EMAIL_RECEIPT | emailReceipt=DO_NOT_STORE | DO_NOT_STORE | ✗ | ✗ | ✓ | ✗ | ✗ | ✓ | ✗ | ✗ |
| 13 | BANK_STATEMENT | rawBank=STORE_RAW | STORE_RAW | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ |
| 14 | BANK_STATEMENT | rawBank=STORE_REDACTED | STORE_REDACTED | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ |
| 15 | BANK_STATEMENT | rawBank=STORE_METADATA_ONLY | STORE_METADATA_ONLY | ✗ | ✗ | ✓ | ✗ | ✗ | ✓ | ✗ | ✗ |
| 16 | BANK_STATEMENT | rawBank=DO_NOT_STORE | DO_NOT_STORE | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ |
| 17 | BANK_API | rawBank=STORE_RAW | STORE_RAW | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ |
| 18 | BANK_API | rawBank=STORE_REDACTED | STORE_REDACTED | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ |
| 19 | BANK_API | rawBank=STORE_METADATA_ONLY | STORE_METADATA_ONLY | ✗ | ✗ | ✓ | ✗ | ✗ | ✓ | ✗ | ✗ |
| 20 | BANK_API | rawBank=DO_NOT_STORE | DO_NOT_STORE | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ |
| 21 | AI_ARTIFACT | dbg=true | STORE_REDACTED | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ (mode≠STORE_RAW) | — |
| 22 | AI_ARTIFACT | dbg=false | DO_NOT_STORE | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| 23 | EXPORT_DEBUG | dbg=true | STORE_RAW | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| 24 | EXPORT_DEBUG | dbg=false | DO_NOT_STORE | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| 25 | RECEIPT_OCR | rawOcr=STORE_RAW, dbg=true (cross-check debug gate) | STORE_RAW | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| 26 | NOTIFICATION | rawNotification=STORE_RAW, dbg=false (cross-check debug gate) | STORE_RAW | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | ✗ |

Rows 25–26 pin the `allowDebugBody = mode==STORE_RAW AND dbg` conjunction.
Rows 21–24 pin the debug-flag-driven sources.

## 3. Fail-closed normalization (corrupt / unavailable / invalid settings)

`FAIL_CLOSED_DEFAULTS`: all four storage modes = DO_NOT_STORE, `debugDataPersistenceEnabled=false`.

| Input condition | Old behavior (pre-15-A) | New behavior (15-A) |
|---|---|---|
| `getLoadState()` = CorruptedFailClosed (DataStore corrupt sentinel) | `getSettings()` already returns FAIL_CLOSED_DEFAULTS; **but** per-key invalid enum values could mask corruption (see next row) | Resolver reads load state; CorruptedFailClosed ⇒ policy built from `PrivacySettings.FAIL_CLOSED_DEFAULTS` — every source ⇒ row-4/8/12/16/22/24 semantics (DO_NOT_STORE everywhere) |
| Repository throws (settings unavailable) | Site 1: fallback STORE_REDACTED; Site 2: exception propagates, whole import fails; Site 3 camera: DO_NOT_STORE; Site 3 email: exception propagates, save fails | Resolver catches (rethrowing CancellationException) ⇒ policy from FAIL_CLOSED_DEFAULTS ⇒ DO_NOT_STORE. Strictly safer; sites 1/2/3 no longer fail open or abort on a settings read failure |
| Invalid persisted enum string (e.g. `rawNotificationStorageMode="GARBAGE"`) | RepositoryImpl coerces: notification/OCR → **STORE_RAW (fail-open)**, email/bank → STORE_REDACTED | RepositoryImpl coerces invalid → **DO_NOT_STORE** (fail closed); absent key keeps existing first-run default (STORE_RAW / STORE_REDACTED — product default, unchanged) |
| `forSourceSync(t, FAIL_CLOSED_DEFAULTS)` | Same as row set above | Same (pure function of given settings; normalization of the settings object is the caller/repository's duty) |

## 4. Call-site equivalence (old caller result vs proposed result)

### Site 1 — `NotificationProcessingPipeline.sanitizePendingReviewText`

| Case | Old result (mode used) | Proposed result (mode used) | Equal? |
|---|---|---|---|
| persistenceContext present (normal path) | `context.rawStorageMode` | `context.rawStorageMode` (unchanged — P2-11 capture-time mode) | ✓ identical |
| context absent, settings healthy | `settings.rawNotificationStorageMode` | `resolver.forSource(NOTIFICATION).mode` (same setting ⇒ same mode) | ✓ identical |
| context absent, settings read throws | STORE_REDACTED (fail-open-ish fallback) | DO_NOT_STORE (fail-closed) | ✗ intentional tightening per plan |
| context absent, corrupt load state | STORE_RAW (invalid-enum coercion fail-open) | DO_NOT_STORE | ✗ intentional tightening per plan |

`RawContentSanitizer.sanitizeNotificationText(text, DO_NOT_STORE)` returns null ⇒ no
notification text is persisted into PendingReview — strictly less raw data than STORE_REDACTED.

### Site 2 — `BankStatementLifecycleProcessor` statement receipt `rawOcrText`

| Case | Old result | Proposed result | Equal? |
|---|---|---|---|
| settings healthy | `rawBankStatementStorageMode` | `resolver.forSource(BANK_STATEMENT).mode` (same setting ⇒ same mode) | ✓ identical |
| settings read throws | exception propagates ⇒ entire import fails | DO_NOT_STORE ⇒ statement receipt saved with `rawOcrText=null`, import proceeds | ✗ intentional: fail closed on the FIELD, not fail the operation |
| invalid enum value | STORE_REDACTED (coercion) | DO_NOT_STORE (coercion fix + resolver) | ✗ intentional tightening |

BANK_STATEMENT and BANK_API share the rawBank setting; both map through the same resolver
branch — write-site equivalence holds for both (processor uses BANK_STATEMENT).

### Site 3 — `ReceiptLifecycleCoordinator`

| Path | Case | Old result | Proposed result | Equal? |
|---|---|---|---|---|
| camera/batch (`resolveRawStorageMode`) | healthy | `rawOcrStorageMode` | `resolver.forSource(RECEIPT_OCR).mode` | ✓ identical |
| camera/batch | read throws | DO_NOT_STORE | DO_NOT_STORE (resolver fail-closed) | ✓ identical outcome |
| camera/batch | corrupt/invalid enum | STORE_RAW (fail-open) | DO_NOT_STORE | ✗ intentional tightening |
| email (`saveEmailReceiptTyped`, deprecated `saveEmailReceipt`, `processEmailReceipt`) | healthy | `emailReceiptStorageMode` | `resolver.forSource(EMAIL_RECEIPT).mode` | ✓ identical |
| email | read throws | exception ⇒ save fails / ingest aborts | DO_NOT_STORE policy ⇒ raw body/items/sender/subject not persisted; dedupe hash still allowed (row 12: extIdHash ✓) | ✗ intentional: privacy field fail-closed instead of operation failure |
| email | corrupt/invalid enum | STORE_REDACTED | DO_NOT_STORE | ✗ intentional tightening |

Preserved invariants (verified by tests):
- Email dedupe/hash behavior: messageIdHash, fingerprint, textFingerprint, semanticFingerprint
  computed and used for dedup in ALL modes (hash permitted under DO_NOT_STORE per row 12).
- Source-specific parsed-field permissions: email keeps parsed amount/date/currency even under
  DO_NOT_STORE (row 12 `amountDateCcy ✓`); camera/bank do not.
- Debug body gating: only EXPORT_DEBUG-with-dbg (and any STORE_RAW source with dbg) sets
  `allowDebugBody`; no call site may derive this inline.

## 5. Ownership invariant (static check)

After 15-A, the ONLY callables of `RawStorageMode` selection from settings are inside
`RawPersistencePolicyResolver`. An architecture-style source scan asserts that outside
`domain/privacy/RawPersistencePolicyResolver.kt`, `PrivacySettingsRepositoryImpl.kt`
(storage coercion + UI settings write-back) and `PrivacySettingsScreen.kt`/`PrivacySettingsViewModel.kt`
(user-facing setting editors), no file reads `rawNotificationStorageMode`, `rawOcrStorageMode`,
`emailReceiptStorageMode`, `rawBankStatementStorageMode`, or `debugDataPersistenceEnabled`
from `PrivacySettings`. In-scope owned files (the three named call sites) must contain ZERO
such reads. (Other files that currently read these keys directly — ReceiptRepository,
ReceiptDebugExporter, NotificationRepository, NotificationCaptureService, NotificationCaptureGate,
BankApiIntegration — are OUTSIDE RP-15's three named call sites and remain listed here as
pre-existing debt, NOT silently exempted.)

## 6. Tests derived from this table (15-A)

1. `forSourceSync` reproduces rows 1–24 exactly (complete policy fields asserted, not just mode).
2. Rows 25–26 debug conjunction pins.
3. Fail-closed corruption: CorruptedFailClosed load state ⇒ all sources DO_NOT_STORE policy
   (forSource); repository exception ⇒ DO_NOT_STORE policy; CancellationException rethrown.
4. Write-site equivalence: site-by-site old-vs-new assertions from §4.
5. Ownership: static scan — the three owned files contain no direct settings-mode reads;
   `modeFor` not callable from outside the resolver.
