# Implementation and Testing Strategy  
## Forecast Quality Population + CloudPayloadRedactor Migration

Target goals:

```text
1. Forecasts must visibly degrade when data is partial, converted with stale/current rates, or excluded.
2. No cloud AI provider should build/send payloads without CloudAiGuard + CloudPayloadRedactor.
```

---

# Part A — Forecast Quality Population

## 1. Current state

You now have a `ForecastDataQuality` skeleton on `ForecastInput`.

That is good, but currently it is mostly structural. The next step is to populate it from real conversion/exclusion signals.

Core problem:

```text
Forecasts may use normalized actual expenses,
but planned/recurring/future inputs can still be raw or partially converted.
```

The user sees a confident forecast even if:

```text
GBP transactions were excluded
USD rate was stale
planned expense could not be converted
recurring amount had unknown currency
```

---

## 2. Target contract

Every forecast output should know:

```kotlin
data class ForecastDataQuality(
    val isPartial: Boolean,
    val confidencePenalty: Double,
    val issues: List<ForecastQualityIssue>,
    val actuals: ForecastInputQuality,
    val planned: ForecastInputQuality,
    val recurring: ForecastInputQuality,
    val budgets: ForecastInputQuality
)

data class ForecastInputQuality(
    val inputCount: Int,
    val includedCount: Int,
    val excludedCount: Int,
    val staleRateCount: Int,
    val approximateCount: Int,
    val missingRateCount: Int,
    val invalidCurrencyCount: Int
)

data class ForecastQualityIssue(
    val source: ForecastQualitySource,
    val severity: ForecastQualitySeverity,
    val code: String,
    val message: String,
    val currency: String? = null,
    val affectedCount: Int = 0
)

enum class ForecastQualitySource {
    ACTUAL_EXPENSES,
    PLANNED_EXPENSES,
    RECURRING_EXPENSES,
    BUDGETS,
    EXCHANGE_RATES
}

enum class ForecastQualitySeverity {
    INFO,
    WARNING,
    HIGH
}
```

If you want minimal change, keep your current `ForecastDataQuality` and add these fields gradually.

---

## 3. Conversion policy

Use different policies by source.

## 3.1 Actual expenses

Use historical conversion:

```kotlin
convertAsOf(
    amount = expense.effectiveAmount,
    fromCurrency = expense.currency,
    toCurrency = homeCurrency,
    atMillis = expense.date
)
```

If conversion fails:

```text
exclude from numeric forecast input
add quality issue
reduce confidence
```

## 3.2 Planned expenses

Planned expenses are future obligations.

Policy:

```text
same currency → include exactly
foreign currency + current usable rate → include, mark approximate/current-rate
foreign currency + stale rate → include only if policy allows stale, mark warning
foreign currency + missing rate → exclude, mark partial
```

Do not pretend future FX is historical truth.

Recommended issue text:

```text
“2 planned GBP expenses excluded because no GBP→EUR rate is available.”
```

## 3.3 Recurring expenses

For materialized recurring occurrences:

```text
use occurrence.expectedAmount / expectedCurrency / dueDate
```

For detected recurring patterns:

```text
if currency is known → convert with current-rate policy
if currency unknown → exclude or include only if pattern source guarantees home currency
```

Never raw-sum detected pattern averages across currencies.

## 3.4 Budgets

Budget quality should be imported from `BudgetStatus`.

If budget conversion failed:

```text
budget forecast confidence should be reduced
budget comparison should be unavailable/partial
```

---

# 4. Implementation plan

## Phase 1 — actual expenses only

Modify `ForecastInputAssembler`.

Current likely shape:

```kotlin
val normalized = analyticsCurrencyNormalizer.normalizeSnapshots(...)
val included = normalized.includedExpenses
```

Add:

```kotlin
val actualQuality = ForecastInputQuality(
    inputCount = normalized.inputCount,
    includedCount = normalized.includedExpenses.size,
    excludedCount = normalized.excludedExpenses.size,
    missingRateCount = normalized.conversionFailures.count { it.reason == MISSING_RATE },
    staleRateCount = normalized.conversionFailures.count { it.reason == STALE_RATE },
    invalidCurrencyCount = normalized.conversionFailures.count { it.reason == INVALID_CURRENCY },
    approximateCount = normalized.approximateConversions.size
)
```

Then:

```kotlin
val qualityIssues = normalized.conversionFailures
    .groupBy { it.currency }
    .map { (currency, failures) ->
        ForecastQualityIssue(
            source = ACTUAL_EXPENSES,
            severity = WARNING,
            code = "ACTUAL_CONVERSION_FAILED",
            message = "${failures.size} $currency actual transactions excluded from forecast.",
            currency = currency,
            affectedCount = failures.size
        )
    }
```

