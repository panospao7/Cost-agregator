# Feature brief: cloud-alias-hashing — keyed (installation-secret) cloud aliases + single redaction source

Author: feature-strategist (read-only). Persisted by the orchestrator. Date 2026-10-16. Post-1.0, docs-only.
Legend: [V] verified in source this pass; [UNVERIFIED] not checked.

## Problem
Users who opt into cloud AI with "redact before cloud" get merchant/category names replaced by aliases that are **unsalted SHA-256 prefixes** → anyone seeing the request can rebuild them by hashing likely names ("Lidl", "Groceries", the app's default categories). Pseudonymisation is only encoding. The alias for "Lidl" is also identical across every user/install → cross-user linkage.

## Who is exposed, when
- Only cloud AI opt-in **and** redaction on (resolved policy), on the cloud route of: query interpretation (assistant), receipt item categorisation, dedupe judge, categorisation assist, review explanation, warranty extraction.
- Redaction **off**: names sent in clear by design/consent — unchanged. On-device/deterministic routes unaffected.

## Threat model
- Anyone who can read the cloud request (AI provider, its logs/abuse-review staff, a logging proxy) rebuilds names offline with no key, only a name list; can link the same merchant across users/installs.
- With keyed HMAC (secret never leaves the device), reversal needs the key. Linkability within one install remains (as today) → later option.
- Target users: privacy-conscious users who chose cloud AI because redaction exists (shared API keys / regulated jobs [UNVERIFIED that BYOK/shared keys exist]).

## Verified facts
- `domain/common/Hashing.kt:5-8` `sha256Prefix` = plain SHA-256, 12 hex default, no key [V].
- Keyed hasher exists [V]: `domain/privacy/InstallationSecretHasher.kt` (`hmacPrefix`, `versionedPseudonym` format `v<ver>_<16hex>`, fail-closed marker `"redacted"`); `data/privacy/KeystoreInstallationSecretHasher.kt` (Keystore HMAC key, not backed up, rotatable by version bump, returns null on failure); used by `DefaultCloudPayloadRedactor.kt:18-28`, whose docs (RP-15) say both redaction paths MUST use this one service.
- Public-hash alias sites [V]:
  - `FinancialQueryInterpretationInputBuilder.kt:124` (`merchant_`), `:131` (`category_`).
  - `ReceiptItemCategorizationInputBuilder.kt:92` (`cat_` seeded `"$id:$name"` — ids small, still enumerable), `:98` (`merchant_`), `:115` (`item_` fallback, probably unreachable [UNVERIFIED]), `:118` private SHA copy.
  - `DedupeJudgeInputBuilder.kt:168` (`${prefix}_`), private SHA copy `:186`.
  - `CategorizationAssistInputBuilder.kt:214` (`category_`); takes `RedactionSanitizer` `:30`.
  - `RedactionSanitizer.kt:17` (`merchant_`); only caller CategorizationAssistInputBuilder.
  - `CloudWarrantyExtractionService.kt:303` (`merchant_`), private SHA copy `:392` (hashes at `:255`, `:293` are local response-body logs only, not cloud requests).
  - **Not on the original list:** `ReviewExplanationInputBuilder.kt:44` (`merchant_`), `:56` (`app_${packageName}` — package names public → trivially reversible).
- 3 files use a private `sha256Prefix` copy → an import-only guard would miss them.
- Builders ORing redaction flags themselves [V]: `ReviewExplanationInputBuilder.kt:32-33`, `ReceiptItemCategorizationInputBuilder.kt:41-44`, `ReceiptAssistInputBuilder.kt:29-30`, `DedupeJudgeInputBuilder.kt:30-33`, `CategorizationAssistInputBuilder.kt:39-40`, `:83-84`; `GenerateTransactionInsightUseCase.kt:96-98` (moves with ai-fixes B2d); `FinancialQueryInterpretationInputBuilder.kt:29-32` (owned by ai-fixes Part C C-6 / slice C1, INV-C18).

## Scope
**Must-have (inc 1)**
1. Every cloud-bound merchant/category/item/app-package/label alias at the 7 sites comes from the single `InstallationSecretHasher` (shared `versionedPseudonym` format); public-hash alias code removed there, incl. the 3 private SHA copies.
2. Alias **type** prefixes (`merchant_`, `category_`, `cat_`, …) kept so the model knows the token kind; exact format → domain-modeler.
3. The 5 not-yet-migrated builders take their redaction decision only from `EffectiveCloudAiPolicyResolver`, with a result **exactly equal** to today's combined flags (same rule as ai-fixes P10/INV-C18).
4. Fail-closed behaviour defined for hasher null (Q2).
5. A static guard/test blocks new public-hash aliases in cloud input builders and `data/ai/provider`.

**Later increments:** optional per-request key (no cross-request linkability within an install); review of local-only unsalted hashes — `SourceLinkEventMetadataBuilder.kt:92` (8-char merchant hash in event metadata, maybe persisted/exported [UNVERIFIED]), `SafeEventMetadata.kt:58`, `DefaultCloudPayloadPolicy.kt:50` (raw prompt text hash in audit record).

**Non-goals:** no new cloud features/prompts/capabilities; no change to consent, gate, settings UI or defaults; redaction-off raw names unchanged; no PII regex or amount-handling change (amounts already exempt, `CloudPiiSanitizer`); no schema/migration; no new permissions/network; `GenerateTransactionInsightUseCase` (ai-fixes B2d) and FQI redaction source (ai-fixes C1) only touched after those land.

## Stability, rotation, restore
- **Within a request:** aliases must be stable and unique (model echoes them; mapped back via per-request alias maps `FinancialQueryInterpretationInputBuilder.kt:35-96`, consumed `CloudQueryInterpretationService.kt:247-256`). HMAC gives this.
- **Across requests:** not needed for correctness as far as seen (alias maps rebuilt per request; after ai-fixes C1 prior user queries re-sanitised from raw text each turn). Install-stable hasher acceptable, matches RP-15.
- **Key rotation:** version bump changes every alias — harmless unless an alias is stored.
- **Reinstall/restore:** new Keystore key → different aliases; harmless for the same reason.

## Interaction with stored data
- Hasher docs: pseudonyms "never persisted in the app DB" — one exception: `AiArtifactSourceHash.forReceiptItemCategorization` (`:111-117`) hashes `input.merchant` (= the **alias** when redaction on) + `redactBeforeCloud` → changing alias generation changes stored source hashes → existing receipt-item categorisation results may be treated stale once at upgrade, after every key rotation, and after restore [UNVERIFIED whether stored and compared].
- Aliases not exported [UNVERIFIED backups/diagnostics export]. Cloud audit records `payloadHash` + redacted field list, not aliases [UNVERIFIED].

## Effect on cloud answer quality
No meaningful change (aliases already meaningless to the model); 12 → 16 hex + `v1_` → a few more tokens at 100 merchants. Risk: hasher failure collapsing all names to `"redacted"` → alias map collisions → wrong/arbitrary matches (FQI `:71` overwrites entries) → Q2 matters.

## Privacy & permissions impact
Low risk, positive: tightens data that already leaves the device; no new egress/permission/network/notification/OCR/receipt content. Still a T3 privacy area (PSG + reviewer-strict).

## Market patterns (general crypto practice; no expense app documents its alias scheme)
- Secret-keyed HMAC prevents dictionary rebuilding; names from a small, mostly public set make plain hashes reversible ([arXiv 1712.00871](https://arxiv.org/pdf/1712.00871), [FLAIM arXiv cs/0606063](https://arxiv.org/pdf/cs/0606063)).
- Per-record salting breaks consistent tokens; a guessable key reintroduces the dictionary attack ([metzdowd cryptography list](https://www.metzdowd.com/pipermail/cryptography/2014-May/021421.html), [PEEPLL arXiv 1912.05861](https://arxiv.org/pdf/1912.05861)).

## Rough size / risk
Small–medium: ~8 main files + tests, no schema/UI, T3. Risks: accidental behaviour change moving to the resolver (Q5); alias collisions on the fail-closed path; conflicts with ai-fixes C1/B2d if mis-ordered; stale stored source hashes.

## Domain questions (domain-modeler)
- D1 alias format per site (type prefix + `versionedPseudonym`?); does `cat_` keep the id in its seed?
- D2 should `forReceiptItemCategorization` hash the raw merchant instead of the alias (so key changes don't invalidate stored results)? Is that hash persisted?
- D3 collision/uniqueness rule within one request, incl. the fail-closed marker.
- D4 does `EffectiveCloudAiPolicyResolver.resolve().redactBeforeCloud` equal `aiPolicy.shouldRedact(settings, capability) || privacy.redactBeforeCloud` for **each** capability (`AiPolicyImpl.kt:53`; capability overrides [UNVERIFIED])? If not, the move could weaken redaction.
- D5 bounded reason code when a cloud call is skipped because the hasher is unavailable.

## Owner questions (with recommendations)
- Q1 install-stable HMAC (existing service) vs per-request key → **install-stable now** (one legal path, RP-15); per-request later.
- Q2 hasher unavailable: `"redacted"` markers vs skip cloud → **skip cloud** for multi-alias flows (FQI, categorisation, dedupe, receipt items) with on-device/deterministic fallback + bounded reason code; marker acceptable for single-merchant flows (warranty, review explanation).
- Q3 include `ReviewExplanationInputBuilder` (merchant + app package) → **yes**.
- Q4 local-only unsalted hashes (event metadata, audit `payloadHash`) → **out of scope**; PSG backlog.
- Q5 if D4 shows the resolver is weaker for any capability, block that builder's move → **yes** (never loosen redaction).
- Q6 order → **after Release 1.0 and after ai-fixes C1 (FQI builder) + B2d (insight use case)**.

## Success signal (no content telemetry)
- Tests: alias("Lidl") ≠ `sha256Prefix("Lidl")`; stable within a request and for install life with a fixed key; differs under two keys; hasher null → Q2 behaviour.
- Redaction-decision tests equal current combined flags; resolver mock called; `PrivacySettingsRepository` not read (as AE-C29).
- Static guard: no `sha256Prefix`/`MessageDigest` alias building in cloud input builders/providers.
- Existing cloud audit and gate tests unchanged and green.

## Recommendation
**Pursue** — narrow, high-value privacy fix reusing an existing reviewed keyed service; no new egress.

## Key paths
`domain/common/Hashing.kt`; `domain/privacy/InstallationSecretHasher.kt`; `data/privacy/KeystoreInstallationSecretHasher.kt`; `data/privacy/DefaultCloudPayloadRedactor.kt`; `domain/ai/usecase/{FinancialQueryInterpretation,ReceiptItemCategorization,DedupeJudge,CategorizationAssist,ReviewExplanation,ReceiptAssist}InputBuilder.kt`; `domain/privacy/RedactionSanitizer.kt`; `data/ai/provider/CloudWarrantyExtractionService.kt`; `domain/ai/util/AiArtifactSourceHash.kt`; `domain/privacy/EffectiveCloudAiPolicy.kt` (all under `app/src/main/java/com/yourname/expensetracker/`).
