# Verification — placeholder data + bank UF-20, UF-21, UF-22, UF-30 (2026-10-22)

Source: read-only Sonnet scout pass (persisted by orchestrator). Confidence high for UF-20/21/30,
medium-high for UF-22 (router + strings traced, on-device services not).

## Cross-cutting facts
- Manifest has `INTERNET`, `ACCESS_NETWORK_STATE` (`AndroidManifest.xml:27-28`). OkHttp clients exist only
  for cloud AI (`data/ai/provider/Cloud*`), geocoding (`data/location/*`), currency rates
  (`CurrencyRatesRepositoryImpl`), merchant categories (`MerchantCategoryProvider`). **No client for prices,
  coupons, deals, market rates or banks.**
- `DeferredFeatureGate.DEFERRED_FEATURE_IDS` = tax, investment, challenges only (`DeferredFeatureGate.kt:21`).
  **Price Protection (`price`) and Bill Negotiation (`negotiation`) are ungated and visible in release**
  (`FeatureConfig.kt:67-82`; `MainActivity.kt:778-784`).
- `planning-finalization-handoff.md:32`: "Greece/EUR only for 1.x; wider markets parked".
- `docs/features/_discovery/opportunity-scan/portfolio.md:182` already recommends folding Price Protection +
  Negotiation under one "Save on bills" entry, hidden for non-EUR users.

## UF-20 Price Protection — CONFIRMED, worse than audit
- Real: RETAIL/EMAIL receipts last 30 days (`PriceProtectionTracker.kt:63-76`), item parsing + protectable
  keyword match (`:113-124`), eligibility window (`:126+`), tracking toggles.
- Simulated / invented:
  - Price drops `:281-291` (0.92/0.95× if name contains "electronics"/"appliance"; `isSimulated=true` `:272`; chip `PriceProtectionScreen.kt:263-277`).
  - Better deals `:331-343` (any item >100 → "Competitor Store" 0.85×, `example.com/deal`; chip `:696`).
  - Coupons `:346-364` ("SAVE10" for every merchant — tautological filter; chip `:777`).
  - **Credit-card benefits `:367-432` — invented cards, keyword lists include bare "ab"/"bp"; `CreditCardBenefitCard` (`Screen.kt:812-868`) has no `isSimulated`, NO badge.**
  - Return windows `:236-247` hardcoded merchant map (WRN-22; `ReturnWindowDao`/entity exist, used by warranty, not wired); `price_return_label` (strings.xml:1493) shown as fact.
  - "Price protection active for 30 days" (strings.xml:1476, constant `:270`); static claim URLs `:293-306` (Walmart link questionable).
- "SIMULATED" badge is a hardcoded English literal (`Screen.kt:270,702,784`), no es/el.
- **needs-confirm (money):** `PriceDropCard` (`:294`) formats `purchasePrice` with `homeCurrency`, item carries `purchaseCurrency` (WRN-26).
- Fully done: real prices/deals/coupons need external API + product IDs (GTIN/SKU) + ToS — not feasible local-first.
  Local-only candidates: return-window tracker via `ReturnWindowDao` (overlaps warranty), repeat-purchase price-change
  detection, user-entered card benefits (new schema).
- Options: (1) complete properly L, not recommended; (2) honest local-only rebuild M-L; **(3) hide in release via
  DeferredFeatureGate S** (add "price" id, save tokens, `isDeferredDestination`, MainActivity branch); (4) badge/strip S
  (remove invented cards + SAVE10, badge to string resources); (5) remove M.
- Recommended: **(3) now**, (2) later as its own feature; (4) as cheaper interim.
- Track A (hide) → later B/C (rebuild). Tier T1 hide; T2–T3 rebuild (schema). No approved-plan overlap.
- Owner decisions: hide vs local rebuild; user-editable ReturnWindow; user-entered card benefits; drop fake claim URLs.

