# Whole-app coverage and dispatch plan

This is the initial scope design, not a completed audit or a live inventory.
Verify it against the final candidate. Discovery must cover unchanged code too:
Release 1.0 is broader than the money refactor or campaign diff.

## Keep all three historical axes

The earlier campaign uses horizontal = pipeline reach and vertical = shared
engine depth. This kit also uses end-to-end journey depth. Maintain explicit
pipeline, engine, infrastructure and journey columns; do not let naming replace
coverage. Phase 3 is shared-contract analysis; Phase 4 is full feature journeys.

Reuse these historical cell names and cross-reference old reports:

| Cell | Scope | Initial auditor |
| --- | --- | --- |
| P-01 | Notification capture | Sonnet/high; Opus privacy/worker boundaries |
| P-02 | Transaction lifecycle | Opus/xhigh |
| P-03 | Receipt, OCR, statement, matching | Opus/xhigh owner boundary; Sonnet/high parser/UI |
| P-04 | Recurring rules and reminders | Opus/xhigh lifecycle/claim boundary |
| P-05 | Dashboard and synthesis | Sonnet/high; Opus money/unavailable boundaries |
| P-06 | Budget, forecast, cash flow | Opus/xhigh money decisions; Sonnet/high remaining logic |
| P-07 | Backup, restore, recovery | Opus/xhigh |
| P-08 | Privacy, retention, cloud AI | Opus/xhigh |
| P-09 | Worker runtime | Opus/xhigh |
| P-10 | Bank sync and statement import | Opus/xhigh lifecycle/identity; Sonnet/high adapters |
| P-11 | Email receipts | Sonnet/high; Opus privacy/lifecycle boundaries |
| P-12 | Export, import, accounting | Opus/xhigh accounting/privacy; Sonnet/high format/parser |
| E-01 | Currency and time shared surface | Opus/xhigh |
| E-02 | Analytics shared surface | Opus/xhigh normalized contracts; Sonnet/high pure engines |
| E-03 | Merchant/categorization shared surface | Sonnet/high; Opus identity/dedupe effects |
| E-04 | Groups, shared expenses, investment, tax | Opus/xhigh persistence/money; split into bounded slices |
| E-05 | Warranty, subscriptions, offers | Sonnet/high; Opus lifecycle/claim boundaries |
| I-01 | Worker/backup integration | Opus/xhigh |
| I-02 | Persistence and schema | Opus/xhigh |
| I-03 | Dependency injection breadth | Sonnet/high |
| I-04 | Static guardrails and CI | Opus/xhigh; by FG-ID, no weakening |
| I-05 | UI/ViewModel mutation and insight pipelines | Sonnet/high; Opus dangerous writes/quality loss |

Auditing an engine implementation once is not auditing every consumer. Trace all
consumers from `ENGINE_INTERACTION_MAP.md`, confirm source calls, and link to
their pipeline/journey reports. Split shared ownership instead of duplicate
full-file reviews. Opus verifier must inspect the real evidence, not trust a
Haiku/Sonnet summary for safety acceptance.

## Shipped-feature completeness crosswalk

Every segment must have a disposition. The groups below account for all 39
segment IDs, but do not prove completeness. Enumerate current Kotlin/Java,
manifest entries, resources/routes, DI, build variant/ProGuard, schema/SQL and
repo guards after reading the architecture router. Add new/unmapped files.

