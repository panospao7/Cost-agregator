# RP-18 — Email parser correctness (Pipeline 11, production-unwired)

> **Scope class:** pipeline-isolated; **CODE-REAL but zero production reach** (service unwired — verified exhaustively; see FRESH-P11-003). **Mode:** standard (defects are real and unit-tested; no production risk until wiring exists per RP-00/D2).
> **Files:** `data/email/provider/AppleReceiptParser.kt`, `AmazonReceiptParser.kt`, `UberReceiptParser.kt`, `data/email/EmailReceiptParser.kt` (base), `data/email/EmailReceiptIngestionService.kt`, `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` (email path: fingerprint dedup, messageId handling, PendingReview lifecycle), `data/privacy/DefaultSensitiveHashingService.kt`, `di/PrivacyModule.kt`, docs: `ReceiptRepository.kt:374` KDoc row, `ARCHITECTURE.md` F14 row, `di/EmailIngestionModule.kt` header.
> **PR shape:** 3 PRs — (18a) amount/currency extraction = P11-001, 002, 006, 007; (18b) identity/dedup = P11-004, 005, 008, 010; (18c) semantics + docs = P11-009, P11-003 (docs/tracking half), NEW-P11-002/003 residuals (detectProvider).
> **Note:** the uncommitted Apple regex/canParse work (NEW-P11-2026-001/002) is correct — these plans build on it.

---

## 18a — Amount & currency extraction

### P11-001 — "Total" matches inside "Subtotal" → expense = pre-tax subtotal (HIGH)

**Problem.** `AppleReceiptParser.kt:25` `Pattern.compile("Total\s*([^\n]{1,32})", CASE_INSENSITIVE)` — unanchored `find()` matches the `Total` substring inside `Subtotal` (which precedes Total in every invoice) → group(1) grabs the subtotal amount; tax/shipping silently dropped. Also makes the `:26` "Total Amount" pattern dead in both directions.

**Fix design.**
1. Anchor the primary patterns to line starts and require a word boundary before "Total": `(?m)^[^\p{L}\d]*Total\b\s*:?\s*([^\n]{1,32})` (leading non-letter junk tolerates table markup residue after `cleanHtml`); same treatment for the `Order Total`/`Total Amount` variants — **reorder** so the most specific (`Order Total`, `Total Amount`, `Grand Total`) are tried **before** bare `Total`.
2. Add a negative lookbehind as belt: `(?<!Sub)(?<!sub)total` is fragile — prefer the `^`-anchor approach; verify against the existing corpus fixtures (the uncommitted test set covers order-number extraction; add Subtotal-before-Total bodies).
3. Apply the same specific-before-generic ordering in `extractAppleAmount`'s fallback list (`:156-171`).

**What it solves.** Correct totals for every Apple/Amazon-style invoice with a Subtotal row. **Tests:** corpus: "Subtotal $4.99 / Tax $0.39 / Total $5.38" → 5.38; "Order Total" variant; total-amount-only body.

### P11-002 — Country codes matched as words flip currency (HIGH)

**Problem.** `CURRENCY_INDICATORS` contain bare two-letter tokens — Apple `:66-70` ("US" in USD list; "IT/ES/FR/DE/NL" in EUR), Uber `:81-85` (plus "GR") — matched as bounded tokens on the uppercased body (`containsBoundedToken`, `EmailReceiptParser.kt:204-209`), first-hit wins in USD→EUR→GBP order → the English words "us"/"it" (or `country=US`) flip a £ receipt to USD/EUR. Uber additionally defaults to EUR (`:251`).

**Fix design.**
1. Remove bare two-letter country tokens from the indicator lists; keep symbols (`$`, `€`, `£`), ISO codes (`USD`, `EUR`, `GBP` — bounded 3-letter tokens are safe), and sender-domain mappings (Amazon already does `amazon.co.uk` — extend Apple/Uber the same way: `apple.com`→USD, `apple.com/gr`→EUR etc. as available; Uber domains per region if known, else omit).
2. Order remains symbol → ISO → domain; **no** text-token fallback for 2-letter codes.
3. Uber's EUR default → change to "unknown → skip ingest with `CURRENCY_UNRESOLVED` ParseError" (honest failure beats wrong currency; the review queue shouldn't see phantom-EUR rows — consistent with RP-13's P3-010 decision).

**Tests.** "Contact us…" £ body → GBP; `country=US` param ignored unless domain/symbol agrees; unknown-everything Uber body → typed skip.