Set:

```kotlin
ForecastInput(
    ...,
    dataQuality = ForecastDataQuality(
        isPartial = actualQuality.excludedCount > 0,
        confidencePenalty = penaltyFor(actualQuality),
        issues = qualityIssues,
        actuals = actualQuality,
        planned = ForecastInputQuality.empty(),
        recurring = ForecastInputQuality.empty(),
        budgets = ForecastInputQuality.empty()
    )
)
```

Suggested penalty:

```kotlin
fun penaltyFor(q: ForecastInputQuality): Double {
    if (q.inputCount == 0) return 0.0
    val excludedRatio = q.excludedCount.toDouble() / q.inputCount
    val staleRatio = q.staleRateCount.toDouble() / q.inputCount

    return (excludedRatio * 0.35 + staleRatio * 0.10)
        .coerceIn(0.0, 0.50)
}
```

Keep it simple first.

---

## Phase 2 — planned expenses

Add helper:

```kotlin
data class NormalizedForecastAmount(
    val amount: Double?,
    val currency: String,
    val originalAmount: Double,
    val originalCurrency: String,
    val status: ForecastAmountStatus,
    val issue: ForecastQualityIssue? = null
)

enum class ForecastAmountStatus {
    INCLUDED_EXACT,
    INCLUDED_CURRENT_RATE,
    INCLUDED_STALE_RATE,
    EXCLUDED_MISSING_RATE,
    EXCLUDED_INVALID_CURRENCY
}
```

Create:

```kotlin
ForecastMoneyNormalizer.normalizeFutureAmount(
    amount,
    fromCurrency,
    toCurrency,
    dueDate,
    source
)
```

For planned expenses:

```kotlin
val normalizedPlanned = plannedExpenses.map {
    forecastMoneyNormalizer.normalizeFutureAmount(
        amount = it.amount,
        fromCurrency = it.currency,
        toCurrency = homeCurrency,
        dueDate = it.dueDate,
        source = PLANNED_EXPENSES
    )
}
```

Use only included amounts in numeric forecast.

Add excluded/stale/current-rate counts to `ForecastDataQuality`.

---

## Phase 3 — recurring expenses

For materialized occurrences:

```kotlin
val normalizedOccurrences = occurrences.map {
    forecastMoneyNormalizer.normalizeFutureAmount(
        amount = it.expectedAmount,
        fromCurrency = it.expectedCurrency,
        toCurrency = homeCurrency,
        dueDate = it.dueDate,
        source = RECURRING_EXPENSES
    )
}
```

For detected patterns:

```text
if pattern.currency == null:
  exclude and add issue DETECTED_RECURRING_UNKNOWN_CURRENCY
```

If the detected pattern is known to come from already-normalized analytics, encode that explicitly:

```kotlin
pattern.amountCurrency = homeCurrency
pattern.amountIsNormalized = true
```

No implicit assumptions.

---

## Phase 4 — consumers

Update:

```text
SynthesisEngine
FinancialWeatherRepository
FinancialStressForecastEngine
CashFlowCalculator
Dashboard weather widgets
```

Minimum behavior:

```kotlin
val baseConfidence = computedConfidence
val finalConfidence = (baseConfidence - input.dataQuality.confidencePenalty)
    .coerceIn(0.0, 1.0)
```

Add narrative:

```text
if input.dataQuality.isPartial:
  “Forecast is partial: excludes 3 GBP transactions due to missing rates.”
```

Avoid confident wording:

```text
“will exceed budget”
```

when partial. Prefer:

```text
“may exceed budget, but forecast is partial.”
```

---

# 5. Forecast tests

## Unit tests

### `ForecastDataQualityBuilderTest`

Cases:

```text
all included → isPartial=false, penalty=0
missing 1 of 10 → isPartial=true, penalty > 0
stale 2 of 10 → warning, small penalty
missing 8 of 10 → high severity, large penalty capped
```

### `ForecastMoneyNormalizerTest`

Cases:

```text
same currency exact
foreign current-rate included approximate/current-rate
foreign stale-rate included or excluded according to policy
foreign missing-rate excluded
invalid currency excluded
```

---

## DB-backed assembler tests

### `ForecastInputActualQualityTest`

Seed:

```text
home EUR
expense 50 EUR
expense 10 USD with rate
expense 20 GBP without rate
```

Assert:

