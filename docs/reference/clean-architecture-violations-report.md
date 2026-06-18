# Clean Architecture Violations Report

## Executive Summary

- **Total Files Violating Clean Architecture:** 12
- **Critical Violations:** 3
- **High Priority:** 6
- **Medium Priority:** 2
- **Low Priority:** 3

**Impact:** These violations couple the domain layer to Android framework, making it harder to test, port, or refactor without framework dependencies.

---

## Critical Violations (Fix ASAP)

### 1. `model/UiText.kt` - CRITICAL

**Issue:** Domain model contains full Android framework integration including Compose UI rendering.

**Current Imports:**
```kotlin
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
```

**Current Design:**
```kotlin
sealed class UiText {
    @Composable
    fun asString(): String { /* Compose rendering */ }
}
```

**Problems:**
- ❌ Domain model depends on Compose framework
- ❌ Can't unit test without Compose
- ❌ Can't use in data layer or backend
- ❌ @Composable functions in domain layer

**Solution:**

**Step 1:** Make domain model framework-agnostic
```kotlin
// app/src/main/java/com/yourname/expensetracker/domain/model/UiText.kt
sealed class UiText {
    data class PlainText(val text: String) : UiText()
    data class StringResource(@StringRes val resId: Int) : UiText()
    data class PluralResource(@PluralsRes val resId: Int, val quantity: Int) : UiText()
    data class Formatted(val template: String, val args: List<Any>) : UiText()
    
    // Pure function, no rendering
    fun getRawString(): String? = when (this) {
        is PlainText → text
        else → null
    }
}
```

**Step 2:** Move rendering to presentation layer
```kotlin
// app/src/main/java/com/yourname/expensetracker/presentation/util/UiTextComposable.kt
@Composable
fun UiText.asString(): String = when (this) {
    is UiText.PlainText → text
    is UiText.StringResource → stringResource(resId)
    is UiText.PluralResource → pluralStringResource(resId, quantity)
    // ...
}
```

**Estimated Effort:** 2-3 hours (update all consumers)

**Test:** ✅ Domain UiText can be tested without Compose

---

### 2. `naturallanguage/NaturalLanguageSearchEngine.kt` - CRITICAL

**Issue:** Speech recognition API in domain layer.

**Current Imports:**
```kotlin
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
```

**Problems:**
- ❌ Speech API is framework-specific
- ❌ Context required (framework coupling)
- ❌ Can't work without Android
- ❌ Hard to test (SpeechRecognizer not mockable)

**Solution:**

**Step 1:** Extract interface to domain
```kotlin
// app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/SpeechRecognizer.kt
interface SpeechRecognizer {
    suspend fun recognizeQuery(language: String): QueryRecognitionResult
}

sealed class QueryRecognitionResult {
    data class Success(val text: String) : QueryRecognitionResult()
    data class Error(val reason: String) : QueryRecognitionResult()
}
```

**Step 2:** Move implementation to presentation/feature
```kotlin
// app/src/main/java/com/yourname/expensetracker/presentation/feature/search/AndroidSpeechRecognizer.kt
@Singleton
class AndroidSpeechRecognizer @Inject constructor(
    private val context: Context
) : SpeechRecognizer {
    private val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
    
    override suspend fun recognizeQuery(language: String): QueryRecognitionResult {
        // Actual Android Speech API implementation
    }
}
```

**Step 3:** Update domain to use interface
```kotlin
// app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageSearchEngine.kt
@Singleton
class NaturalLanguageSearchEngine @Inject constructor(
    private val speechRecognizer: SpeechRecognizer  // Interface, not Android API
) {
    suspend fun search(language: String): SearchResult {
        return when (val result = speechRecognizer.recognizeQuery(language)) {
            is QueryRecognitionResult.Success → interpretQuery(result.text)
            is QueryRecognitionResult.Error → SearchResult.Error(result.reason)
        }
    }
}
```

**Estimated Effort:** 3-4 hours (Presentation layer needs Speech setup)

**Test:** ✅ Domain can now be tested with mock SpeechRecognizer

---

### 3. ML Classifiers (Context Dependency) - CRITICAL

**Affected Files:**
- `intelligence/ml/ExpenseCategoryClassifier.kt`
- `intelligence/ml/HybridExpenseClassifier.kt`
- `intelligence/ml/MerchantNormalizer.kt`
- `intelligence/TransactionClassifier.kt`

**Current Pattern:**
```kotlin
class ExpenseCategoryClassifier @Inject constructor(
    private val context: Context  // ❌ Domain should not receive Context
) {
    init {
        // Load TF Lite model from assets
        val assetFileDescriptor = context.assets.open("model.tflite")
    }
}
```

**Problems:**
- ❌ Domain constructor requires Context
- ❌ Can't instantiate without Android app
- ❌ Asset loading is framework-specific

