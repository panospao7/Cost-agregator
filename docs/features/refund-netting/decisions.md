# REFUND-NETTING (DR-05): decisions (domain-modeler recommendations, owner item 144)

Owner item 144 approved the behaviour: "net refunds against the purchase". The owner has asked for recommendations to be followed. Each row below is the modeler's recommended default, and the orchestrator adopts it unless the owner overrides it. Only Q-1 and Q-2 need the owner. The spec is `domain-spec.md` in this folder.

| ID | Topic | Decision (recommended default) | Why |
|---|---|---|---|
| D-1 | Storage | A new `expense_refund_links` table is the source of truth. A denormalised `expenses.refundNettedAmount REAL NOT NULL DEFAULT 0.0` is set on both rows. TLC writes both. | Row-local netting means every `effectiveAmount` consumer and every SQL total nets with no join. This copies the `spendingExclusionReason` precedent. The table allows many-to-many later with no schema bump. A column-only design or a reused `spendingExclusionReason` was rejected (spec section 0). |
| D-2 | Income double count | The applied part of a linked DEPOSIT is excluded from every income total, because it uses the same formula as the purchase side. Any excess over the purchase stays income. | Otherwise the net would gain the refund twice: once as lower spend and once as income. |
| D-3 | Which period and category absorb a refund | The purchase's. Retroactive changes to closed months are accepted. Sent budget alerts are not withdrawn. Accepted savings-sweep months are not re-run. | This is what "net against the purchase" means. |
| D-4 | Auto-link | None in v1. `RefundLikelihoodDetector` only suggests; the user confirms (the picker and confirm). Revisit later using bounded confirm and decline counts. | No unique exact-match rule can be justified. Same merchant, same amount and 90 days also matches cashback, gift-card loads and deposit returns. A wrong auto-link silently moves money on two rows. An automatic path would also add post-commit and lifecycle surface. |
| D-5 | Cardinality | A refund links to at most one purchase in v1. A purchase can take many refunds. Pairs are unique. | Covers partial and multiple refunds. Splitting one refund across purchases is rare; the schema already allows it later. |
| D-6 | Applied amount | Always `min(remaining purchase, remaining refund)` at the currency's scale (HALF_EVEN, BigDecimal). No user-edited amount in v1. | Fewer states. The cap gives "never negative" by construction. |
| D-7 | Cross-currency | Refused (`CURRENCY_MISMATCH`). For the same foreign currency, the home value uses the purchase's snapshot rate (the purchase date), never the refund date's rate. | No FX in linking. A full refund nets to exactly 0 in both native and home terms. |
| D-8 | Transactions list | The list amount, sort, min/max filter and assistant `RawEffective` stay gross. The purchase gets a "Refunded {x}" chip and the deposit an "Applied to {purchase}" chip. The day header nets (decision 131 precedent). | The rows stay recognisable. The parity-tested CV code is not touched. |
| D-9 | Counts | `SPENDING_PREDICATE` and `countsAsSpending` are unchanged. A fully refunded purchase still counts as one purchase, at 0. | Smallest blast radius: only sums change. |
| D-10 | Edits | On a linked row (either side), amount, currency and type are locked (`EXPENSE_LOCKED_BY_REFUND_LINK`). Unlink first to change them. Merchant, date, category, notes and ownership stay editable. | Keeps INV-1 and INV-5 true with no recompute-on-edit path. |
| D-11 | Not-mine and shared purchases | Allowed. The share applies to the net gross: a percentage keeps its percentage, and a fixed share is scaled by net/gross. A not-mine purchase stays at 0, and its refund leaves income. | One formula, defined for every ownership state. |
| D-12 | Installment and group rows | Refused. The installment plan's REFUNDED flow owns installment refunds. `GroupTransactionCoordinator` refuses to group-link a refunded row. | No rule in two layers, and group balances stay gross. |
| D-13 | Dates | The refund may be at most 1 calendar day before the purchase (device zone). The candidate window is 90 days, the detector's `WINDOW_DAYS`. | Allows for posting lag. The window only bounds suggestions. |
| D-14 | Backup | `expense_refund_links` is TIER_1_EXACT from schema 153, and pre-153 sources expect 0 rows. The orphan and consistency checks fail closed. The `SPENDING_EFFECTIVE_BY_CURRENCY` hash changes, so pre-153 manifests are compared using a pinned legacy hash. This is valid because every migrated row has `refundNettedAmount = 0.0`. The fallback is the existing DEFINITION_CHANGED. `INCOME_EFFECTIVE_BY_CURRENCY` stays on the inner constant. | Keeps semantic verification for existing user backups. |
| D-15 | Export and import | The generic CSV and JSON exports get a trailing `refundNettedAmount` column, and `effectiveAmount` is exported net. The accounting exporters inherit the net amount and skip rows that bear nothing. Link identity is not exported. The importers ignore the column (amount first) and never create links. | Explains the net in exports with no new content. |
| D-16 | Cash-flow day series | The refund's cash effect is attributed to the purchase day. The range total is unchanged. | One rule. No cash-timing exception. |
| D-17 | UX surface | The likely-refund notice gets "Apply to purchase", and every eligible DEPOSIT gets it in its row overflow. A picker (ranked candidates) leads to a confirm showing the applied amount. Chips on both rows, and "Remove link" on the deposit. Strings in en, with el and es as DRAFT. | Minimal v1: link, netting, confirm. No parsing changes. |
| D-18 | Backfill | None. Existing refund deposits stay unlinked until the user applies them. | No automatic historical writes. |
| D-19 | Installments and refund links are mutually exclusive | Both directions. (a) Linking is refused (`INSTALLMENT_MANAGED`) when either row carries a spending-exclusion flag, is an installment plan purchase record, or is the linked expense of a plan entry. (b) `TransactionLifecycleCoordinator.setSpendingExclusionDbOnly` refuses to set any exclusion flag on a row with `refundNettedAmount > 0` (`SpendingInclusionResult.RefundLinked`); installment declare and the REFUNDED plan path then fail with the bounded `InstallmentOpFailure.REFUND_LINKED` and roll back, while reconcile repairs skip the flag. Clearing a flag is never refused. Owner item 143/144 default (strict-review iteration 1, MAJOR-2). | One of two owners removes a row's money, never both. Otherwise a plan purchase that is excluded AND netted would be removed twice. Sharpens D-12. |

Constraints:

- Schema 152 to 153, additive only, with no destructive step.
- No new permission. No cloud use.
- Parsers and the notification pipeline are unchanged.
- Expense writes go only through TLC. `insertAtomic` stays the only insert.

## Owner questions (minimal)

- **Q-1.** el and es strings for D-17 need sign-off (the standard DRAFT process). Nothing is blocked meanwhile.
- **Q-2.** Please confirm that item 144 covers two things:
  - schema 153, which is additive: one table and one column;
  - the new `db_access` classification and `DbGuardPolicyFixtureTest` rows for `ExpenseRefundLinkDao` and `ExpenseDao.updateRefundNettedAmount`, both TLC-only restricted mutations.

  This adds rows and weakens no guard. The scoping notes already assumed 152 to 153.

Proposed slices are in spec section 10 (S1 schema, S2 formula, S3 write path, S4 UI and export). Each is money or schema work: a Sonnet writer plus a separate test author, and reviewer-strict at the merge.