### P11-006 — Trailing-annotation capture breaks amount parse → weakest catch-all wins (MED)

**Problem.** `:25-32` capture up to 32 chars after "Total" grabs `€4.99 (incl. 0.79 VAT)` → `parseLocalizedAmount` fails (`AmountUtils.kt:139-143`) → falls through to the `:30` catch-all that matches **any line ending in a currency symbol** (unit prices!) → wrong amount or spurious ParseError.

**Fix design.**
1. Anchor the amount grammar in the pattern (Uber already has an `amountCapturePattern` — Apple oddly doesn't use one; adopt it): capture `(?:USD|EUR|GBP|\$|€|£)\s?\d{1,3}(?:[.,]\d{3})*[.,]\d{2}|\d+([.,]\d{2})` style group instead of free text; let `parseLocalizedAmount` refine symbol/locale on the matched span.
2. Delete or tighten the `:30` catch-all: require the amount to be the line's terminal token (end-of-line or `</td>`) — unit-price lines usually have trailing text; verify against fixtures and pin.
3. VAT-inclusive suffix becomes non-capturing residue — the total itself parses.

**Tests.** "€4.99 (incl. 0.79 VAT)" → 4.99; unit-price-line no longer hijacks; multi-candidate body picks the labeled total.

### P11-007 — Amazon items include summary rows (LOW-MED)

**Fix design.** Port Apple's skip list (`AppleReceiptParser.kt:266-269`) to `AmazonReceiptParser.extractItems` (`:174-197`): skip descriptions matching `(?i)(sub)?total|grand total|tax|vat|shipping|balance due` (plus keep the existing quantity-integer requirement, which already blocks most summary lines). **Tests:** "Order Subtotal 1 $25.00" not an item; real item rows unaffected.

## 18b — Identity & dedup

### P11-004 — Timezone-unstable fingerprint bucket (MED)

**Problem.** Date parse → local-midnight epoch (`EmailReceiptParser.kt:175`); bucket = `date / 3_600_000L` UTC hours (`EmailReceiptIngestionService.kt:402`) → a device timezone change re-splits the bucket → forwarded re-delivery (new messageId) ingests as new. (DST within one zone is deterministic — the trigger is travel/tzdata; `senderDomain` in the fingerprint splits forwarded mail independently.)

**Fix design.**
1. Make the bucket a pure calendar value: bucket key = `LocalDate` string (`"2026-09-07"`) derived at parse time from the **original** zone-agnostic date (store the parsed `LocalDate` alongside the epoch in `ParsedEmailReceipt` — additive field) — no epoch division at fingerprint time.
2. `senderDomain` stays (different-provider forwarded mail deduping is a separate product call — out of scope; note it).
3. Historical rows' fingerprints were computed with the old scheme → after the change, old rows never match new fingerprints (same accepted inert-history note as RP-10a; pipeline is unwired so there is **no production history** — zero-risk change, say so in the PR).

**Tests.** Same email fingerprinted under two device zones → identical.

### P11-005 — Derivable HMAC key + blank-messageId collapse (MED)

**Problem.** `DefaultSensitiveHashingService.kt:25-27` key = `SHA-256("privacy-hmac-key-$purpose")` (doc admits Keystore deferred); `hmacSha256Prefix("")` succeeds → all blank-messageId emails share one hash → second one is a false `Duplicate` (`ReceiptLifecycleCoordinator.kt:762-768`).

**Fix design.**
1. Blank guard at the front door: `EmailReceiptIngestionService` — blank messageId → derive the source fingerprint from **content** only (skip the messageId component) and never call the messageId hash; the coordinator's blank-check then works as intended. (`hmacSha256Prefix` additionally: blank-in → `IllegalArgumentException` — defense in depth.)
2. Key hardening (scoped): keep the deterministic key **but** version-tag outputs (`h1:` prefix) so a future Keystore migration can distinguish generations — full Keystore migration is its own work (note: rotating the key invalidates every stored hash → mass duplicates; the version tag is the prerequisite, the migration is not in this package).

**Guardrails.** Privacy: hashes only, no new persisted content. **Tests:** blank messageId twice with different content → both ingest; version tag present.

### P11-008 — STORE_RAW stores the hash as `emailMessageId` (LOW)

**Problem.** The service passes the HMAC **hash** as `messageId` (`:233`, PRIV-FB58-01) so the coordinator's `STORE_RAW -> messageId` branch (`:864-868`) persists the hash while its comment and `RawContentSanitizer.sanitizeEmailMessageIdWithHash` (`:70-79`) intend plaintext.

**Fix design — decide the honest semantics:** storing the hash everywhere is the *privacy-safer* behavior; the bug is the lying branch/comment. Fix by truthfulness: change the coordinator to always store the hash (`emailMessageId = messageIdHash`), delete the mode-dependent `when` branch, update `RawContentSanitizer.sanitizeEmailMessageIdWithHash` docs to match ("hash is canonical; raw messageId is never persisted"), and rename the coordinator parameter to `messageIdHash` for clarity. The DAO's `getByMessageId` callers (if any non-test) must query by hash — grep and adjust. **Tests:** all modes → hash stored; comment/KDoc consistency.

### P11-010 — NeedsReview is a dead end (LOW)

**Problem.** Re-ingestion short-circuits as `Duplicate` (`:762-777`) without inspecting the existing receipt's status — a parser fix or threshold change can never upgrade a NeedsReview receipt; its PendingReview row persists forever.

**Fix design.** On duplicate-with-existing-`NeedsReview`: re-run the confidence/expense decision against the current parser output (the coordinator has everything in scope — mirror the original `:944-1069` flow but update-in-place: link the existing receipt, resolve/replace its PendingReview row in the same transaction, dispatch post-commit once). Existing `Duplicate` return for non-review rows unchanged. **Tests:** NeedsReview re-ingest with now-parseable content → expense created, review resolved; still-low-confidence → review refreshed, no duplicates.

## 18c — Semantics, routing residuals & docs

### P11-009 — Refunds: second PURCHASE or dropped (LOW)

**Fix design (conservative, consistent with RP-17/P10-005):** subject/sender refund detection (`(?i)\brefund|gutschrift|rimborsa\b`): positive-amount refund email → **typed skip** with `REFUND_UNSUPPORTED` diagnostic (no second PURCHASE); negative amounts remain parse-rejects but now with the same typed reason instead of generic ParseError. Booking refund transactions is feature work (needs the TransactionType/UX decision — flag, don't guess). **Tests:** refund subject → skip + reason; normal order unaffected.

### NEW-P11-002/003 residuals — `detectProvider` body-routing bypasses `canParse` (tracked)

**Problem.** Parser-level `canParse` sender gates landed, but `detectProvider` routes any email whose **body** contains "amazon.com"/"uber.com" to that parser without a sender check (`EmailReceiptIngestionService.kt:333-336`), and the unknown-provider fallback calls all parsers directly, bypassing `canParse` (`:355-362`) — pinned as known-deferred by the uncommitted residual test.

**Fix design.**
1. Body-keyword routing: require **sender-domain OR body** (sender wins; body-only routes to a `provider = UNKNOWN_BODY_HINT` path that still runs the parser but tags low confidence → the existing low-confidence PendingReview flow catches misroutes). Simplest true fix: body-only match → treat as unknown-provider (fallback path) rather than direct provider routing.
2. Fallback path: respect each parser's `canParse` (skip non-matching parsers) — the uncommitted pin-test asserting the residual gets updated to assert the **fixed** behavior instead.

**Tests.** Non-Apple sender + apple.com in body → fallback path (canParse-gated), low-confidence review if parsed; genuine Apple sender → direct route (unchanged).

### P11-003 — Pipeline unwired while docs claim shipped (HIGH as a tracking/docs issue; wiring = D2)

**Fix design (docs half only, per D2-A):**
1. `EmailReceiptIngestionService` class KDoc: add a prominent `STAGED — NOT WIRED: no production entry point (F14 wiring pending decision RP-00/D2)` banner; same on `EmailIngestionModule`.
2. Correct the false records: `ReceiptRepository.kt:374` delegation row "✅ Done" → "✅ parser/service implemented — entry point pending"; `ARCHITECTURE.md:1929` F14 row → "staged"; `backend-di-infrastructure-map.md:471` likewise (REVAL-5).
3. Leave all behavior code untouched (the parsers get fixed by 18a/18b — they run under tests today).

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*EmailReceipt*" --tests "*AppleReceiptParser*" --tests "*AmazonReceiptParser*" --tests "*UberReceiptParser*" --tests "*SensitiveHashing*"
```

## Sequencing & risk
- 18a → 18b → 18c. Risk: minimal production exposure (unwired); the value is that F14 becomes wire-ready with correct parsers, honest docs, and a dedup scheme that won't mass-duplicate on timezone changes when it ships.