**Solution:**

**Step 1:** Create ML model interface
```kotlin
// app/src/main/java/com/yourname/expensetracker/domain/intelligence/ExpenseClassifierModel.kt
interface ExpenseClassifierModel {
    suspend fun classify(features: Map<String, Float>): ClassificationResult
    fun getSupportedCategories(): List<String>
}

data class ClassificationResult(
    val category: String,
    val confidence: Float
)
```

**Step 2:** Create data-layer implementation
```kotlin
// app/src/main/java/com/yourname/expensetracker/data/ml/TFLiteExpenseClassifier.kt
@Singleton
class TFLiteExpenseClassifier @Inject constructor(
    private val context: Context
) : ExpenseClassifierModel {
    private val interpreter: Interpreter
    
    init {
        // Load model from assets
        val assetFileDescriptor = context.assets.openFd("model.tflite")
        interpreter = Interpreter(loadModelFile(assetFileDescriptor))
    }
    
    override suspend fun classify(features: Map<String, Float>): ClassificationResult {
        // TF Lite inference logic
    }
}
```

**Step 3:** Update domain to accept interface
```kotlin
// app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifier.kt
@Singleton
class HybridExpenseClassifier @Inject constructor(
    private val classifierModel: ExpenseClassifierModel  // Interface only
) {
    suspend fun classify(expense: Expense): Category {
        val features = extractFeatures(expense)
        return classifierModel.classify(features).category
    }
}
```

**Estimated Effort:** 4-5 hours (need to factor out all asset loading)

**Test:** ✅ Domain can be tested with mock classifier

---

## High Priority Violations

### 4. `performance/ImageCache.kt` - HIGH

**Issue:** Image caching in domain layer.

**Imports:**
```kotlin
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
```

**Current:** Image caching with Context

**Fix:** Move to `data/cache/ImageCache.kt`
- Keep interface in domain: `interface ImageCache { suspend fun get(uri: Uri): Bitmap? }`
- Implement in data layer with Context
- Domain depends on interface only

**Estimated Effort:** 1-2 hours

---

### 5. `debug/ServiceDiagnostics.kt` - HIGH

**Issue:** Debug utilities in domain with SharedPreferences.

**Imports:**
```kotlin
import android.content.Context
import android.content.SharedPreferences
```

**Fix:** Move entire `debug/` folder to `data/debug/` or `presentation/debug/`

**Rationale:**
- Debug features should not be in shipped domain code
- SharedPreferences belongs in data layer
- UI debug screens belong in presentation

**Estimated Effort:** 1 hour (move + repoint imports)

---

### 6. `debug/NotificationSeeder.kt` - HIGH

**Issue:** Context dependency for notification seeding.

**Fix:** Move to `data/debug/NotificationSeeder.kt` or app startup layer

**Estimated Effort:** 30 minutes

---

### 7. `ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt` - HIGH

**Issue:** Notification delivery in domain.

**Import:**
```kotlin
import android.content.Context
```

**Current Pattern:**
```kotlin
class DeliverProactiveBriefingNotificationUseCase @Inject constructor(
    private val context: Context  // ❌
)
```

**Fix:**
```kotlin
// Domain interface
interface NotificationDispatcher {
    suspend fun dispatchBriefing(briefing: BriefingContent)
}

// Domain use case
class DeliverProactiveBriefingNotificationUseCase @Inject constructor(
    private val dispatcher: NotificationDispatcher  // ✅
)

// Data/Presentation layer implementation
class AndroidNotificationDispatcher @Inject constructor(
    private val context: Context
) : NotificationDispatcher {
    override suspend fun dispatchBriefing(briefing: BriefingContent) {
        // Android notification API
    }
}
```

**Estimated Effort:** 2 hours

---

### 8. `location/LocationResolver.kt` - HIGH (Minor)

**Issue:** Using `android.util.Log` instead of Timber.

**Fix:** Simple replacement
```kotlin
// ❌ Remove
import android.util.Log
Log.d("TAG", msg)

// ✅ Replace with
import timber.log.Timber
Timber.tag("TAG").d(msg)
```

**Estimated Effort:** 15 minutes

---

## Medium Priority Violations

### 9. `analytics/AdvancedAnalyticsModels.kt` - MEDIUM

**Issue:** Compose `@Immutable` annotation on domain models.

**Current:**
```kotlin
import androidx.compose.runtime.Immutable

@Immutable
data class AdvancedMetrics(...)
```

**Fix Option A:** Remove annotation (Kotlin data classes are already immutable)
```kotlin
data class AdvancedMetrics(...)  // Already immutable
```

**Fix Option B:** Use Kotlin `@Immutable` (if available in kotlin-stdlib)

**Rationale:** Domain models shouldn't declare Compose-specific directives

