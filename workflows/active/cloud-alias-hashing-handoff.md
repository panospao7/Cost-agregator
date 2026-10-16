# Handoff: cloud-alias-hashing

Status: PLAN APPROVED — PARKED (post-1.0). DOCS-ONLY (Release 1.0 frozen; implementation post-1.0).
Feature-id: cloud-alias-hashing. Program: AI evolution roadmap item 2d (workflows/active/ai-evolution-handoff.md).

## Origin
- assistant-follow-ups critique F-D (docs/features/assistant-follow-ups/critique.md:11, :38): merchant/category cloud aliases are unsalted SHA-256 prefixes (domain/common/Hashing.kt) → dictionary-reversible; contradicts installation-secret HMAC pseudonyms (DefaultCloudPayloadRedactor / InstallationSecretHasher). P2 privacy.
- Paired follow-up ai-fixes PC-10 (docs/features/ai-fixes/gate2-decisions-part-c.md:17): six AI input builders OR redaction flags outside EffectiveCloudAiPolicyResolver.
- Owner 2026-10-16: orchestrator recommended this as top open idea; owner: "Go".

## Stages
| Stage | Artifact | Status |
|---|---|---|
| 1 Brief | docs/features/cloud-alias-hashing/brief.md (v2; v1 = brief-v1.md) | DONE v2 — reshape applied: alias generation only (7 live sites + delete dead CloudReceiptItemCategorizationService.sanitizeCloudText), domain separation, honest residuals, hard skip-cloud rule, redaction-source move → ai-fixes follow-up; Q-A..Q-J, D1..D5 |
| 2 Critique | docs/features/cloud-alias-hashing/critique.md | DONE — RESHAPE (missed site CloudReceiptItemCategorizationService:536; honest threat model + domain separation; drop redaction-source migration → ai-fixes follow-up; stored-data = 3 compared source hashes; Q2 hard rule). Reshape round 1 sent to strategist (a 2nd reshape goes to owner). |
| Gate 1 | (this handoff) | GO 2026-10-16 (owner: "Go") — Q-A..Q-J as recommended |
| 3 Domain spec | docs/features/cloud-alias-hashing/domain-spec.md | DONE — INV-H1..H17, AE-H1..H16, OD-H1..H7, U1..U15; schema NONE. NOTE OD-H1 deviates from Gate 1 Q-C wording: null hasher in multi-alias flows → deterministic fallback (skip cloud AND on-device, incl. dedupe on-device→cloud failover), not on-device — owner must confirm at Gate 2 |
| 4 UX | — | SKIPPED (no UI) |
| 5 Impact | docs/features/cloud-alias-hashing/impact-map.md | DONE — T3; schema NONE; guard home = registered `privacy` guard (G15..G18); S0..S6; S5 after ai-fixes C1 + assistant-follow-ups F3; new OD-H8..H12 |
| Gate 2 sheet | docs/features/cloud-alias-hashing/gate2-decisions.md | DECIDED 2026-10-16 (all recommended) |
| 6 Slice plan | workflows/active/cloud-alias-hashing-plan.md | APPROVED 2026-10-16 (Gate 3, owner "Recommended and approved") — S0, S1, S2, S3, S4a, S4b, S5, S6, Z; PD-1..PD-6 accepted |

## Slice statuses
| Slice | Status |
|---|---|
| S0, S1, S2, S3, S4a, S4b, S6, Z | planned |
| S5 | planned — blocked on ai-fixes C1 + assistant-follow-ups F3 |

## Constraints
- Post-1.0; sequence after ai-fixes (Part C introduces EffectiveCloudAiPolicyResolver for the assistant + C-6 redaction). Not committed.

## Next action
PARKED. Next action when Release 1.0 has shipped: run S0 (scout on Sonnet for the re-verify list; domain-modeler applies the spec amendment set; validation-runner baselines privacy/cloud_payload/pii_logging + architecture-contracts). Record Q-E (5-builder redaction-source move) on ai-fixes follow-up list.

## Human decisions
- 2026-10-16 Gate 1 GO. Owner (verbatim): "Go" → Q-A domain-separated install-stable HMAC (per-request later); Q-B keep warranty merchant_ alias, domain-separated, documented low value; Q-C skip cloud on null hasher in multi-alias flows; Q-D accept one-time cloud re-call for cached dedupe/review-explanation/categorisation-fallback artifacts; Q-E 5-builder redaction-source move → ai-fixes follow-up; Q-F post-1.0, FQI site after ai-fixes C1; Q-G domain-separate CloudPiiSanitizer/DefaultCloudPayloadRedactor tokens if no stored comparison; Q-H named local log-hash helper; Q-I include ReviewExplanationInputBuilder; Q-J local-only hashes → PSG backlog.
- 2026-10-16 Gate 2 DECIDED. Owner (verbatim): "Recommended and approved" → G2-1..G2-16 recommended: OD-H1 deterministic fallback for multi-alias flows (amends Q-C); OD-H8 RI uses existing cat_<id> fallback (numeric ids, no names); OD-H2..H7 as spec; OD-H9 allowlist audit hashes + raw-prompt audit hash to PSG backlog; OD-H10 dedupe+cap before alias; OD-H11 fix fallback mode/provider attribution; OD-H12 privacy guard G15..G18; APPROVED test retargets (FQI builder test :112-162/:164-200 after C1, ReviewExplanation :34-41/:65-77, PrivacyGateContract :120-149, Keystore exact-pseudonym checks; never delete); cache re-ask accepted; slices S0..S6 (S5 after ai-fixes C1 + assistant-follow-ups F3); T3.
