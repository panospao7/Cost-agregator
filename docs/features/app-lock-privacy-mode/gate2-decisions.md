# Gate 2 decision sheet — app-lock-privacy-mode

Compiled by the feature orchestrator (2026-10-16) from `domain-spec.md` (OD-1..OD-21, after AMD-1..16),
`impact-map.md` (§5 owner-approval items, §7, §8, §9) and `ux-flows.md` (§12-13). Recommendations are the
authoring agents', not the orchestrator's. Status: **DECIDED 2026-10-16** — owner: "accept all reccomendations" (verbatim). Every row in §A and §B resolves to its Recommendation column; §C approvals remain to be requested at the stated time; §D escalations stand.

Tier: **T3** for all three increments (impact-map §8). Schema impact: **none** (no Room change; lock
settings + PIN data device-local, excluded from `.costbackup`). Implementation order after Release 1.0:
R17/R21/R24 → 1a → 1b → 2.

## A. Product decisions (owner)

| # | Decision | Options | Recommendation |
|---|---|---|---|
| OD-1 | Build Inc 2 app PIN (household / PIN-knowing partner)? | (a) build last as an option; (b) drop | (a), only with OD-15a, OD-16a and the OD-19 escalation |
| OD-2 | Discreet notifications while lock on | (a) forced; (b) default on, user can turn off | (a) |
| OD-4 | Ship 1a ahead of 1b regardless of C4 | (a) yes; (b) wait | (a) |
| OD-5 | API 31+: bill Snooze/Dismiss require phone unlock | (a) always, from 1a (visible change for **all** users → release note); (b) only when discreet or lock on | (a) |
| OD-6 | Notifications already on screen when discreet/lock is turned on | (a) silently re-post them as discreet; (b) leave them, say so in copy; (c) cancel them | (a) (mechanic beyond brief list, S1) |
| OD-7 | Link received while locked expires after | (a) 5 min; (b) until used/process death; (c) dropped on background | (a) |
| OD-8 | Weakening changes (e.g. longer timeout, lock off) need fresh unlock | (a) yes; (b) only enabling | (a) |
| OD-9 | After re-lock, keep the screen you were on and unsaved input | (a) preserve; (b) discard | (a) |
| OD-10 | Discreet text detail | (a) kind only ("Bill due"); (b) kind + severity | (a) |
| OD-13 | App PIN format | (a) 6–12 digits, reject 111111/123456; (b) 4+ | (a) |
| OD-14 | Wrong-PIN delays | escalating schedule in spec §6.2, never wipe data | as written |
| OD-15 | PIN data unreadable (e.g. after phone-to-phone transfer) | (a) hard lock → recovery = clear app data / restore exported backup; (b) fall back to phone unlock | (a) — (b) lets a phone-PIN holder bypass |
| OD-16 | Fingerprint in PIN mode | (a) a newly enrolled fingerprint disables it; (b) any fingerprint works | (a) |
| OD-17 | Bill actions in PIN mode | (a) keep (phone-unlock-required on 31+); (b) remove | (a) |
| OD-18 | App PIN below Android 11 | (a) no; (b) yes | (a) |
| OD-20 | Discreet notifications + screenshot blocking offered on Android 8–10 | (a) yes; (b) Android 11+ only | (a) |
| OD-21 | Lock-screen version under "hide sensitive content" | (a) one generic text for all kinds ("Reminder — open the app"); (b) kind text ("Bill due") | (a) — literal brief v2 rule |
| NEW-1 | 6th notification kind: receipt auto-match (merchant name in text today, `ReceiptMatchingWorker.kt:108`) | (a) add RECEIPT_MATCH kind; (b) leave as budget kind | (a) (impact-map headline 1) |

## B. Technical decisions (reviewers decide; owner informed)

| # | Decision | Options | Recommendation / owner |
|---|---|---|---|
| OD-3 | Manifest | (a) remove BROWSABLE only; (b) also VIEW filter | (a) now; revisit (b) after explicit PendingIntents |
| OD-11 | Settings store | (a) dedicated device-local store; (b) extend `PrivacySettings` | (a) — architecture-guardian |
| OD-12 | Diagnostics sink | (a) durable writer, bounded codes, rate-limited; (b) memory only | (a) — note codes travel in exported backups (privacy-security-guardian) |
| TECH-1 | Fingerprint prompt API | androidx.biometric (needs `MainActivity` → `FragmentActivity`, new dependency, strip `USE_FINGERPRINT`) vs platform `BiometricPrompt` (API 30+ fits; no host change, no dependency) | architecture-guardian + privacy-security-guardian at plan stage |
| TECH-2 | `NotificationService` contract (non-suspend vs bounded settings read; localized constants not in pure domain) | suspend interface / caller-resolved decision / cached snapshot | feature-planner proposes; architecture-guardian decides |
| TECH-3 | Re-post kind detection: budget notifications post with raw `budget.id.toInt()` (`BudgetMonitor.kt:354,365,376 → :474`), not `NotificationId.forBudget`, so id ranges cannot identify kind for budgets ≥10000 (label only; no content leak) | (a) stamp a hidden kind marker at post time; others in spec §13.1 | (a) per domain-modeler — guardians at plan stage (added post-Gate 2 by spec AMD-20/21; technical, not a product decision) |

## C. Approvals needed later (not now)

| # | Item | When |
|---|---|---|
| APP-1 | Any guard / baseline / allowlist addition, incl. spec §8.3 proposed guards (guard registry frozen until 2026-12-01) | at plan stage, per slice |
| APP-2 | `db_ownership_policy.yml` amendment if `WarrantyExpirationWorker.deliverReminder` changes shape | at plan stage |
| APP-3 | Emulator images API 30 / 31 / 33 / 34 for device tests (only API 26 and 35 approved today) | before validation of 1b |
| APP-4 | Release note for OD-5(a) | at 1a release |

## D. Escalations outside this feature

| # | Item | Route |
|---|---|---|
| OD-19 / C5 | Phone-to-phone transfer may copy app data despite `allowBackup=false` (targetSdk 35); found independently as HZ-06 F1 (P3, NEEDS_RUNTIME, deferred 1.0.x) | Release track / privacy-security-guardian (app-wide `dataExtractionRules`); Inc 2 protection relies on OD-15a until decided |
| C6, C7 | `notify()` without `SecurityException` handling; merchant+amount in `Timber.i` log | Release track (see `docs/features/_discovery/opportunity-scan/release-suggestions.md`) |

## E. Scope-growth flags (out of scope unless owner adds)
S1 (OD-6 re-post mechanic, included only if OD-6a), S2 in-app Snooze/Dismiss for Android 11 lock users, S8 "last exported backup" indicator, S9 in-app erase, S10 app PIN below Android 11, S11 app PIN on phones without a screen lock.

## F. Pending doc follow-up after decisions
`ux-flows.md` updates requested by the spec amendment: generic public text pair (if OD-21a) in §2.7/§9.4; PIN-mode turn-off copy (§2.2c, §3.3); review-settings card "on" save is not a separate enable flow, paused → only "off" (§2.1); App PIN row unavailable while paused (§2.1). Briefing discreet title wording mismatch (spec vs ux) to settle in the same pass.
