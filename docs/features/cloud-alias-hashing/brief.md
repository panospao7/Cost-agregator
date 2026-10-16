# Feature brief: cloud-alias-hashing — keyed cloud aliases (v2)

Author: feature-strategist (read-only). Persisted by the orchestrator. Date 2026-10-16. Post-1.0, docs-only. v1 kept at `brief-v1.md`; critique at `critique.md`.
Legend: [V] verified in source (v1 or this pass); [V-critique] verified by feature-critic; [UNVERIFIED] not checked.

## Changes from v1
1. Inc 1 = **alias generation only**, **8** sites (8th = `CloudReceiptItemCategorizationService.kt:536`). **Partial rebuttal:** it sits inside private `sanitizeCloudText` (`:523`), which has no caller in `app/src` [V]; when reached its input is blank (every PII regex leaves a non-blank token) → would only hash `""` to a constant. Handling: **delete dead code**, don't migrate. 4 private SHA copies (Dedupe `:186`, ReceiptItem builder `:118`, Warranty `:392`, ReceiptItem service `:581`).
2. Guard design tolerates legitimate **local log hashes** (Warranty `:255`/`:293`, ReceiptItem service `:328`/`:436`) [V-critique].
3. **Dropped v1 must-have #3** (5 builders' redaction decision → resolver) → ai-fixes follow-up (`docs/features/ai-fixes/impact-map-part-c.md:12` item 6). v1 D4, Q5 move with it.
4. **Threat model rewritten honestly:** gain = "no offline dictionary reversal"; residuals = co-sent cleartext, install linkability via API key; **domain separation by capability/field is a must-have**.
5. **Stored data rewritten:** three compared source hashes → one-time cache miss; intermittent Keystore failure covered.
6. **Q2 is a hard rule** for multi-alias flows, user-visible effect per capability; amends the RP-15 hasher contract.
7. Owner questions merged Q-A..Q-J. `RedactionSanitizer` is an interface + injected `class DefaultRedactionSanitizer` (`RedactionSanitizer.kt:9-19`) [V] → no object→class conversion.

## Problem
Users who opt into cloud AI with "redact before cloud" get merchant/category/item/app names replaced by unsalted SHA-256 prefixes (`domain/common/Hashing.kt:5-8`) [V] → anyone reading the request rebuilds names by hashing a name list; the same name gets the same alias for every user.

## Target users / situations
Only cloud AI opt-in **and** redaction on, on the cloud route of: assistant query interpretation, receipt item categorisation, dedupe judge, categorisation assist, review explanation, warranty extraction. Redaction off, on-device and deterministic routes unchanged.

## Threat model (honest)
- **Gain:** a reader of the cloud request (provider, its logs/review staff, logging proxy) can no longer reverse aliases offline with a name list; aliases differ across installs (per-install Keystore key, not backed up; `InstallationSecretHasher.kt:19-22`) [V].
- **Residual 1 — co-sent cleartext:** warranty sends full OCR text (merchant header) beside an aliased `Merchant:` (`CloudWarrantyExtractionService.kt:185-197`, `:306-314`) [V-critique]; receipt line text PII-scrubbed only [V-critique]; assistant query text/history can name merchants outside the alias list (`FinancialQueryInterpretationInputBuilder.kt:134-160`) [V] → provider learns alias↔name for the key's life. **Domain separation** (HMAC input bound to capability + field kind) confines that to one capability. Existing `CloudPiiSanitizer.kt:102`, `DefaultCloudPayloadRedactor.kt:67` already emit `merchant_v1_<hmac(name)>` from the same hasher without separation [V-critique] (Q-G).
- **Residual 2 — install linkability:** provider already links requests to an install/account via the API key [UNVERIFIED BYOK]; install-stable aliases add little; per-request key = later increment.