```text
includedCount = 2
excludedCount = 1
isPartial = true
issue currency = GBP
numeric forecast uses EUR + converted USD only
```

### `ForecastInputPlannedQualityTest`

Seed:

```text
planned 100 USD with rate
planned 50 GBP missing rate
```

Assert:

```text
USD included
GBP excluded
planned.excludedCount = 1
forecast warning appears
```

### `ForecastInputRecurringQualityTest`

Seed:

```text
recurring Netflix 12 EUR
recurring AWS 20 USD with rate
detected recurring unknown currency
```

Assert:

```text
Netflix included exact
AWS included approximate/current-rate
unknown excluded
recurring quality issue exists
```

---

## Scenario test

### `ForecastPartialCurrencyScenarioTest`

Seed:

```text
home EUR
actuals:
  50 EUR
  10 USD with rate 0.90
  20 GBP missing

planned:
  100 USD with current rate
  40 GBP missing

recurring:
  12 EUR
  20 GBP missing
```

Expected:

```text
dataQuality.isPartial = true
actual excluded = 1
planned excluded = 1
recurring excluded = 1
confidence reduced
UI/weather summary includes partial warning
no raw GBP value included in forecast total
```

---

# Part B — CloudPayloadRedactor Migration

## 1. Current state

You added `CloudPayloadRedactor`. Good.

But the interface is not yet used by providers. Existing providers still risk:

```text
building prompt from raw text
logging raw prompt
auditing only gate decision
trusting caller-provided redact flag
```

Goal:

```text
Every cloud provider must be self-defending.
```

Meaning every provider must do:

```text
CloudAiGuard check
CloudPayloadRedactor sanitize
Cloud AI audit event
only then build/send request
```

---

# 2. Target provider flow

Every cloud provider should follow this structure:

```kotlin
suspend fun callCloud(input: RawInput): Result<Output> {
    val permission = cloudAiGuard.check(
        capability = CLOUD_AI_QUERY_INTERPRETATION,
        context = ...
    )

    if (!permission.allowed) {
        cloudAiAuditLogger.logDenied(...)
        return Result.failure(PrivacyDeniedException(permission.reason))
    }

    val redacted = cloudPayloadRedactor.redact(
        purpose = CloudPayloadPurpose.QUERY_INTERPRETATION,
        payload = CloudPayload.RawText(input.query),
        forceRedaction = permission.redactBeforeCloud
    )

    cloudAiAuditLogger.logAttempt(
        provider = providerName,
        capability = ...,
        redactionApplied = redacted.metadata.redactionApplied,
        payloadHash = redacted.metadata.payloadHash,
        rawTextIncluded = redacted.metadata.rawTextIncluded,
        rawImageUploaded = false
    )

    val request = buildRequest(redacted.payload)
    return httpClient.execute(request)
}
```

No provider should build its cloud prompt from raw input after this migration.

---

# 3. Recommended redactor model

If your current `CloudPayloadRedactor` is narrower, evolve it gradually.

```kotlin
interface CloudPayloadRedactor {
    fun redactText(
        text: String,
        purpose: CloudPayloadPurpose,
        forceRedaction: Boolean = true
    ): RedactedText

    fun redactReceiptItems(
        merchant: String?,
        items: List<String>,
        purpose: CloudPayloadPurpose,
        forceRedaction: Boolean = true
    ): RedactedReceiptItems

    fun hashPayload(raw: String): String
}

enum class CloudPayloadPurpose {
    QUERY_INTERPRETATION,
    RECEIPT_ASSIST,
    RECEIPT_ITEM_CATEGORIZATION,
    REVIEW_EXPLANATION,
    DASHBOARD_BRIEFING,
    DEDUPE_JUDGE,
    WARRANTY_EXTRACTION,
    BANK_STATEMENT_VALIDATION
}

data class RedactedText(
    val text: String,
    val metadata: RedactionMetadata
)

data class RedactedReceiptItems(
    val merchant: String?,
    val items: List<String>,
    val metadata: RedactionMetadata
)

data class RedactionMetadata(
    val redactionApplied: Boolean,
    val rawTextIncluded: Boolean,
    val fieldsRedacted: Set<String>,
    val payloadHash: String,
    val originalLength: Int,
    val redactedLength: Int
)
```

Important:

```text
payloadHash = hash of raw payload
```

Do not store raw payload.

---

# 4. What to redact

Minimum patterns:

```text
emails
phone numbers
IBAN
card numbers
long account-like numbers
postal addresses where detectable
person names where detectable
merchant names if policy requires hashing
exact free-text query
raw receipt OCR lines
raw bank statement lines
```

