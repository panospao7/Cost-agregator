Here’s the prioritized bug list with exact file-level fixes.

## P0 — Bank provenance can degrade to weak/unknown identity
**Problem:** bank links may not reliably carry `providerTransactionIdHash`, `accountIdHash`, and `operationRunId`, so `BANK_TRANSACTION` identity can collapse or lose run traceability.

**Fix files:**
- `app/src/main/java/.../domain/bank/BankApiIntegration.kt`
  - ensure every bank create/review path builds a full `BankTransactionSourceContext`
  - pass `providerId`, `providerTransactionIdHash`, `accountIdHash`, `operationRunId`, `bookingDate`, `valueDate`, `transactionStatus`
- `app/src/main/java/.../domain/bank/provenance/BankSourceLinkPayloadFactory.kt`
  - make `sourceIdentityKey` deterministic and non-fallback by default
  - require bank-specific identity fields for `BANK_TRANSACTION`
- `app/src/main/java/.../domain/bank/provenance/BankSourceEventMetadataBuilder.kt`
  - mirror the same safe identity summary into event metadata
- Tests:
  - `app/src/test/java/.../domain/bank/provenance/BankSourceLinkPayloadFactoryTest.kt`
  - `app/src/test/java/.../domain/bank/provenance/BankSourceEventMetadataBuilderTest.kt`
  - `app/src/test/java/.../domain/bank/BankApiIntegrationTest.kt`

## P1 — Static guard misses JSONObject-style metadata leaks
**Problem:** `verify_source_provenance_boundaries.py` only catches some `mapOf(...)` patterns, but provenance builders may use `JSONObject.put(...)`, `putString(...)`, etc.

**Fix files:**
- `scripts/verify_source_provenance_boundaries.py`
  - extend scanning to detect blocked keys in:
    - `JSONObject.put(...)`
    - `putString(...)`
    - `buildJsonObject { ... }`
    - raw string literals in provenance builders
- `scripts/source_boundary_rules.json`
  - add broader blocked-key patterns and provenance-builder file allowlists
- `scripts/tests/test_verify_source_provenance_boundaries.py`
  - add fixtures proving `JSONObject.put("rawText", ...)` and similar now fail

## P2 — MIGRATION identity is too coarse
**Problem:** `SourceLinkWriterImpl` maps `SourceEntityType.MIGRATION` to one fixed identity, which collapses all migration provenance.

**Fix files:**
- `app/src/main/java/.../domain/provenance/SourceLinkWriterImpl.kt`
  - replace hardcoded `migration:v1`
  - derive identity from migration/backfill context, e.g. migration key + phase + operation run
- `app/src/main/java/.../domain/provenance/backfill/SourceLinkBackfillPayloadFactory.kt`
  - pass a stable migration/backfill identifier into payloads
- Tests:
  - `app/src/test/java/.../domain/provenance/SourceLinkWriterImplTest.kt`
  - backfill worker/factory tests

## P3 — Duplicate metadata may be broader than desired
**Problem:** `duplicateMetadata()` may include too much attempted business context.

**Fix file:**
- `app/src/main/java/.../domain/provenance/SourceLinkEventMetadataBuilder.kt`
  - review `attemptedMerchant`, `attemptedMerchantKey`, `attemptedDate`, `attemptedCurrency`
  - keep only what your privacy policy explicitly allows

## Recommended order
1. Bank provenance identity
2. Static guard coverage
3. Migration identity semantics
4. Duplicate metadata review

If you want, I can turn this into an execution checklist per file next.