## Must-have scope (inc 1)
1. All cloud-bound merchant/category/item/app-package/label aliases at the 7 live sites come from the single `InstallationSecretHasher`, **domain-separated by capability and field kind**; type prefixes (`merchant_`, `category_`, `cat_`, `app_`…) kept. Sites: `FinancialQueryInterpretationInputBuilder.kt:124`, `:131`; `ReceiptItemCategorizationInputBuilder.kt:92`, `:98`, `:115`; `DedupeJudgeInputBuilder.kt:168`; `CategorizationAssistInputBuilder.kt:214`; `RedactionSanitizer.kt:17`; `CloudWarrantyExtractionService.kt:303`; `ReviewExplanationInputBuilder.kt:44`, `:56`.
2. Dead `sanitizeCloudText` (`CloudReceiptItemCategorizationService.kt:523-540`) removed; all 4 private SHA copies gone from cloud-alias code.
3. Local log hashes stay public SHA but clearly local-only: named local log-hash helper or explicit allowlist (Q-H).
4. Static guard/test blocks new public-hash aliases in cloud input builders and `data/ai/provider` without failing on local log hashes.
5. **Hard rule:** hasher null in a multi-alias flow → **no cloud call**, bounded reason code, on-device path; never collapsed `"redacted"` markers. Assistant query interpretation keeps category→id one-to-one or skips the call (today all categories collapse to `category_redacted`, last id wins `:53`, `:58`, `:80` → wrong category total [V-critique]).
6. RP-15 hasher docs amended (`InstallationSecretHasher.kt:25-26` "Callers MUST then emit an identity-free marker"; `:31-35` "never persisted") to cover skip-cloud and alias-bearing stored source hashes.
7. Existing tests using `sha256Prefix`/literal aliases (7 files [V-critique]) retargeted with equal/stronger assertions, never deleted.

## User-visible effect of the hard rule (hasher unavailable, multi-alias flows)
- Assistant query interpretation: `HybridQueryInterpretationService` wires on-device, then NoOp (`:22-29`) [V] → cloud-quality parsing lost; simpler on-device answer or "can't interpret".
- Dedupe judge: `OnDeviceDedupeJudgeService` (`HybridDedupeJudgeService.kt:59-64`) [V] → heuristic verdict.
- Receipt item categorisation: `OnDeviceReceiptItemCategorizationService` (`HybridReceiptItemCategorizationService.kt:26`; `CategorizeReceiptItemsUseCase.kt:170`) [V] → on-device suggestions.
- Categorisation assist: `OnDeviceCategorizationAssistService` (`HybridCategorizationAssistService.kt:27`) [V] → on-device suggestions.
- Whether `HybridRouter` falls back automatically when cloud is skipped (vs failing) [UNVERIFIED] → domain-modeler.
- Single-alias flows (warranty, review explanation) may still send the marker; nothing mapped back to an id [UNVERIFIED for review explanation].

## Later increments
- Per-request key or numbered per-request aliases (removes cross-request linkability). Assistant history re-sanitised each turn from raw text with that request's alias maps, `payloadJson` cleared (`FinancialQueryInterpretationInputBuilder.kt:97-116`) [V] → cross-request stability not needed for history.
- Local-only unsalted hashes (`SourceLinkEventMetadataBuilder.kt:92`, `SafeEventMetadata.kt:58`, `DefaultCloudPayloadPolicy.kt:50`) → PSG backlog.

## Non-goals
Redaction-source move to the resolver (→ ai-fixes follow-up); new cloud features/prompts/capabilities; consent, gate, settings UI or defaults; redaction-off raw names; PII regex or amount handling; schema/migration; new permission/network; folding into ai-fixes.

## Existing functionality reused / overlapped
`domain/privacy/InstallationSecretHasher.kt` + `data/privacy/KeystoreInstallationSecretHasher.kt` (as-is); `data/privacy/DefaultCloudPayloadRedactor.kt`, `CloudPiiSanitizer.kt` (existing RP-15 users, Q-G); `data/ai/provider/Hybrid*Service.kt` (on-device fallbacks); `docs/features/ai-fixes/impact-map-part-c.md:12` (owner of redaction-source move).

## Interaction with stored data
- Three stored source hashes include alias-bearing builder output and are compared for cache hits [V-critique]: `AiArtifactSourceHash.forDedupeJudge` → `JudgePendingReviewDuplicateUseCase.kt:66`; `forReviewExplanation` (merchant + package) → `ExplainPendingReviewUseCase.kt:70`; `forReviewCategorizationFallback` → `SuggestCategoryFallbackUseCase.kt:152`.
- Effect: one-time cache miss at upgrade, after key rotation, after restore/reinstall → repeat cloud calls (more data sent, more cost); reversible, no migration.
- Intermittent Keystore failure: single-alias review explanation flips alias↔marker → repeated misses; multi-alias flows skip cloud under the hard rule (no flip).
- `forReceiptItemCategorization` persisted (`CategorizeReceiptItemsUseCase.kt:160`) but no comparison found [V-critique, grep] → v1 D2 likely moot.
- Aliases not exported [UNVERIFIED backup/diagnostics].