## UF-21 Bill Negotiation — CONFIRMED, mechanism corrected
- Real: subscriptions via `RecurringExpenseRepository`, price history, `recordNegotiationOutcome`, scripts.
- Static: `StaticMarketRateProvider.kt:19-82` (EUR/GR seed, `lastUpdatedAt=0L`, source/confidence never shown); bound `NegotiationModule.kt:17-19`.
- **Corrected mechanism:** `SmartBillNegotiationEngine.kt:43-51` silently skips any subscription whose own currency ≠ EUR
  (Timber log only), then calls `getRates(region="GR", currency="EUR")` hardcoded (`:96-100`). Per-subscription, not home currency.
  All-non-EUR users see "already at competitive rates. Great job…" (`bill_negotiation_empty_subtitle`) — **false claim**.
- Quirk: no provider match → fallback `minBy { competitiveMonthlyPrice }` (`:119-124`) compares any streaming sub to the cheapest of Netflix/Prime/Disney+.
- R19 "negotiation label" claimed in `opportunity-scan-handoff.md:57` **did not land** (no "reference/approximate" string).
- Minimal honest fix (S): static "reference prices for Greece in EUR, approximate, not live" note; report skipped non-EUR
  count from engine + honest empty state; optionally gate the matching fallback (T2 business-rule edge); en/es/el strings.
- Later: user-entered comparison price (new table → migration, M). Live provider L, not recommended.
- Track A. Tier T1 (label/strings/skip reporting), T2 for fallback change. Greece/EUR-only for 1.x already owner-answered.
- Owner decision: keep vs hide for non-EUR users.

## UF-22 AI settings labels — CONFIRMED, cosmetic/partial
- `ai_section_experimental` = "Phase 4A Experimental" (strings.xml:1979), English only (no es/el), holds three real toggles.
- `ON_DEVICE_IMPLEMENTED_CAPABILITIES` (`DefaultAiCapabilityRouter.kt:343-354`) excludes `WARRANTY_EXTRACTION`, `LOCATION_SUMMARY`;
  `:188` "On-device AI is not implemented yet for this capability." English literal from domain; router reasons `:188-203,234-241` all literals.
- ai-fixes overlap: B1a/B1c resolve warranty row + caption; B3b relabels proactive toggle but "Move nothing" — **"Phase 4A" not
  removed by any slice** (grep confirms). Router English reasons not covered.
- Recommended: fold section-title rename into **ai-fixes B3b** (same file/strings; scope growth → owner approval). Router reason
  strings → separate AI/privacy-reviewed pass (T3, privacy-security-guardian).
- Owner decisions: new section title wording; approve B3b scope +1 string pair.

## UF-30 Bank connections — CONFIRMED debug-only demo stub, correctly gated
- Real: `BankConnection` entity (encrypted tokens, expiry, autoSync, counters), `BankConnectionDao`, `BankTokenCipher`
  (Keystore AES-GCM), `BankConnectionLifecycleCoordinator`, `BankSyncStartupRecovery`, dedupe by hashed provider id, PendingReview
  routing (0.75), raw-storage privacy modes.
- Stub: `BankApiIntegration` `@StubForDemo` methods (`:168-199,545-554,834-878`), `requireStubMode()` errors in release,
  placeholder `SUPPORTED_BANKS` (`:143-150`); no HTTP/OAuth/aggregator/sync worker; add-connection → `navigateBack()`.
- Hidden: `BankFeatureAvailability` (`:29-33`), menu dropped, MainActivity redirect (`:818-836`), deep-link hosts rejected.
  Bank **statement import** (PDF/CSV) is a separate real feature and is the existing local-only substitute.
- Real integration = owner decision: PSD2/AISP licence vs aggregator (GoCardless/Tink), OAuth+PKCE, 90-day reconsent,
  secrets require backend (conflicts local-first), privacy policy + Play Data Safety + GDPR, new sync worker
  (`WorkerGuardStaticVerificationTest` asserts exactly 10 work names).
- Recommended: keep hidden (no action); FEATURES.md truth fix. Track X. Tier T3 if ever pursued. Size L-XL if built.
- Owner decisions: bank sync in scope post-1.x at all; aggregator vs direct; backend tolerance; legal sign-off.

## Scout-ranked order
1. UF-20 hide (or interim strip/badge) — only fake data shown as fact in release. 2. UF-21 relabel + honest empty state.
3. UF-22 fold rename into ai-fixes B3b. 4. UF-30 no action (+ docs). 5. Later: local-only Price Protection rebuild and
user-entered negotiation comparison (schema work).