| Feature/infra group | Segment IDs | Minimum journey or proof |
| --- | --- | --- |
| Manual transaction CRUD, filtering, use cases | 9, 31 | create/edit/delete -> events -> totals; duplicate/conflict |
| Notifications, parsing, merchant categorization, alerts/runtime | 3, 6, 11, 12 | live/deferred intake -> review -> durable expense; permission/retry |
| Receipt scanning, item categorization, matching | 4, 5, 38 | image/OCR -> draft -> review/link; tax allocation and duplicate |
| Recurring rules and bill reminders | 7, 36 | generate -> match/pay -> reminder; retries and period anchors |
| Dashboard totals and widgets | 10 | same data -> totals/drill-down/widgets; unavailable/empty consistency |
| Forecast, budget, cash flow | 1, 2, 13 | budget status/rollover/alerts -> forecast and cashflow; FX/time |
| Analytics, lifestyle, carbon | 8, 22, 27 | period/category/trends -> insights/nudges; units and incomplete data |
| Bank integration | 14 | connect/sync/import -> review -> expense; credential/failure states |
| Investment tracking | 15 | holding/transaction -> valuation/performance; source currency/stale quote |
| Currency and exchange | 16 | supported source/target -> correct basis/rates -> all consumers |
| Tax calculation/reporting | 17 | eligible effective expense -> allocation -> reports/export; conservation |
| Export, backup/restore | 18 | CSV/JSON/accounting/PDF; archive/encryption -> staging/migration/live |
| Location enrichment | 19 | location permission -> enrichment/map -> lifecycle update; no stale overwrite |
| AI, natural-language search, security/API keys | 20, 26, 28 | local/cloud/offline/denied -> result/action; consent/provenance/secrets |
| Splits, shared groups, budget offsets | 21, 24, 25 | split -> group accounting -> settlement -> correct effective ownership |
| Savings nudges, goals, health | 23, 35 | computation -> visible quality -> durable acceptance/history; legacy |
| Warranty, subscription, offers | 34 | receipt/manual -> warranty/return/reminder; subscription price/usage |
| Spending challenges | 37 | start -> progress -> completion; time/ownership/units |
| Diagnostics | 29 | safe controlled events, debug-only access; no sensitive retention |
| DI | 30 | production bindings and feature/worker entry points actually resolve |
| Shared utilities | 32 | every helper with critical consumers; no duplicate money/time policy |
| Config, performance, accessibility | 33 | navigation/state/accessibility, device resources, optimized variant |
| Static guards/CI | 39 | registry/required guards/ratchets enforce actual architecture |

Feature exclusion is a human product decision, not an auditor's shortcut. A
source path with no production caller can be a wiring finding; do not label its
hypothetical runtime behavior as a confirmed user-impacting defect.

## Risk/depth tiers

- T1: monetary integrity, identity/dedupe, persistence, privacy/security,
  mutation/restore/worker boundaries, migrations, backups and guards. Opus/xhigh
  analyzes the owner and real boundary; independent Opus verification and
  real-boundary runtime proof are mandatory. Money conservation and rounding
  need independent fixtures/oracles, not implementation-derived expected values.
- T2: normal feature logic, engine consumers, presentation, navigation,
  subscription/challenge behavior. Sonnet/high discovers; fresh Opus/xhigh
  verifies credible findings and reviews changed contracts/coverage gaps.
- T3: indexing, file/route lists, artifact links, documentation drift. Haiku/
  medium scouts; Sonnet/high confirms facts. Haiku cannot certify T1/T2.

Never spend Opus/max on every cell. Use xhigh for risky reasoning and max only
for a bounded final decision or a particularly unresolved critical problem.

## Dispatch envelope (one cell/slice per invocation)

```text
Mode: delegated | solo
Role/profile:
Requested actual model and effort:
Candidate SHA and fingerprint:
Campaign comparison base / Wave 5 comparison base:
Phase and cell/journey IDs:
Primary files and exact caller/consumer boundaries:
Relevant architecture sections and old finding IDs:
Evidence/result paths:
Read scope:
Write scope: NONE for audit/verification; exact paths for coder/test author
Excluded scope and pending follow-up cells:
Required output/provenance:
Stop rule: return partial on missing proof; no broader phases or validation
```

Verify actual runtime model/effort and reject silent substitution for intended
safety gates. No nested delegation. Use at most two read-only agents on
independent cells; never two coders on the same paths or a verifier who authored
the finding. Do not read real user databases, secrets, raw notifications, OCR,
receipts or credential-bearing logs for model context.

## Inventory and negative coverage

File membership is not source inspection. Record FULL, SCOPED (methods/ranges),
SEARCH-ONLY, or NOT-READ, and boundary/tests/runtime coverage separately. Start
with small slices and explicitly queue unread scope. Preserve refutations and
accepted intended behavior to prevent rediscovery. Generated historical testing
maps and architecture facts are hypotheses until checked against this pin.

When fixes land, list affected cells/consumers and stale evidence. Re-run the
targeted boundary, re-review authored hunks, then refresh the final snapshot
queue. Unchanged cells can retain dated static analysis with a documented impact
assessment; whole-candidate validation must still match the final fingerprint.
