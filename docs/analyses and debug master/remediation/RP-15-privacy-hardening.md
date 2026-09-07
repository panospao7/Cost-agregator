# RP-15 — Privacy hardening & denied-UX (Pipeline 8)

> **Scope class:** pipeline-isolated. **Mode:** strict (privacy).
> **Files:** `domain/privacy/**` (`EffectiveCloudAiPolicy`, `RawPersistencePolicyResolver`, `CloudAiPrivacyGate`), `data/privacy/**` (`DefaultCloudPayloadRedactor`, `CloudPiiSanitizer` at `data/ai/provider/internal/`, `DataRetentionWorker`), `data/ai/provider/CloudWarrantyExtractionService.kt`, write sites: `NotificationProcessingPipeline.kt:816-824`, `BankStatementLifecycleProcessor.kt:286-288`, `ReceiptLifecycleCoordinator.kt` (email path), `ui/components/PrivacyBlockedCard.kt`, ViewModels: `BackupRestoreViewModel`, `ExportOptionsViewModel`, `SpendingMapViewModel`, `ReviewViewModel`.
> **PR shape:** 3 PRs — (15a) resolver consolidation = D5; (15b) policy & redactor hardening = P8-006, 008, 005(removal part); (15c) denied-UX adoption = P8-P1-12; NEW-P8-006 folds into 15c's worker semantics or lands with RP-16 (noted).
> **Gated by:** RP-00/D5 (resolver decision — plan assumes option (a), wire the resolver).

---

## 15a — Consolidate storage-mode resolution behind `RawPersistencePolicyResolver` (D5)

**Problem (debt).** The resolver (`domain/privacy/RawPersistencePolicyResolver.kt:48-62`) implements the per-source matrix (NOTIFICATION→`rawNotificationStorageMode`, RECEIPT_OCR→`rawOcrStorageMode`, EMAIL_RECEIPT→`emailReceiptStorageMode`, BANK_*→`rawBankStatementStorageMode`) but has **zero production callers**; each write site re-implements the selection inline (`NotificationProcessingPipeline.kt:816-824` with fail-closed fallback, `BankStatementLifecycleProcessor.kt:286-288`, coordinator email path `:620/:761`) — the drift-bug pattern this class was built to prevent.

**Fix design.**
1. Make the resolver the single entry point: each of the three write sites calls `resolver.modeFor(RawSourceType.X, settings)` instead of inline selection. The resolver already receives the settings snapshot — keep the **fail-closed fallback inside the resolver** (unresolvable → most-restrictive mode, i.e. `STORE_METADATA_ONLY` for notifications / `STORE_REDACTED` where that's the current convention — mirror each site's current fallback exactly so behavior is unchanged; the audit verified each site's fallback, replicate those per source type in the resolver's docs).
2. Unit tests exist (`RawPersistencePolicyTest`) — extend with the three production source types' fail-closed behaviors.
3. Delete the inline selection code; keep the sites' *use* of the returned mode untouched (sanitize calls unchanged).

**What it solves.** One matrix, one fail-closed convention; future source types can't drift.

**Guardrails.** Privacy review is mandatory here (strict mode): the PR must demonstrate mode-for-mode equivalence (table in PR description: source × settings state × old inline result vs resolver result). No behavior change is the success criterion.

## 15b — Policy & redactor hardening

### P8-006 — `requireAllowed` covers 4 of 25 capabilities; the rest throw (LOW, by-design trap)

**Problem.** `EffectiveCloudAiPolicy.kt:29-40` handles `RECEIPT_IMAGE_CLOUD_UPLOAD`, `CLOUD_AI_BANK_STATEMENT`, `AI_BANK_STATEMENT_PARSING`, `CLOUD_AI_RECEIPT_OCR`; every other cloud capability throws `SecurityException`. Fail-closed is right — but the "register new capabilities explicitly" migration never happened, so the next wiring (warranty extraction is the obvious candidate) ships a guaranteed crash.

**Fix design.**
1. Map the remaining **cloud** capabilities to the policy's own merge semantics (`resolve().cloudAllowed` AND any per-capability receipt-image style flags where they exist): `CLOUD_AI_RECEIPT_ASSIST`, `CLOUD_AI_ITEM_CATEGORIZATION`, `CLOUD_AI_WARRANTY_EXTRACTION`, `CLOUD_AI_DAILY_BRIEFING`, `CLOUD_AI_GENERAL` → each returns `cloudAllowed` (the composite `privacy.cloudAiEnabled && ai.allowCloudAi` result) — that is exactly what those services gate on today via `resolve()`, so `requireAllowed` becomes uniformly callable without behavior change.
2. Keep the throw only for genuinely unknown/unregistered values (defensive against typos in new enum entries) — that remains the fail-closed dev guard.
3. Do **not** add callers in this PR (wiring the warranty service to `requireAllowed` is RP-18/RP-20-adjacent scope creep); this PR makes the function safe to call.