Use existing `CloudPiiSanitizer` first. Do not overbuild.

Start with deterministic replacements:

```text
[EMAIL]
[PHONE]
[IBAN]
[CARD]
[ACCOUNT_NUMBER]
[MERCHANT_HASH_ab12]
```

For merchant:

```text
if redaction strict:
  merchant → stable hash token
else:
  leave merchant
```

Be explicit in metadata:

```text
fieldsRedacted = ["email", "iban", "card", "merchant"]
```

---

# 5. Migration order

## Provider 1 — CloudQueryInterpretationService

Highest privacy risk because user free-text queries can contain anything.

Migrate first.

Before:

```text
build prompt from raw query
```

After:

```text
redact query
build prompt from redacted query
audit attempt
```

Tests should capture outgoing HTTP body and assert raw values absent.

---

## Provider 2 — CloudReceiptItemCategorizationService

Currently risky because it may trust caller redaction flag.

Change:

```text
provider resolves effective redaction internally
```

Do not trust:

```kotlin
input.redactBeforeCloud
```

Use:

```text
CloudAiGuard.redactBeforeCloud OR input.redactBeforeCloud
```

---

## Provider 3 — CloudReceiptAssistService

Receipt OCR can contain rich PII.

Add:

```text
redact raw OCR text
block image upload if redaction required
audit rawImageUploaded
```

---

## Provider 4 — CloudReviewExplanationService

Review text can include raw notification data.

Migrate to redacted prompt.

---

## Provider 5 — CloudDashboardBriefingService

Dashboard context is aggregated but can include merchant names and notes.

Redact merchant/note fields or send aggregated buckets only.

---

## Provider 6 — CloudDedupeJudgeService

Dedupe prompts can include merchant, amount, timestamps.

Redact merchant if strict, keep amount/date if needed.

---

## Provider 7 — CloudWarrantyExtractionService / bank statement validation

Raw OCR/bank statement text can include:

```text
IBAN
account numbers
card digits
addresses
names
```

Needs strict redaction.

---

# 6. DI plan

Add:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object PrivacyAiModule {
    @Provides
    @Singleton
    fun provideCloudPayloadRedactor(
        piiSanitizer: CloudPiiSanitizer,
        hasher: PrivacyHasher
    ): CloudPayloadRedactor = DefaultCloudPayloadRedactor(piiSanitizer, hasher)
}
```

Providers should inject:

```kotlin
private val cloudAiGuard: CloudAiGuard,
private val redactor: CloudPayloadRedactor,
private val cloudAiAuditLogger: CloudAiAuditLogger
```

---

# 7. Audit integration

Add or use planned:

```kotlin
CloudAiAuditEvent(
    provider = "openai",
    capability = "QUERY_INTERPRETATION",
    route = "cloud",
    decision = "SENT_REDACTED",
    redactionApplied = true,
    payloadHash = "...",
    rawTextIncluded = false,
    rawImageUploaded = false,
    timestampMs = timeProvider.now()
)
```

Decisions:

```text
DENIED_PRIVACY
SKIPPED_NO_API_KEY
SENT_REDACTED
SENT_RAW_USER_ALLOWED
FAILED_NETWORK
FAILED_PROVIDER
```

Rule:

```text
If redaction required and redactor fails, do not send cloud request.
```

---

# 8. Provider migration template

Use this pattern.

```kotlin
suspend fun interpret(input: QueryInput): Result<QueryInterpretation> {
    val permission = cloudAiGuard.check(
        capability = PrivacyCapability.CLOUD_AI_QUERY_INTERPRETATION,
        context = mapOf("source" to "assistant")
    )

    if (!permission.allowed) {
        auditLogger.logDenied(...)
        return Result.failure(PrivacyDeniedException(permission.reason))
    }

    val redacted = redactor.redactText(
        text = input.query,
        purpose = CloudPayloadPurpose.QUERY_INTERPRETATION,
        forceRedaction = permission.redactBeforeCloud
    )

    if (permission.redactBeforeCloud && redacted.metadata.rawTextIncluded) {
        auditLogger.logBlocked(...)
        return Result.failure(PrivacyDeniedException("Redaction failed closed"))
    }

    auditLogger.logSentAttempt(...)

    val request = buildRequestBody(redacted.text)

    return runCatching {
        httpClient.post(request)
    }.onFailure {
        auditLogger.logFailed(...)
    }
}
```

---

# 9. Cloud redaction tests

## Unit tests

### `DefaultCloudPayloadRedactorTest`

Input:

```text
"Paid Dr Smith at pharmacy, card 4111111111111111, IBAN GR160110..."
```

Assert:

```text
no raw card
no raw IBAN
email/phone removed
payloadHash exists
redactionApplied = true
rawTextIncluded = false or policy-defined
```

### `MerchantHashRedactionTest`

Assert:

```text
same merchant → same hash token
different merchant → different hash token
raw merchant absent when strict
```

---

## Provider tests with fake HTTP client

### `CloudQueryInterpretationRedactionTest`

Input query:

```text
"Show payments to Dr Smith from card 4111111111111111"
```

Privacy:

```text
cloudAiEnabled = true
redactBeforeCloud = true
```

Assert:

```text
HTTP request body does not contain "Dr Smith"
HTTP request body does not contain card number
audit event SENT_REDACTED
payloadHash set
```

### `CloudReceiptItemCategorizationRedactionTest`

Input:

```text
merchant = "John's Pharmacy"
items = ["Prescription for Maria", "Card 1234"]
```

Assert:

```text
raw sensitive values absent
caller redactBeforeCloud=false cannot override privacy redaction=true
```

### `CloudReceiptAssistImageBlockedWhenRedactionRequiredTest`

Privacy:

```text
redactBeforeCloud = true
receiptImageCloudEnabled = true
```

Assert:

```text
raw image upload denied
audit rawImageUploaded=false
```

---

## Privacy-denied tests

For each provider:

```text
cloudAiEnabled=false
provider called directly
```

Assert:

```text
no HTTP request
DENIED_PRIVACY audit event
Result failure or local fallback
```

---

## Fail-closed tests

Simulate redactor exception.

Assert:

```text
no HTTP request
FAILED_REDACTION or DENIED audit event
```

---

# 10. CI/static guard

Add script:

```text
scripts/guards/check_cloud_provider_privacy.kts
```

Rules:

```text
Every file matching Cloud*Service.kt must reference:
  CloudAiGuard or PrivacyGate
