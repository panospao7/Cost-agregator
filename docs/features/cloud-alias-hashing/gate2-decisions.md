# cloud-alias-hashing — Gate 2 decision sheet

Prepared by the orchestrator from `domain-spec.md` (OD-H1..H7) and `impact-map.md` §0, §6, §11 (OD-H8..H12). Date 2026-10-16.
Status: DECIDED 2026-10-16 — owner: "Recommended and approved" → every G2-n = Recommended (G2-1 amends Gate 1 Q-C: deterministic fallback, not on-device, for multi-alias flows except receipt items per G2-2).

| # | Decision | Recommended |
|---|---|---|
| G2-1 | OD-H1: key unavailable in a many-names flow (assistant, duplicate check, category suggestion) → plain built-in answer: no cloud AND no on-device AI for that request (on-device would also get broken labels; duplicate check would otherwise fail over to cloud). Amends Gate 1 Q-C wording "on-device path" | **Yes** |
| G2-2 | OD-H8: receipt items on key failure → use the existing id-only category list (`cat_<id>`, store shown as `merchant_redacted`) instead of the plain fallback; on-device keeps working; sends numeric category ids, never names | **Yes** |
| G2-3 | OD-H2: log reason = existing `PRIVACY_FAIL_CLOSED` + small detail enum (`HASHER_UNAVAILABLE`, `ALIAS_COLLISION`) | **Yes** |
| G2-4 | OD-H3: the two older privacy paths share one scope `pii_redactor` (no API churn) | **Yes** |
| G2-5 | OD-H4: blank values → fixed placeholders `merchant_unknown` / `item_unknown` / `app_unknown` (fixes today's "Unknown Merchant" clash) | **Yes** |
| G2-6 | OD-H5: keep the existing `cat_(\d+)` reverse-mapping (it backs G2-2) | **Yes** |
| G2-7 | OD-H6: frozen text scope/kind ids, independent of capability enum names | **Yes** |
| G2-8 | OD-H7: category suggestions use one alias session per request; `RedactionSanitizer` becomes a thin delegate | **Yes** |
| G2-9 | OD-H9: new hashing check excludes, with written reasons, the audit-record hashes (`DefaultCloudPayloadPolicy`, `DefaultCloudPayloadRedactor.hashPayload`, `SensitiveHashingService`); the raw-prompt audit hash itself goes to the privacy backlog (Q-J) | **Allowlist + backlog** |
| G2-10 | OD-H10: assistant: de-duplicate and cap the merchant list (100) BEFORE coding names (avoids hundreds of secure-key calls) | **Yes** |
| G2-11 | OD-H11: when the fallback ran, saved AI-result records show the fallback mode/provider (not CLOUD/ON_DEVICE) | **Fix (small)** |
| G2-12 | OD-H12: new rules live in the existing `privacy` guard as G15..G18 with an in-script allowlist + planted-violation tests; no new guard, no baseline growth | **Yes** |
| G2-13 | Tests (stop-and-ask): retarget literal-alias assertions in `FinancialQueryInterpretationInputBuilderTest` (:112-162, :164-200; re-baseline after ai-fixes C1), `ReviewExplanationInputBuilderTest` (:34-41, :65-77), `PrivacyGateContractTest` (:120-149), and any `KeystoreInstallationSecretHashingTest` exact-pseudonym checks; never delete; ctor-only edits elsewhere | **Approve** |
| G2-14 | Accept one-time cloud re-ask for cached duplicate / review-explanation / category-suggestion results after update, key rotation, restore (Q-D, restated) | **Accept** |
| G2-15 | Slices S0 → S1 foundation → S2 single-name sites + older paths + dead code → S3 duplicates → S4 receipt items + categories → S5 assistant (after ai-fixes C1 and assistant-follow-ups F3) → S6 guard rules + docs | **Approve** |
| G2-16 | Risk tier T3 throughout; privacy guardian + reviewer-strict per slice; no schema change, no migration tests | **Accept** |