**Tests.** Per-capability matrix: each cloud capability resolves to the merged decision; a fake unregistered name still throws.

### P8-008 — Redactor edge gaps: phone floor, unsalted merchant hash (LOW)

**Problem.** `CloudPiiSanitizer.kt:9` `PHONE_REGEX` floor is 8 chars (bare 7-digit locals pass; separator-bearing 7-digit numbers match); `merchant_${sha256Prefix(12)}` (`:56-63`, used at `DefaultCloudPayloadRedactor.kt:44-46, :64`) is unsalted and truncated — dictionary-reversible over known merchant brands in outbound payloads.

**Fix design.**
1. Phone: lower the floor to 7 (`\+?\d[\d\s().-]{5,}\d` → min length 7). Verify false-positive rate against currency/amount strings in payloads (the regex requires a digit head/tail with separators — amounts like "1.234,56" must not match; add fixtures before/after).
2. Merchant hash: add a **per-install salt** — generate once, store in the existing encrypted prefs/DataStore privacy store (not Keystore-required; salt need not be secret, only unique — but storing alongside settings is simplest); prefix hashes with a version tag (`mh1:`) so future rotations are detectable. Redactor computes `sha256Prefix(salt + merchant)`. Hash length 12→16 hex chars while at it (still short, collision-safe for display).
3. Migration note: existing `merchant_hash_*` values in cached AI artifacts become stale — they are display-only redaction artifacts (grep consumers); acceptable, note in PR.

**Tests.** 7-digit locals redacted; amounts/currency strings not redacted (fixture corpus); salted hash stable per install, differs across installs.

### P8-005 — Dead duplicate sanitizer copies in the warranty service (LOW-MED)

**Fix design.** Delete `CloudWarrantyExtractionService.sanitizeMerchant/sanitizeReceiptText` (`:297-318`) and their companion regexes (`:381+`) — verified zero callers, and they predate the NEW-P8-004 PII patterns (a trap for the next caller). The live path goes through `DefaultCloudPayloadRedactor` → `CloudPiiSanitizer`. Listed under RP-20/D4 as well — execute here (same module as 15b). **Test:** compile + existing warranty service tests unaffected.

## 15c — Privacy-denied UX adoption (P8-P1-12, adjusted)

**Problem (adjusted).** A shared component exists — `ui/components/PrivacyBlockedCard.kt` — and `AssistantViewModel` has a typed blocked state (S3-011); but `BackupRestoreViewModel` (`:108-109, :213-214`) and `ExportOptionsViewModel` (`:224`) **string-match** "denied" messages, `SpendingMapViewModel` catches raw `SecurityException` (`:825`), and `ReviewViewModel:1039` stringifies `DebugExportResult.Denied`. The gap is inconsistent adoption, not absence.

**Fix design.**
1. Standardize the error channel: wherever a privacy gate denies, the ViewModel state carries a typed `PrivacyBlocked(capability: PrivacyCapability, reason: String-resource-id)` — not exception-message strings. Small sealed-state addition or reuse of the assistant's pattern.
2. Each of the four ViewModels renders `PrivacyBlockedCard` for that state (the component already handles layout/strings).
3. Remove the string-matching (`contains("denied")`) branches — they are fragile against copy changes.
4. NEW-P8-006 (permanent retention failure → `Result.success`): the re-validation noted the daily periodic re-run mitigates and the test codifies success (`DataRetentionWorkerTest:206` — REVAL-7). Minimal honest fix lives in RP-16's counter/terminal work (P9-004 makes permanent failures visible in the run row + diagnostics). **Decision: implement the run-row visibility in RP-16/P9-004 and leave `Result.success` semantics** (retrying permanent failures forever would violate worker idempotency budgets) — document that reasoning in the DataRetentionWorker KDoc here.

**What it solves.** Every privacy denial looks and behaves identically; no message-string coupling; retention failure visibility without retry storms.

**Guardrails.** UI-only + state plumbing; no gate logic changes (gates stay fail-closed). String resources: reuse `PrivacyBlockedCard`'s existing ones; add capability-specific subtitle strings only if missing.

**Tests.** Per ViewModel: denied gate → blocked state renders card; no string-matching code paths remain (grep assertion in review checklist).

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*Privacy*" --tests "*CloudPii*" --tests "*CloudPayload*" --tests "*RawPersistence*" --tests "*BackupRestoreViewModel*" --tests "*ExportOptionsViewModel*"
```

## Sequencing & risk
- 15a first (behavior-equivalent consolidation, heaviest privacy review), then 15b, 15c. Risk: 15a is the only one touching write-path behavior — the equivalence table is the review artifact; 15b/15c are additive.