**Estimated Effort:** 30 minutes

---

### 10. `receipt/ReceiptOcrService.kt` - MEDIUM

**Issue:** ExifInterface import (actually LOW severity - it's a file format library, not UI framework)

```kotlin
import androidx.exifinterface.media.ExifInterface
```

**Assessment:** EXIF is file format metadata, not UI. ✅ Acceptable

**Action:** No change needed (update classification to LOW)

---

## Low Priority (Code Quality)

### 11. Missing Timber import

**In:** `location/LocationResolver.kt`

**Fix:** Replace `android.util.Log` with `timber.log.Timber`

---

## Implementation Plan

### Phase 1: Critical (Week 1)
1. Refactor `UiText.kt` - Move rendering to presentation
2. Extract `SpeechRecognizer` interface - Move implementation to presentation
3. Create ML model interfaces - Move implementations to data layer

**Risk:** High impact on consumers (search 5 uses of UiText, etc.)
**Testing:** Add unit tests for new interfaces

### Phase 2: High Priority (Week 2)
4. Move `ImageCache` to data layer
5. Move debug utilities to data/debug
6. Extract `NotificationDispatcher` interface
7. Replace Log with Timber

**Risk:** Medium (mostly moves, some refactoring)
**Testing:** Verify notification dispatch still works

### Phase 3: Review & Consolidate (Week 3)
8. Code review all changes
9. Update documentation
10. Verify no new violations introduced
11. Update architecture guidelines

---

## Verification Checklist

After fixes, run these checks:

```bash
# 1. No android imports (except debug/)
grep -r "import android\." app/src/main/java/com/yourname/expensetracker/domain \
  --exclude-dir=debug \
  && echo "❌ FAIL: Found android imports" || echo "✅ PASS: No android imports"

# 2. No androidx.compose imports
grep -r "import androidx.compose" app/src/main/java/com/yourname/expensetracker/domain \
  && echo "❌ FAIL" || echo "✅ PASS"

# 3. No androidx.ui imports
grep -r "import androidx.ui" app/src/main/java/com/yourname/expensetracker/domain \
  && echo "❌ FAIL" || echo "✅ PASS"

# 4. All @Inject use DI interfaces (no Context in domain constructors)
grep -r "Context" app/src/main/java/com/yourname/expensetracker/domain \
  | grep -v "// " | grep -v "import" | grep -v "test"
```

---

## Refactoring Checklist Template

For each violation fix:

- [ ] Create domain interface (if extracting)
- [ ] Move implementation to appropriate layer (data/presentation)
- [ ] Update DI bindings
- [ ] Search and replace imports in consumers
- [ ] Verify compilation
- [ ] Run affected tests
- [ ] Update architecture documentation
- [ ] Create commit with clear message

---

## Expected Benefits After Fixes

✅ **Testability:** Domain classes can be unit-tested without Android SDK  
✅ **Portability:** Domain layer could be extracted to pure Kotlin library  
✅ **Clarity:** Clear separation of concerns (domain ≠ framework)  
✅ **Maintainability:** Framework changes don't cascade to domain  
✅ **Reusability:** Domain logic usable in backend, scripts, multiplatform  

---

## Reference: Clean Architecture Layers

```
┌─────────────────────────────────────────┐
│         Presentation Layer              │
│  (Activities, Fragments, Compose UI)    │
│        AndroidNotificationDispatcher    │
│        AndroidSpeechRecognizer         │
└─────────────────────────────────────────┘
              ↑        ↓
┌─────────────────────────────────────────┐
│         Data Layer                      │
│  (Repositories, Databases, APIs)        │
│      TFLiteExpenseClassifier            │
│      AndroidImageCache                  │
│      CachedCurrencyRates               │
└─────────────────────────────────────────┘
              ↑        ↓
┌─────────────────────────────────────────┐
│         Domain Layer (Pure Kotlin)      │
│  (Models, UseCases, Engines, Services)  │
│  ✅ No Android imports                   │
│  ✅ No framework coupling               │
│  ✅ Interface-based dependencies        │
└─────────────────────────────────────────┘
```

**Domain should depend on abstractions in presentation ❌**  
**Domain should depend on abstractions in data ✅**

---

## Q&A

**Q: Why not just suppress the warnings?**
A: Warnings will be ignored, technical debt accumulates, refactoring becomes harder.

**Q: Will this break the app?**
A: No. All changes are internal refactoring. Public API remains identical.

**Q: How long will this take?**
A: ~10-15 hours total across 3 phases. Can be parallelized.

**Q: What if I don't fix it?**
A: App continues working, but:
- Harder to test domain logic
- Harder to refactor later
- Coupling increases over time
- New violations likely added

---

**Last Updated:** April 4, 2026  
**Priority:** Medium (Quality improvement, not blocking)