## Market patterns (general crypto practice; no expense app documents its alias scheme)
- Secret-keyed HMAC prevents dictionary rebuilding; small public name sets make plain hashes reversible — [arXiv 1712.00871](https://arxiv.org/pdf/1712.00871), [FLAIM cs/0606063](https://arxiv.org/pdf/cs/0606063).
- Per-record salting breaks consistent tokens; a guessable key reintroduces the dictionary attack — [metzdowd 2014-05](https://www.metzdowd.com/pipermail/cryptography/2014-May/021421.html), [PEEPLL 1912.05861](https://arxiv.org/pdf/1912.05861).

## Privacy & permissions impact
Low risk, net positive: tightens data that already leaves the device; sends less when the hasher fails; no new egress/permission/notification/OCR/receipt content. Still T3 privacy (PSG + reviewer-strict).

## Domain questions (domain-modeler)
- D1 alias format per site; how capability/field is encoded into the HMAC input; does `cat_` keep the id in its seed? (Only regex `cat_(\d+)` parses aliases, `CloudReceiptItemCategorizationService.kt:588` [V-critique]; `v1_` aliases can't match.)
- D2 uniqueness rule within one request; exact multi- vs single-alias classification per capability.
- D3 where skip-cloud is decided (builder signal vs router); how `HybridRouter` routes a skipped cloud call to on-device.
- D4 bounded reason code(s) for "hasher unavailable, cloud skipped".
- D5 confirm no other stored/compared artifact embeds aliases.

## Owner questions (merged, with recommendations)
- **Q-A** domain-separated install-stable HMAC vs one alias across capabilities vs per-request key → **domain-separated, install-stable now**; per-request later.
- **Q-B** keep warranty `merchant_` alias though OCR text contains the name → **keep, domain-separated; document it adds little**.
- **Q-C** null hasher in multi-alias flows: skip cloud vs numbered aliases → **skip cloud for inc 1**; numbered aliases later (history doesn't need stability [V]).
- **Q-D** accept one-time cloud re-call for cached dedupe / review-explanation / categorisation-fallback artifacts after upgrade, rotation, restore → **accept**.
- **Q-E** owner of the 5-builder redaction-source move → **ai-fixes follow-up**.
- **Q-F** order → **after Release 1.0; assistant query (FQI) site after ai-fixes C1**; other sites can go earlier; insight (B2d) not a blocker.
- **Q-G** also domain-separate existing `CloudPiiSanitizer` / `DefaultCloudPayloadRedactor` merchant tokens → **yes if domain-modeler finds no stored comparison**; else later increment with residual recorded.
- **Q-H** local log hashes → **named local log-hash helper** rather than a path allowlist.
- **Q-I** include `ReviewExplanationInputBuilder` (merchant + `app_<package>`) → **yes**.
- **Q-J** local-only unsalted hashes → **out of scope; PSG backlog**.

## Success signal (observable, no content telemetry)
- Unit tests: alias ≠ `sha256Prefix(name)`; same name → different aliases across capabilities/fields; stable within a request and with a fixed key; different under two keys.
- Null hasher: multi-alias flows make no cloud call and record the reason code; assistant query category→id stays one-to-one.
- Static guard green with local log hashes present; fails on a planted public-hash alias.
- Existing cloud audit and gate tests green; retargeted alias tests at least as strict.

## Recommendation
**Pursue** — narrow fix reusing the reviewed keyed hasher, no egress; reshaped to alias generation only, honest residuals, hard fail-closed rule.

## Key paths (under `app/src/main/java/com/yourname/expensetracker/`)
`domain/common/Hashing.kt`; `domain/privacy/{InstallationSecretHasher,RedactionSanitizer}.kt`; `data/privacy/{KeystoreInstallationSecretHasher,DefaultCloudPayloadRedactor}.kt`; `domain/ai/usecase/{FinancialQueryInterpretation,ReceiptItemCategorization,DedupeJudge,CategorizationAssist,ReviewExplanation}InputBuilder.kt`; `data/ai/provider/{CloudWarrantyExtraction,CloudReceiptItemCategorization,HybridQueryInterpretation,HybridDedupeJudge,HybridReceiptItemCategorization,HybridCategorizationAssist}Service.kt`; `domain/ai/util/AiArtifactSourceHash.kt`.
