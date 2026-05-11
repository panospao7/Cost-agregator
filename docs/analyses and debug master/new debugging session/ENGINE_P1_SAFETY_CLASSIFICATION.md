# Engine P1 Issues — Safety Classification

> **Purpose:** Categorize remaining ~70 engine P1s as SAFE vs DANGEROUS to fix.

---

## SAFE TO FIX (isolated, no cross-pipeline regression risk)

These can be fixed in one pass without affecting other pipelines:

### Timestamp/Validation Fixes (pattern: set createdAt, check insert result)
| ID | Engine | Issue | Fix |
|----|--------|-------|-----|
| E3-003 | Merchant | MerchantAlias.createdAt remains 0 | Set createdAt=timestamp on insert |
| E4-004 | Groups | GroupMember.joinedAt can remain 0 | Set joinedAt=timeProvider.now() |
| E3-004 | Merchant | MerchantCategoryRepository ignores insert conflict | Return result, check > 0 |

### Atomicity Fixes (pattern: wrap mutation+event in transaction)
| ID | Engine | Issue | Fix |
|----|--------|-------|-----|
| E4-002 | Groups | Lifecycle events not atomic with mutations | Wrap in withTransaction |
| E4-006 | Groups | removeMember balance check + delete race | Wrap in withTransaction |

### Cache/Concurrency Fixes
| ID | Engine | Issue | Fix |
|----|--------|-------|-----|
| E3-006 | Categorization | invalidateAllCaches not mutex-protected | Add cacheMutex.withLock |

### Privacy/Observability
| ID | Engine | Issue | Fix |
|----|--------|-------|-----|
| E1-various | Warranty | Missing lifecycle events for return/expiry | Add event writes |

---

## DANGEROUS TO FIX (shared engines, cross-pipeline impact)

These require careful planning per the Engine Interaction Map:

### Currency/Money Engine (affects: dashboard, budget, forecast, export, analytics)
| ID | Engine | Issue | Why Dangerous |
|----|--------|-------|---------------|
| E2-001 | Analytics | NormalizedAnalyticsInput not enforced | Changes data flow for ALL analytics |
| E2-002 | Analytics | Historical total uses midpoint | Changes dashboard headline numbers |
| E2-003 | Analytics | Historical fallback silent | Changes MoneyAggregate warnings |
| E2-004 | Analytics | Category breakdown FX basis mismatch | Changes category percentages |
| E2-005 | Analytics | TotalsAggregation drops quality | Changes drilldown UI |
| E2-006 | Analytics | Weekly/daily counts wrong | Changes MoneyAggregate metadata |
| E2-007 | Analytics | Monthly totals type-agnostic | **Already fixed (P5-006)** ✅ |
| E2-008 | Analytics | AdvancedAnalytics second data source | Changes advanced analytics cards |
| E2-009 | Analytics | Deprecated getCategoryAnalytics used | Changes ViewModel behavior |
| E2-010 | Analytics | Budget conversion latest rate | Changes budget-vs-actual display |
| E5-002 | Money | MoneyAmount.fromBigDecimal lossy | Fundamental type change |
| E5-003 | Money | CurrencyCode accepts unsupported codes | Could reject existing data |
| E5-006 | Money | MoneyMappers regresses counts | Changes aggregate metadata |

### Merchant/Categorization Engine (affects: dedupe, matching, analytics, recurring)
| ID | Engine | Issue | Why Dangerous |
|----|--------|-------|---------------|
| E3-001 | Merchant | Same normalizedKey silently no-ops | Changes alias behavior |
| E3-002 | Merchant | Repository bypasses AliasLinkResult | Changes API contract |
| E3-005 | Merchant | Cache invalidation inconsistent | Changes categorization timing |
| E3-007 | Merchant | normalizedCanonicalName ambiguous | Changes category resolution |

### Group Engine (affects: shared expenses, budget offsets, dashboard)
| ID | Engine | Issue | Why Dangerous |
|----|--------|-------|---------------|
| E4-003 | Groups | Ownership update dispatches side effects early | Changes transaction timing |
| E4-005 | Groups | Currency policy not enforced at low level | Could reject existing data |
| E4-007 | Groups | Balance uses current member count for historical | Changes settlement amounts |

---

## ALREADY FIXED (verified in current codebase)
| ID | Issue | When Fixed |
|----|-------|-----------|
| E2-007 | Monthly totals type-agnostic | Pipeline 5 P1 fix (getHomeCurrencyPurchaseMonthlyTotals) |
| E1-P0 | Warranty write barrier | This session |
| E4-P0 | Group/Investment write barrier | This session |
| E5-004 | CurrencyCode non-ASCII | This session |
| E5-005 | MoneyAggregate/Bucket NaN | This session |

---

## DEFERRED (fundamental design decisions)
| ID | Issue | Why Deferred |
|----|-------|-------------|
| E5-001 | MoneyAmount uses Double | Requires Long minorUnits redesign across entire codebase |
| E5-002 | fromBigDecimal lossy | Same root cause as E5-001 |
| E5-003 | CurrencyCode accepts unsupported | Needs policy decision (strict vs permissive) |