and:
  CloudPayloadRedactor
unless allowlisted.
```

Allowlist initially:

```text
NoOp providers
pure local/on-device providers
test fakes
```

Wire into Gradle after first provider migration, not before.

---

# 11. Rollout strategy

## Step 1

Add redactor implementation and migrate `CloudQueryInterpretationService`.

## Step 2

Add tests and CI guard in warning/manual mode.

## Step 3

Migrate remaining providers.

## Step 4

Turn CI guard into required check.

## Step 5

Add audit UI/debug screen.

---

# 12. Acceptance criteria

## Forecast quality population

Done when:

```text
ForecastInput.dataQuality is populated from actual conversion failures.
Planned/recurring missing-rate values are excluded or marked partial.
Financial weather confidence decreases when forecast input is partial.
UI/debug output shows partial forecast warnings.
Tests prove raw missing-currency amounts do not enter numeric forecast.
```

## CloudPayloadRedactor migration

Done when:

```text
CloudQueryInterpretationService uses CloudAiGuard + CloudPayloadRedactor.
Receipt item categorization no longer trusts caller redaction flag.
Every cloud provider either migrated or allowlisted.
Tests capture outgoing HTTP payload and prove sensitive raw values absent.
Privacy-disabled cloud calls make zero HTTP requests.
Audit records sent redacted / denied / failed attempts.
```

---

# 13. Recommended PR sequence

## PR 1 — Forecast actual quality

```text
Populate actual expense conversion failures into ForecastDataQuality.
Add ForecastInputActualQualityTest.
```

## PR 2 — Forecast planned/recurring quality

```text
Add ForecastMoneyNormalizer.
Normalize planned + recurring future obligations.
Add missing-rate/stale-rate tests.
```

## PR 3 — Forecast consumers

```text
SynthesisEngine and FinancialWeather reduce confidence and expose warnings.
```

## PR 4 — Redactor implementation

```text
DefaultCloudPayloadRedactor wraps CloudPiiSanitizer.
Add unit tests.
```

## PR 5 — CloudQueryInterpretation migration

```text
CloudAiGuard + redactor + audit.
Fake HTTP payload tests.
```

## PR 6 — Receipt item categorization migration

```text
Provider computes effective redaction internally.
```

## PR 7 — Remaining providers + CI guard

```text
Migrate receipt assist, review, dashboard, dedupe, warranty.
Enable static guard.
```

---

# 14. Main caution

Do not try to finish both as one PR.

These are cross-cutting systems. Best path:

```text
Forecast quality:
  actuals → planned → recurring → consumers

Cloud redaction:
  interface → implementation → one provider → all providers → CI guard
```

That gives safe, testable progress without destabilizing the app.