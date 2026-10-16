# Critique: cloud-alias-hashing (brief v1)

Author: feature-critic (read-only; did not write the brief). Persisted by the orchestrator. Date 2026-10-16.
Legend: [V] verified in source this pass; [UNVERIFIED] not checked.

**Verdict: RESHAPE.** Problem is real (completes RP-15 remediation, today only `CloudPiiSanitizer` + `DefaultCloudPayloadRedactor`), but the brief misses a cloud site, overstates the threat-model gain, bundles a second feature, and gets stored-data impact wrong.

## Blocking objections
1. **Site list incomplete.** `data/ai/provider/CloudReceiptItemCategorizationService.kt:536` sends `"${fallbackPrefix}_${trimmed.sha256Prefix()}"` in the blank-text fallback of `sanitizeCloudText`, own private SHA copy `:581` [V] → **4** private SHA copies (Dedupe `:186`, ReceiptItem builder `:118`, Warranty `:392`, this). Both cloud services also use `sha256Prefix` legitimately for local log hashes (Warranty `:255`/`:293`, ReceiptItem service `:328`/`:436`) [V] → a plain "no sha256Prefix in data/ai/provider" guard fails on correct code unless allowlisted or moved to a named log-hash helper.
2. **"Reversal needs the key" is false for any merchant ever sent in clear next to its alias.**
   - Warranty sends full receipt OCR text (merchant header included) beside the aliased `Merchant:` field (`CloudWarrantyExtractionService.kt:185-197`); `sanitizeReceiptText` scrubs only PII patterns (`:306-314`) [V].
   - `CloudPiiSanitizer.kt:102` and `DefaultCloudPayloadRedactor.kt:67` already produce `merchant_v1_<hmac(name)>` from the same hasher + input [V]; brief §1 would make every capability emit that identical token.
   - Provider learns alias↔name from warranty requests (also receipt line-item text — builder scrubs PII only, `ReceiptItemCategorizationInputBuilder.kt:101-115`; FQI query text naming merchants outside the recent-100 list, `:144-159`) → de-aliases that merchant in every other capability from the same install for the key's life.
   - Provider already links requests to an install via API key/account [UNVERIFIED BYOK] → honest gain = "no offline dictionary reversal". Inc 1 must domain-separate HMAC input by capability/field, or state this residual exposure and accept it.
3. **Two features in one.** Must-have #3 (5 builders' redaction decision → `EffectiveCloudAiPolicyResolver`) is not needed for hashing and is the open decision already raised in `docs/features/ai-fixes/impact-map-part-c.md:12` (item 6) → a third owner for one invariant.
   - D4: `AiPolicyImpl.kt:52-53` `shouldRedact` ignores capability, returns `settings.redactBeforeCloud` [V] → OR logic matches per capability.
   - Still not "exactly equal": builders read the caller-passed `settings` snapshot; resolver reads `aiSettingsRepository.settings().first()` (`EffectiveCloudAiPolicy.kt:125,145`) [V].
   - Builders AND with `canUseCloudFor(settings, cap)`, which the resolver does not model; INV-C18 keeps that conjunct (`domain-spec-part-c.md:206`) [V].

## Cautions
- **Stored data (brief wrong both ways):** `forReceiptItemCategorization` is persisted (`CategorizeReceiptItemsUseCase.kt:160`) but no freshness comparison found [V-by-grep] → D2 likely moot. Three other source hashes include alias-bearing builder output and ARE compared for cache hits: `forDedupeJudge` (`AiArtifactSourceHash.kt:16-43`) → `JudgePendingReviewDuplicateUseCase.kt:66`; `forReviewExplanation` (`:45-60`, merchant + packageName) → `ExplainPendingReviewUseCase.kt:70`; `forReviewCategorizationFallback` (`:145-172`) → `SuggestCategoryFallbackUseCase.kt:152`. Effect: one-time cache miss at upgrade → repeat cloud calls (more data sent, more cost); intermittent Keystore failure flips hash alias↔marker → repeated misses. Reversible, no migration; must be stated and accepted.
- **Money correctness on fail-closed path:** hasher null → FQI collapses every category to `category_redacted`; `categoryNameToId[alias]` overwritten to last id (`FinancialQueryInterpretationInputBuilder.kt:53`), `distinct()` (`:58`, `:80`) leaves one → assistant sums the wrong category (wrong total, not "unavailable") → Q2 for multi-alias flows is a correctness requirement.
- **Q2 skip-cloud** right for multi-alias flows; doesn't change consent (sends less) but changes what the user sees. Per-capability on-device/deterministic fallback for FQI, dedupe, item categorisation, categorisation assist [UNVERIFIED]. Hasher KDoc (`InstallationSecretHasher.kt:25-26`) tells callers to emit the marker → skip-cloud amends the RP-15 contract.
- **Format parsing:** only regex `cat_(\d+)` (`CloudReceiptItemCategorizationService.kt:588`); `v1_` aliases can't match [V]. Low risk.
- **Tests:** 7 test files use `sha256Prefix` or literal aliases (e.g. `FinancialQueryInterpretationInputBuilderTest` ×6, `ReviewExplanationInputBuilderTest` ×4) [V] → retarget with equal/stronger assertions, never delete.
- **DI shape:** if `RedactionSanitizer` is an `object`, injecting the hasher means converting it to a class (as `CloudPiiSanitizer` was) [UNVERIFIED].
- **Sequencing:** FQI builder (`:29-32`, `:124`, `:131`) is in ai-fixes C1's blast radius → FQI site after C1. Insight (B2d) has no alias site → not a blocker.
- **Do not fold into ai-fixes** (already large); keep a separate small T3 feature starting after C1.

## Minimal reshape that flips to GO
1. Inc 1 = alias generation only, at **8** sites (7 + `CloudReceiptItemCategorizationService.kt:536`); remove all 4 private SHA copies; guard allowlists log-hash calls or moves them to a named helper.
2. Domain-separate HMAC input by capability/field (e.g. `"<capability>:<kind>:" + name`), or record the co-sent-cleartext residual risk as an owner-accepted limitation. Both use the existing hasher.
3. Drop must-have #3 (redaction-source migration) → ai-fixes follow-up (impact-map-part-c item 6).
4. Rewrite "Interaction with stored data": list the three compared source hashes; accept one-time cache miss.
5. Q2 as hard rule: multi-alias flows with null hasher → no cloud call + bounded reason code, never collapsed markers; test that FQI category→id stays one-to-one or the call is skipped.

## Owner questions (with recommendations)
- Q-A domain separation vs one alias shared across capabilities → **separate per capability** (cheap, no new service, limits co-sent-cleartext leakage to that capability).
- Q-B keep warranty `merchant_` alias though OCR text contains the name → **keep, domain-separated; document it adds little**.
- Q-C null hasher in multi-alias flows: skip cloud vs identity-free per-request numbered aliases (`merchant_1..n`, no key) → **skip cloud for inc 1**; numbered aliases may later replace per-request keys if cross-request stability isn't needed [UNVERIFIED for prior assistant turns in FQI history].
- Q-D accept one-time cloud re-call for cached dedupe / review-explanation / categorisation-fallback artifacts after upgrade → **accept**.
- Q-E owner of 5-builder redaction-source migration → **ai-fixes follow-up**, not this feature.
- Q-F order → **after Release 1.0; FQI site after ai-fixes C1**; other 7 sites can go earlier.
