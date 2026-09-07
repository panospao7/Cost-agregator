# RP-00 — Decision Register (blocking decisions)

> These decisions gate scope of later packages. Each has options + recommendation. Nothing here requires code; each decision should be recorded by the stakeholder (a one-line answer per ID is enough).

---

## D1 — Bank sync in release builds (gates RP-17)

**Fact:** `BankApiIntegration` is `requireStubMode()`-gated (`BuildConfig.DEBUG`). In release, any sync throws `IllegalStateException`, which the coordinator maps to `RetryableFailure` and `BankConnectionsViewModel` drops silently. The UI (connections screen, icon that shows "syncing" forever) ships in release today.

| Option | Meaning |
|---|---|
| A (recommended) | Hide the bank-sync surface in release (`BuildConfig.DEBUG` flag on the nav destination / settings entry, mirroring the Debug screen gating at `MainActivity:844-853`). RP-17 then fixes the debug-only cluster without release pressure. |
| B | Keep the surface visible and add a "coming soon" state. |
| C | Productize a real provider now (OAuth/PKCE work — P10-P1-02). Large; out of scope for remediation. |

**Recommendation:** A now, C later as a feature project. RP-17 proceeds either way for the debug cluster; option A removes its release-severity tail.

## D2 — Email ingestion wiring (gates RP-18 scope)

**Fact:** `EmailReceiptIngestionService` has zero production callers; `saveEmailReceiptTyped` is never invoked; docs claim shipped ("✅ Done" `ReceiptRepository.kt:374:386`; ARCHITECTURE.md F14). There is also no mailbox/IMAP/provider integration anywhere — "wiring" needs a real ingestion source (manual forward-in address, or provider sync = a feature project).

| Option | Meaning |
|---|---|
| A (recommended) | Declare F14 **staged**: fix the parser defects now (they're verified real and unit-tested), correct the docs (`ReceiptRepository` delegation row, ARCHITECTURE.md), leave service unwired behind a `TODO(F14-wiring)` note on the class KDoc. |
| B | Wire a minimal manual path now (e.g. share-into-app .eml/text ingestion from the receipt screen). Small-medium feature; needs UX decision. |

**Recommendation:** A. RP-18 then covers parsers + docs only; the defects are fixed and tested before any wiring happens.

## D3 — CSV import product status (gates RP-19 import scope)

**Fact:** Import UI exists only in the debug screen; release builds ship exporters with no import path at all (`MainActivity:844-853` gates the debug destination).

**Options:** (a) keep debug-only, fix importer correctness anyway (RP-19 does — the roundtrip contract is real and tested); (b) promote a first-class import entry point (feature work).
**Recommendation:** (a) for remediation; (b) as a separate feature decision. Either way RP-19's importer fixes are required (the exporter/importer contract is broken today).

## D4 — Dead dangerous API deletions (gates RP-20)

Approve deletion (each verified zero compilable production callers; some have tests pinning non-use):
1. `NotificationRepository.deleteAll()` (`data/repository/NotificationRepository.kt:247-258`, event-less wipe).
2. `ExpenseDao.updateMerchantForMerchant` (`data/database/dao/ExpenseDao.kt:337` — leaves dedupeKey stale).
3. `ProcessReceiptUseCase` (whole class — zero production callers, contains the EUR remnant).
4. `RecurringPlanProjectionService.projectFromRule` **method only** (dead, buggy — verified: the service itself is live via `projectFromOccurrencesInCurrentTransaction`; do NOT delete the class).
5. `ImportCoordinator` (`util/ImportCoordinator.kt` — zero consumers, lossy mapping).
6. `BankConnectionsViewModel.refresh()` (immortal-collect bug, zero callers).
7. `CloudWarrantyExtractionService` private sanitizer copies (:297-318, dead, stale regexes).
8. `EmailReceiptIngestionServiceTest` fix keeps (already applied); `captureForRetry`'s REPLACE policy → change to KEEP or document unreachable (RP-10).

**Recommendation:** approve all; each deletion is its own tiny commit with the zero-caller verification (grep) quoted in the message.

## D5 — `RawPersistencePolicyResolver` fate (privacy consolidation)

**Fact:** the resolver implements the per-source storage-mode matrix but has zero production callers; each write site implements the logic inline (notification pipeline, bank processor, coordinator email path).

**Options:** (a) wire the three write sites to the resolver (single source of truth; medium refactor, strict-privacy review); (b) delete the resolver and keep inline (accepted drift risk).
**Recommendation:** (a) — the resolver already has tests (`RawPersistencePolicyTest`) and the inline copies are where drift bugs come from. Fold into RP-15 as its first step.

## D6 — Notification identity policy (informs RP-16/P9-001)

Adopt `NotificationIdGenerator` as the **only** ID source for all `notify()` calls: extend the BILL range (or add a DELIVERY range) so `forBill(delivery.id)` fits; add an architecture guard test asserting `NotificationManagerCompat.notify`/`notify(` call sites use generator-produced IDs. Capacities: ranges are mod-9999 — acceptable (collisions *within* a type are the old id-replacement semantics, intentional).

## D7 — Recurring month-end correction jump (informs RP-04/P4-005)

Fixing anchor drift will make e.g. a rule currently materializing on the 28th (drifted from 31st) jump **back** to the 31st on its next occurrence. Past PAID rows keep their (drifted) dates; no dedupe collision (keys are date-embedded). **Approve the jump** (it restores user intent). Alternative (keep drifted day) rejected — it permanently encodes the bug.

## D8 — Suite triage timing (informs RP-21)

The ~178-failure unit suite is not a gate until triaged. Approve RP-21 as a separate work item (classify: environment-sensitive / outdated expectation / real regression) **after** the remediation PRs land, so each PR's own test changes are reviewed on a stable base.

---

## Answer format for the stakeholder

```
D1: A | B | C
D2: A | B
D3: a | b
D4: approve all / list exceptions
D5: a | b
D6: approve / adjust
D7: approve / adjust
D8: now / after remediation
```
