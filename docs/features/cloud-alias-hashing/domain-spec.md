# Domain spec: cloud-alias-hashing (Stage 3)

Author: domain-modeler. Date 2026-10-16. Post-1.0, docs-only. Inputs: `brief.md` (v2, binding), `critique.md`, Gate 1 decisions (Q-A..Q-J as recommended) in `workflows/active/cloud-alias-hashing-handoff.md`.
Legend: [V] verified in source this pass; [V-brief] trusted from brief/critique; [UNVERIFIED] not checked. Paths are under `app/src/main/java/com/yourname/expensetracker/`. Class names below (`CloudAlias*`, `localLogHash`) are proposals. Impact-mapper may rename them, but the semantics are binding.

## 0. Answers to D1–D5, Q-G (summary)
- **D1:** one alias function. HMAC input = `cah1|<scopeId>|<kindId>|<value>`. Output = `<prefix>_v<ver>_<16 hex>`. `cat_` **keeps the id** in its seed (`<id>:<name>`). Real aliases can never full-match `cat_(\d+)` (`matchEntire`, `CloudReceiptItemCategorizationService.kt:577`, `:588`) [V].
- **D2:** classification is a property of the scope (§2 table). Uniqueness is defined per (scope, kind) entity. A collision makes a multi-alias request UNAVAILABLE (§3).
- **D3:** `HybridRouter` has **no** failover. It dispatches once on `router.decide(...)` (`HybridRouter.kt:62-69`, KDoc `:16-17`) [V]. `HybridDedupeJudgeService` fails over both ways, including on-device→**cloud** (`:63-70`) [V]. On-device consumes the same aliased input as cloud: the builders alias whenever redaction applies, regardless of route [V: FQI `:29-32`, CA `:40`, RE `:32`]. The builder signals alias state on the input. The **routing layer** (HybridRouter + HybridDedupeJudgeService, one shared predicate) dispatches UNAVAILABLE inputs to the **deterministic fallback** (see OD-H1; this deviates from the brief's "on-device path").
- **D4:** reuse `DiagnosticReasonCode.PRIVACY_FAIL_CLOSED` (`domain/diagnostics/DiagnosticReasonCode.kt:7`) [V], with a bounded detail enum `CloudAliasUnavailableReason { HASHER_UNAVAILABLE, ALIAS_COLLISION }` (OD-H2).
- **D5 / Q-G:** §6. The `CloudPiiSanitizer`/`DefaultCloudPayloadRedactor` tokens reach only the audit `payloadHash`. That hash is stored (`PrivacyAuditLoggerImpl.kt:21`) but is never compared: grep finds no `payloadHash ==` outside the in-memory `PreparedCloudPayload.equals` `:41` [V-grep]. `forDashboardBriefing` hashes the raw `DashboardBriefingInput`, not the prompt (`AiArtifactSourceHash.kt:62-96`) [V]. So the tokens feed **no compared artifact**, and per Q-G they are **domain-separated now** (scope `pii_redactor`).

## 1. Entities & value objects (all in-memory, never persisted)
- `CloudAliasScope` (enum, domain/privacy). Fields: `id: String` (frozen literal, never derived from `AiCapability.name`, so renames cannot silently rotate aliases) and `cardinality: MULTI|SINGLE`.
- `CloudAliasKind` (enum). Fields: `id: String` (frozen) and `prefix: String`. Values: `MERCHANT("merchant","merchant")`, `CATEGORY("category","category")`, `CATEGORY_OPTION("category_option","cat")`, `SOURCE_APP("source_app","source")`, `APP_PACKAGE("app_package","app")`, `TEXT_FALLBACK("text_fallback", <caller prefix, today "text">)`.
- `CloudAliasSession`. One per request and scope. Memoises `(kind, normalisedValue) → alias`, detects collisions, and exposes `state`.
- `CloudAliasState` (on each multi-alias input model and the session). Values: `NOT_APPLICABLE` (no alias requested), `KEYED`, `MARKED` (SINGLE only: ≥1 marker emitted), `UNAVAILABLE(reason)` (MULTI only; sticky).
- `CloudAliasFactory` (`@Inject`, wraps the single `InstallationSecretHasher`). It is the **only** caller of `hmacPrefix`/`versionedPseudonym` besides the hasher implementation.
- Normalisation `N(v) = v.trim().take(80)`. It is case-preserving, with no Unicode normalisation and no lowercasing. This keeps today's distinctness ("Lidl" ≠ "LIDL") and the alias↔raw-name maps intact.

## 2. Alias function and per-site table
`alias(scope, kind, raw)`:
1. `n = N(raw)`. If `n` is blank, return the site placeholder (§2 col "blank") **without calling the hasher**.
2. `input = "cah1|" + scope.id + "|" + kind.id + "|" + entityValue`. `entityValue = n`, except `CATEGORY_OPTION`, where it is `"<categoryId>:" + n`. The encoding is injective: scope and kind ids are fixed `[a-z_]` literals with no `|`, and the value is the suffix. `cah1` is the encoding version, independent of the key version.
3. `hex = hasher.hmacPrefix(input)` (16 hex). If `null`, apply §4. Otherwise return `prefix + "_v" + hasher.currentVersion + "_" + hex`, for example `merchant_v1_9f0c…` (16 hex).

| Site [V] | scope.id | kind | Today | Cardinality | Blank placeholder |
|---|---|---|---|---|---|
| FQI `FinancialQueryInterpretationInputBuilder.kt:124` (list + query/history replacement) | `query_interpretation` | MERCHANT | `merchant_`+SHA(n) | MULTI | `""` (filtered, as today) |
| FQI `:131` | `query_interpretation` | CATEGORY (name only, see INV-H12) | `category_`+SHA(n) | MULTI | `""` |
| RI builder `ReceiptItemCategorizationInputBuilder.kt:92` | `receipt_item_categorization` | CATEGORY_OPTION (`id:name`) | `cat_`+SHA("id:raw") | MULTI | n/a (id always present) |
| RI builder `:98` | `receipt_item_categorization` | MERCHANT | SHA("Unknown Merchant") if null | single field in MULTI flow | `merchant_unknown` |
| RI builder `:115` item-text fallback | none | none | `item_`+SHA(trimmed); only reachable when trimmed is blank, so it is the constant SHA("") [derived] | n/a | `item_unknown`, no HMAC |
| Dedupe `DedupeJudgeInputBuilder.kt:168` (`sanitizeLabel`, prefixes `merchant`/`source`) | `dedupe_judge` | MERCHANT / SOURCE_APP | SHA(n) | MULTI | `""` (as today) |
| CA `CategorizationAssistInputBuilder.kt:214` (candidates `:196` + history `:208`) | `categorization_fallback` | CATEGORY (name only) | SHA(n) | MULTI | `category_unknown` |
| `RedactionSanitizer.kt:17`. Only production callers are CA `:44`, `:91`, `:204` [V-grep] | `categorization_fallback` | MERCHANT | SHA(n) | (CA flow) MULTI | `merchant_unknown` |
| Warranty `CloudWarrantyExtractionService.kt:303` | `warranty_extraction` | MERCHANT | SHA("Unknown") if blank | SINGLE | `merchant_unknown` |
| RE `ReviewExplanationInputBuilder.kt:44` / `:56` | `review_explanation` | MERCHANT / APP_PACKAGE | SHA(raw, untrimmed) | SINGLE | `merchant_unknown` / `app_unknown` |
| Q-G: `CloudPiiSanitizer.kt:102`, `DefaultCloudPayloadRedactor.kt:67` | `pii_redactor` | MERCHANT | hmac(n), no separation | SINGLE (marker as today) | `merchant_v1_<…>` of "Unknown" (unchanged) |
| Q-G: `CloudPiiSanitizer.kt:93` blank-text fallback | `pii_redactor` | TEXT_FALLBACK | hmac(trimmed) | SINGLE | unchanged |

Delete: dead `sanitizeCloudText` (`CloudReceiptItemCategorizationService.kt:523-540`) [V-brief], plus the private SHA helpers at Dedupe `:186`, RI builder `:118`, Warranty `:392`, and RI service `:581` [V].
A flow is MULTI when its payload needs distinct aliases for ≥2 entities (alias→id mapping or equality comparison). The examples are FQI lists, `cat_` options, CA candidates vs history, and dedupe subject vs candidates. A scope is MULTI if any of its kinds is.

## 3. Uniqueness & collisions (D2)
- Entity per kind: MERCHANT, SOURCE_APP, APP_PACKAGE and CATEGORY use `N(name)`. CATEGORY_OPTION uses `(id, N(name))`.
- Within one session and kind:
  - Equal entities get identical aliases. This holds through memoisation even under intermittent hasher failure.
  - Distinct entities must get distinct aliases.
- Required equalities:
  - FQI list alias == the query/history replacement alias.
  - CA subject merchant == history merchant, and CA candidate `cloudLabel` == history `cloudCategoryName`.
  - Dedupe subject merchant/source == candidate merchant/source.
  - All of these hold because both sides use the same (scope, kind).
- Collision: two distinct entities produce the same alias (a 64-bit truncation collision, about n²/2⁶⁵, or a forced collision in tests). A MULTI session then becomes `UNAVAILABLE(ALIAS_COLLISION)` and follows the hasher-null path. It is never resolved "last wins".
- Duplicate category **names** with different ids are not collisions under CATEGORY. FQI/CA keep today's last-id-wins behaviour, which matches redaction-off parity (INV-H12). `cat_` separates them by id.

## 4. State machines
**Session** (per request):
- `NOT_APPLICABLE → KEYED` on the first successful `alias()`.
- `NOT_APPLICABLE|KEYED → MARKED` when the hasher returns null in a SINGLE scope. The site emits `<prefix>_redacted` (the `UNAVAILABLE_MARKER`, `InstallationSecretHasher.kt:53`) [V].
- `NOT_APPLICABLE|KEYED → UNAVAILABLE(HASHER_UNAVAILABLE|ALIAS_COLLISION)` when the hasher returns null or a collision occurs in a MULTI scope.
- Illegal transitions: `UNAVAILABLE →` anything (sticky), `MARKED → KEYED`, and any public-hash fallback.
- Retry means a new request, a new session, and a fresh hasher query. Null is never cached across requests.
- Process death: nothing is persisted, so the next request re-evaluates.
- Cancellation: `CancellationException` propagates from the suspend builder (never swallowed), and no partial input is used.

**Builder content when UNAVAILABLE** (MULTI; owner: each builder):
- Every alias slot carries `<prefix>_redacted`.
- Every alias→id/name structure is **empty**:
  - FQI: `categoryNames`, `merchantNames`, `categoryNameToIdMap`, `categoryLookupMap`, `categoryAliasMap`, `merchantLookupMap`, `merchantAliasMap`.
  - RI: `cloudCategoryOptions`.
  - CA: `candidateCategories`, `recentTransactionsWithSameMerchant`.
- FQI query/history text: known raw names are replaced by `merchant_redacted`/`category_redacted` (PII scrub as today), so no identity remains.
- `input.cloudAliasState = UNAVAILABLE(reason)`.

**Routing** (owner: HybridRouter + HybridDedupeJudgeService through one shared predicate `CloudAliasRouting.mustUseDeterministicFallback(state)`):
- `UNAVAILABLE`, on any decided route (CLOUD or ON_DEVICE):
  - The request goes to `fallbackFn` / `noOpDedupeJudgeService` once.
  - Neither `cloudFn` nor the on-device function is called, and there is no dedupe failover to cloud.
  - Log one line: `PRIVACY_FAIL_CLOSED` plus `reason.name`.
- Any other state: today's routing is unchanged, including dedupe failover.
- `HybridRouter` gains an optional `aliasStateOf: (TInput) -> CloudAliasState` (default `NOT_APPLICABLE`). It is wired by the Query, ReceiptItem and CategorizationAssist hybrids [V wiring `:22-29` / `:21-27` / `:22-28`].

## 5. Invariants
- **INV-H1** Every cloud-bound identity alias at §2 sites comes from `CloudAliasFactory`/`CloudAliasSession`. Nothing else calls `hmacPrefix`/`versionedPseudonym`. Owner: `domain/privacy/CloudAliasFactory`. Enforced by guard G3.
- **INV-H2** HMAC input is exactly `cah1|scope|kind|entityValue` with `N` applied. Owner: factory.
- **INV-H3** Output is `^(merchant|category|cat|source|app)_v\d+_[0-9a-f]{16}$` or a placeholder/marker from §2/§4. No `cat_` alias full-matches `cat_(\d+)`. Owner: factory.
- **INV-H4** Domain separation: the same `N(v)` under different (scope, kind) gives different HMAC inputs. With a real key the aliases differ. Owner: factory.
- **INV-H5** Stability: the same key, version, scope, kind and value give the same alias within and across requests and process restarts. Different keys give different aliases. Within one session, equal entities give equal aliases (§3). Owner: factory/session.
- **INV-H6** No public hash: no cloud-bound alias equals or derives from unkeyed SHA. A null hasher never produces a hash fallback. Owner: factory. Enforced by guards G1/G2.
- **INV-H7** Blank normalised input produces the §2 placeholder, and the hasher is not invoked. Owner: factory.
- **INV-H8** Cardinality is fixed per scope (§2) and declared only in `CloudAliasScope`. Owner: domain/privacy.
- **INV-H9** In a MULTI scope, hasher null or a collision produces UNAVAILABLE plus the §4 builder content. No collapsed marker ever maps to an id. Owners: session (state) and each builder (content).
- **INV-H10** An UNAVAILABLE input is never passed to a cloud service or to on-device on any route, failover included. It is dispatched to the deterministic fallback with a bounded reason. Owner: routing predicate (HybridRouter, HybridDedupeJudgeService).
- **INV-H11** In a SINGLE scope, hasher null emits `<prefix>_redacted`, the cloud call proceeds, and nothing in the response is resolved through the marker. Owners: Warranty service, RE builder, CloudPiiSanitizer.
- **INV-H12** FQI one-to-one: under KEYED, each category alias maps to exactly one id, and that id equals the id the redaction-off path resolves for the same name. Under UNAVAILABLE all FQI maps are empty, so no category total is computed from AI output. Owner: FQI builder.
- **INV-H13** Within a session and kind, distinct entities get distinct aliases. A detected collision produces `UNAVAILABLE(ALIAS_COLLISION)` (MULTI). Owner: session.
- **INV-H14** Diagnostics and logs carry only `PRIVACY_FAIL_CLOSED` and `CloudAliasUnavailableReason.name`. They never contain raw names, aliases, HMAC inputs, key material or the scope payload. Owner: routing predicate.
- **INV-H15** `localLogHash` appears only as a local log argument in `data/ai/provider`. It never appears in a cloud payload, a stored artifact or an alias. Owner: `domain/common`; enforced by guard G4.
- **INV-H16** No schema change. Alias-bearing source hashes change only on upgrade, rotation, restore or reinstall, or on a single-scope alias↔marker flip, and then produce a cache miss and never a false hit. Owner: `AiArtifactSourceHash` (unchanged).
- **INV-H17** `sanitizeCloudText` and all 4 private SHA helpers are removed, and zero `sha256Prefix` identifiers remain in guard scope. Owner: guard G1.

## 6. Stored data, persistence (D5)
| Artifact | Embeds aliases | Compared | Effect |
|---|---|---|---|
| `AiArtifactSourceHash.forDedupeJudge` (`:16-43`, subject/candidate `merchant`, `sourceLabel`) [V] | yes | yes, `JudgePendingReviewDuplicateUseCase.kt:66` [V-grep] | one-time miss → 1 re-call |
| `forReviewExplanation` (`:45-60`, `merchant`, `packageName`) [V] | yes | yes, `ExplainPendingReviewUseCase.kt:70` [V-brief] | one-time miss; alias↔marker flip → repeated misses (accepted, Q-D) |
| `forReviewCategorizationFallback` (`:145-172`, merchant, `cloudLabel`, `cloudMerchant`, `cloudCategoryName`) [V] | yes | yes, `SuggestCategoryFallbackUseCase.kt:152` [V-grep] | one-time miss |
| `forReceiptItemCategorization` (`:111-143`, merchant, `cloudName`) [V] | yes | none found [V-brief] | none |
| Audit `payloadHash` (SHA of redacted text with `merchant_v1_` tokens) | yes (Q-G) | no [V-grep] | values differ going forward; harmless |
| `forDashboardBriefing`, `forTransactionInsight` | no (raw input, pre-prompt) [V] | n/a | none |

No false hits are possible. Each source hash covers every input field the route consumes. UNAVAILABLE inputs differ from KEYED inputs (markers, empty lists), so when the hasher recovers the result is a miss. The fallback output is a function of the UNAVAILABLE input only (U2).

Key events:
- Version bump → `v<n+1>_` → miss.
- Restore or reinstall → a new Keystore key (`InstallationSecretHasher.kt:19-22`) [V] → miss.
- Legacy SHA-based artifacts are never regenerated or matched.

Schema: **none**. No version bump, migration, backfill or destructive step.

## 7. Privacy rules, local log hash, static guard
- Stored, exported and logged content does not change, except that bounded reason codes are added (INV-H14). Aliases are not exported [UNVERIFIED U7].
- **Local log-hash helper (Q-H):** `fun localLogHash(value: String): String` in `domain/common`. It is unkeyed SHA-256, 12 hex, and documented "local Timber correlation only; MUST NOT enter a cloud payload, stored artifact, alias or diagnostic".
  - It replaces `sha256Prefix` at Warranty `:255`, `:293` [V] and RI service `:328`, `:436` [V-brief].
  - Other `Hashing.kt` callers are out of scope (Q-J).
- **Guard** (FG-ID to be assigned by impact-mapper). Scope: `domain/ai/usecase/*InputBuilder.kt`, `domain/privacy/**`, `data/privacy/**`, `data/ai/provider/**`. Rules:
  - G1: the identifier `sha256Prefix` is forbidden.
  - G2: `MessageDigest` is forbidden except for an explicit baselined allowlist with written justification. The seed entry is `DefaultCloudPayloadRedactor.hashPayload` (the audit content hash). The list never includes alias producers.
  - G3: `hmacPrefix(` and `versionedPseudonym(` are allowed only in `domain/privacy/CloudAlias*.kt`, `InstallationSecretHasher.kt` and `KeystoreInstallationSecretHasher.kt`.
  - G4: `localLogHash(` is forbidden in `*InputBuilder.kt`, `domain/privacy/**` and `data/privacy/**`.
- **Planted-violation tests:** G1, G3 and G4 must each fail on a fixture. A Warranty-style `Timber.w(…, localLogHash(content), …)` must pass.

## 8. RP-15 contract amendments (`InstallationSecretHasher.kt`; proposed wording)
- `:7-10`: "Exactly ONE implementation is bound. ALL cloud-bound identity aliases are produced through `CloudAliasFactory`, which feeds this service a domain-separated input (`cah1|scope|kind|value`). Direct callers other than the factory are forbidden."
- `:25-26`: "Callers MUST NOT fall back to a public hash. SINGLE-cardinality scopes MAY emit the identity-free marker (`<prefix>_redacted`). MULTI-cardinality scopes (≥2 aliased entities mapped back to ids or compared) MUST NOT emit markers to any AI route. They mark the input UNAVAILABLE and are routed to the deterministic fallback with `PRIVACY_FAIL_CLOSED`/`HASHER_UNAVAILABLE`."
- `:31-35`: "…require no migration. Pseudonyms are never persisted as values, but they ARE inputs to persisted, compared AI-artifact source hashes (dedupe judge, review explanation, categorisation fallback). Key rotation, restore, reinstall or hasher unavailability therefore cause cache misses and repeat cloud calls (accepted, Q-D), never false hits."

## 9. Legal paths to add (Privacy / Cloud AI, `LEGAL_PATHS.md:375`; proposal only)
- **Cloud alias:**
  - Path: builder or cloud service → `CloudAliasFactory.session(scope).alias(kind, value)` → `InstallationSecretHasher.hmacPrefix`.
  - Forbidden: `sha256Prefix`/`MessageDigest`/private hash helpers for aliases, direct `hmacPrefix`/`versionedPseudonym`, and collapsed markers in MULTI inputs.
- **Hasher unavailable (MULTI):** the builder sets `cloudAliasState=UNAVAILABLE` → HybridRouter/HybridDedupeJudgeService → deterministic fallback plus a bounded reason. Forbidden: cloud or on-device dispatch, and failover to cloud.
- **Local log correlation hash:**
  - Path: `localLogHash` → Timber argument only.
  - Forbidden: use in a payload, artifact, alias or diagnostic.

## 10. Ownership
| Owner | Owns |
|---|---|
| Privacy segment (`domain/privacy`) | alias rule, encoding, scope/kind vocabulary, cardinality, session state |
| AI segment (`domain/ai/HybridRouter`, `data/ai/provider/HybridDedupeJudgeService`) | dispatch of UNAVAILABLE inputs |
| Each input builder / Warranty service | UNAVAILABLE content (§4) and nothing else |
| `domain/common` | `localLogHash` |

No rule lives in two layers.

## 11. Edge cases
- **Empty data:** no names to alias → `NOT_APPLICABLE`. The hasher is not called and routing is normal. The residual from query text naming unlisted merchants is unchanged (brief).
- **Single-entity MULTI flows:** dedupe with one candidate is still MULTI, because of equality semantics.
- **CA/RE redact without `canUseCloudFor` (CA `:40`, RE `:32`) [V]:** a null hasher with on-device-only settings still sends CA to the deterministic fallback. This is pre-existing shape and resolves with the ai-fixes redaction-source move (Q-E).
- **Intermittent failure within a request:** memoisation plus sticky UNAVAILABLE keep it consistent. Across requests, SINGLE flows flip alias↔marker (cost only). MULTI flows go to the fallback.
- **Key version:** constant per process [UNVERIFIED U1], so no mixed versions occur within a session.
- **Duplicate category names:** FQI/CA keep last-id-wins (parity with redaction off). `cat_` stays distinct by id.
- **Dashboard briefing:** it hashes category names via `sanitizeMerchant` (`DashboardBriefingPromptFormatter.kt:82`) [V], so they get kind MERCHANT under `pii_redactor`. This is a pre-existing quirk and is not changed.
- **`cat_(\d+)` fallback:** a hallucinated `cat_7` from the model still resolves to category id 7 (pre-existing, OD-H5).
- **Not applicable:** currency, FX, rounding, time zones and recurrence (no amounts or dates are touched).

## 12. Test retarget expectations
The 7 files are named by the critique: `FinancialQueryInterpretationInputBuilderTest` (×6), `ReviewExplanationInputBuilderTest` (×4), plus 5 more for the impact-mapper to enumerate (U10).
- Never delete a test. Replace `sha256Prefix`/literal expectations with expectations derived from a fake hasher:
  - a `TableHasher(version, map, recordsInputs)` for exact strings and encoding;
  - an `HmacTestHasher(key)` using real `javax.crypto` HMAC for key and separation properties.
- Every retargeted test keeps its original property (distinctness, mapping back, PII scrub). Each one also adds the format regex (INV-H3) and the check alias ≠ `"<prefix>_"+sha256Prefix(name)`.
- New tests cover:
  - null hasher, per MULTI builder and per SINGLE site;
  - routing for the 3 HybridRouter users and for dedupe both ways;
  - the guard planted-violation tests;
  - the cache-miss-once behaviour.

## 13. Acceptance examples
Fakes: `T1 = TableHasher(v=1)` and `Null = hasher always null`. Settings: redaction on, cloud allowed for the capability, route CLOUD unless stated.
- **AE-H1 (INV-H2/3/4).** Given T1 with `"cah1|query_interpretation|merchant|Lidl"→"aaaaaaaaaaaaaaaa"` and `"cah1|dedupe_judge|merchant|Lidl"→"bbbbbbbbbbbbbbbb"`. When FQI builds with recent merchant `"  Lidl  "` and Dedupe builds with subject merchant `"Lidl"`. Then `merchantNames==["merchant_v1_aaaaaaaaaaaaaaaa"]` and `subject.merchant=="merchant_v1_bbbbbbbbbbbbbbbb"`. The recorded inputs are exactly those two strings, and neither alias equals `"merchant_"+sha256Prefix("Lidl")`.
- **AE-H2 (INV-H5).** Given `HmacTestHasher(K1)` and `HmacTestHasher(K2)`, both v1. FQI's alias for "Lidl" differs between K1 and K2. Two builds with K1 are byte-identical.
- **AE-H3 (INV-H9/10/12/14, null hasher in FQI).** Given Null, categories `{1:"Food",2:"Rent",3:"Travel"}`, merchants `["Lidl"]` and query `"How much on Food at Lidl?"`. When FQI builds:
  - `cloudAliasState==UNAVAILABLE(HASHER_UNAVAILABLE)`;
  - `categoryNames==[]`, `merchantNames==[]`;
  - all 5 maps are `{}`;
  - `rawQuery=="How much on category_redacted at merchant_redacted?"`.

  When `HybridQueryInterpretationService.interpret` runs, cloud=0, on-device=0 and NoOp=1 calls. There is one log line containing only `PRIVACY_FAIL_CLOSED`/`HASHER_UNAVAILABLE`. The old `{"category_redacted":3}` never appears.
- **AE-H4 (INV-H12).** Given T1 mapping the three category inputs to distinct hex. Then the map has 3 entries with values `{1,2,3}`, and `map[alias("Food")]==1==` the redaction-off `map["Food"]`.
- **AE-H5 (INV-H11, warranty marker).** Given Null and receipt merchant `"MediaMarkt"`. Then the prompt contains `Merchant: merchant_redacted`, the cloud is called once, and no log contains "MediaMarkt".
- **AE-H6 (INV-H11, RE).** With Null: `merchant=="merchant_redacted"` and `packageName=="app_redacted"`. With T1: the inputs are `"cah1|review_explanation|merchant|Lidl"` and `"cah1|review_explanation|app_package|com.bank.app"`, and the outputs are `merchant_v1_<hex>` and `app_v1_<hex>`.
- **AE-H7 (INV-H16, cache miss once).** Given a READY dedupe artifact whose `sourceHash` was built from legacy SHA aliases. After upgrade (T1), the first judge makes exactly 1 cloud call and rewrites the hash. The second judge with unchanged data makes 0 cloud calls (hit).
- **AE-H8 (INV-H10).** Given Null, dedupe route ON_DEVICE, and an on-device fake returning Failure. Then cloud=0, on-device=0 and NoOp=1.
- **AE-H9 (INV-H13).** Given T1 mapping both `…|merchant|Lidl` and `…|merchant|Aldi` to the same hex in FQI. Then `UNAVAILABLE(ALIAS_COLLISION)` and cloud=0.
- **AE-H10 (INV-H3, `cat_`).** Given RI categories `{7:"Food",9:"Food"}`. Then the inputs are `…|category_option|7:Food` and `…|category_option|9:Food`, there are 2 distinct `cat_v1_` names, and neither `matchEntire`s `cat_(\d+)`.
- **AE-H11 (§3 equalities).** In CA, subject `"Lidl"` and history `" Lidl"` get the same alias, and candidate `"Food"` and history category `"Food"` get the same alias.
- **AE-H12 (INV-H7).** RI with `parsedMerchant=null` gives `merchant=="merchant_unknown"`, and the hasher is not called for the merchant.
- **AE-H13 (Q-G).** `CloudPiiSanitizer.sanitizeMerchant("Lidl", true)` records the input `"cah1|pii_redactor|merchant|Lidl"`. With `HmacTestHasher(K1)` the token ≠ the FQI alias.
- **AE-H14 (§7 guard).**
  - Fails: a fixture `"merchant_${n.sha256Prefix()}"` in `data/ai/provider`, `hasher.hmacPrefix(` in an InputBuilder, and `localLogHash(` in an InputBuilder.
  - Passes: a Warranty Timber `localLogHash(content)`.
- **AE-H15 (intermittent).** Call 1 uses T1 and call 2 uses Null:
  - RE source hashes differ, so there are 2 cloud calls (accepted).
  - FQI call 2 makes 0 cloud calls.
- **AE-H16 (empty).** FQI with no categories or merchants and Null gives `NOT_APPLICABLE`, 0 hasher calls and a normal route.

## 14. Non-goals
Per the brief:
- Moving the redaction source to the resolver (ai-fixes follow-up).
- Per-request keys or numbered aliases.
- On-device local tokens.
- Prompt, consent, settings or UI changes; PII regexes; amounts.
- Schema changes.
- Local-only hashes outside guard scope (Q-J).
- The dashboard-briefing category-as-merchant quirk.
- Removing the `cat_(\d+)` fallback.

## 15. Open decisions (human)
- **OD-H1** Route for UNAVAILABLE MULTI inputs. Recommend the **deterministic fallback** (on-device cannot be fed correct alias slots without new local tokens). This changes the brief's "on-device path" wording, so confirm. Alternative: identity-free per-request ordinal tokens so on-device keeps working (later increment).
- **OD-H2** Reason code. Recommend reusing `PRIVACY_FAIL_CLOSED` plus the bounded detail enum. Alternative: new `DiagnosticReasonCode` constants (additive; U8).
- **OD-H3** Q-G granularity. Recommend one `pii_redactor` scope. Alternative: per-`CloudPayloadPurpose` scopes (API churn in `sanitizeMerchant` callers).
- **OD-H4** Accept identity-free blank placeholders (`merchant_unknown`, `item_unknown`, `app_unknown`) replacing hashes of constants. Recommend accept.
- **OD-H5** Leave the `cat_(\d+)` hallucination fallback untouched. Recommend leave and log it as a separate ai-fixes item.
- **OD-H6** Frozen `cah1` and scope/kind literals independent of `AiCapability` names. Recommend yes.
- **OD-H7** `RedactionSanitizer` shape. Recommend that CA uses one `CloudAliasSession` for all its aliases and that `DefaultRedactionSanitizer` becomes a thin delegate taking the session or scope. No deletion; its test is retargeted.

## 16. UNVERIFIED
- U1: `KeystoreInstallationSecretHasher` uses UTF-8, does not cache null across calls, and has a `currentVersion` that is constant per process.
- U2: NoOp/`fallbackFn` outputs are input-independent, and whether they are persisted as READY artifacts.
- U3: no MULTI cloud service is invoked outside the Hybrid services (e.g. `CategorizeReceiptItemsUseCase.kt:170`).
- U4: the RE response never resolves the merchant or app alias.
- U5: whether `AiCapability` has a warranty constant (irrelevant to the frozen ids).
- U6: the assistant chat history or stored responses echoing aliases (history is re-sanitised; `payloadJson` cleared `:113` [V]).
- U7: whether aliases are excluded from backup, export and diagnostics.
- U8: whether `DiagnosticReasonCode` is persisted by name or guarded.
- U9: the Dedupe subject uses `sanitizeLabel` exactly as the candidates do (lines <120 not read).
- U10: the names of the remaining 5 test files.
- U11: whether the `CloudPiiSanitizer.sanitizeText` blank fallback is reachable.
- U12: whether category name uniqueness is enforced.
- U13: install linkability via BYOK API key.
- U14: whether warranty has a compared source hash.
- U15: other `MessageDigest` uses in guard scope (the baseline seed).
