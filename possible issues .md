1. CRITICAL: Compilation Errors
1.1 — ReviewViewModel.kt Missing Imports (WON'T COMPILE)
Kotlin

// ReviewViewModel.kt — Line references
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: NotificationRepository,  // ← MISSING IMPORT
    private val categoryRepository: CategoryRepository
)
Problem: ReviewViewModel uses NotificationRepository but does not import it. The file imports com.yourname.expensetracker.data.repository.CategoryRepository but NOT com.yourname.expensetracker.data.repository.NotificationRepository. Also missing @HiltViewModel import (dagger.hilt.android.lifecycle.HiltViewModel).

Fix:

Kotlin

import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
1.2 — MainActivity.kt Missing SnackbarHost / SnackbarHostState Imports (WON'T COMPILE)
Kotlin

val snackbarHostState = remember { SnackbarHostState() }  // ← Not imported
// ...
snackbarHost = { SnackbarHost(snackbarHostState) },       // ← Not imported
Problem: SnackbarHostState and SnackbarHost are used but never imported.

Fix:

Kotlin

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
1.3 — MerchantCategoryProvider Does Not Exist (WON'T COMPILE)
Kotlin

// CategoryRepository.kt
val defaults = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.categoryBlueprints
val merchantMap = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.getExpandedMap()
Problem: The class com.yourname.expensetracker.data.provider.MerchantCategoryProvider is referenced but never exists in your codebase. This means:

The app cannot compile
Default categories are never seeded
The merchant dictionary is never populated
Fix: Create MerchantCategoryProvider.kt in data/provider/ or inline the data.

2. CRITICAL: Missing Files / Broken References
2.1 — No build.gradle Files Provided
Without the Gradle files, these potential issues cannot be verified but are likely:

Missing Hilt plugin (id 'dagger.hilt.android.plugin')
Missing kapt/ksp for Room annotation processing
Missing Room schema export config (you have exportSchema = false but no migration strategy tests)
Dependency version mismatches between Compose, Room, Hilt
2.2 — No colors.xml or Color Resources
You reference colors inline but have no colors.xml. Not a compilation error but a best-practice miss.

2.3 — Missing ProGuard Rules
For a release build with Hilt, Room, JSONObject serialization — you need ProGuard rules or things will break in release.

3. CRITICAL: Database Issues
3.1 — fallbackToDestructiveMigration() Destroys User Data
Kotlin

// AppModule.kt
Room.databaseBuilder(context, AppDatabase::class.java, "expense_tracker_db")
    .fallbackToDestructiveMigration()
    .build()
Problem: You are at version = 5. Any schema change silently destroys ALL user data — all expenses, categories, corrections, ML training data, everything. This is extremely dangerous for a financial app.

Fix: Write proper Migration objects or at minimum use fallbackToDestructiveMigrationFrom() targeting specific old versions.

3.2 — Database Version 5 But No Migration History
You're at version 5 but have zero migrations defined. Every user who updates from any previous version loses all data silently.

3.3 — ExpenseDao.insert Uses OnConflictStrategy.ABORT
Kotlin

@Insert(onConflict = OnConflictStrategy.ABORT)
suspend fun insert(expense: Expense): Long
Problem: ABORT throws SQLiteConstraintException which you catch in several places, but you're using it as a flow-control mechanism. This is an anti-pattern that:

Creates unnecessary exception overhead
Makes debugging harder (exceptions show in logs)
Is fragile if a new foreign key constraint is added
3.4 — No @Transaction Annotations on Multi-Table Operations
NotificationRepository.processAndSave() performs operations across 4+ tables (raw_notifications, expenses, source_stats, pending_reviews) without any @Transaction wrapper. A crash mid-way leaves data in an inconsistent state:

Raw notification saved but stats not updated
Expense created but source stats not incremented
Status updated to APPROVED but expense insertion failed
Fix: Wrap critical multi-table operations in withTransaction:

Kotlin

database.withTransaction {
    // all DB operations
}
3.5 — ForeignKey CASCADE on PendingReview → RawNotification Can Cause Silent Data Loss
Kotlin

// PendingReview entity
ForeignKey(
    entity = RawNotification::class,
    parentColumns = ["id"],
    childColumns = ["rawNotificationId"],
    onDelete = ForeignKey.CASCADE
)
Problem: If someone deletes a raw notification (which delete(notification) does), all associated pending reviews are silently deleted too — potentially losing items the user hasn't reviewed yet. The delete() method does try to handle this, but there's a race condition where the review might be approved between the check and the delete.

3.6 — getTotalSpentFlow() SQL Includes Only PURCHASE and WITHDRAWAL
SQL

SELECT SUM(amount) FROM expenses WHERE transactionType IN ('PURCHASE', 'WITHDRAWAL')
Problem: This sums withdrawals as spending. But in HomeViewModel, you filter only PURCHASE for totalSpent. These two numbers will disagree — the home screen shows one total, and getTotalSpent() returns a different one. Confusing for users.

3.7 — SourceStatsDao.incrementTotal Default Parameter
Kotlin

suspend fun incrementTotal(packageName: String, now: Long = System.currentTimeMillis())
Problem: Room DAOs don't support default parameter values in @Query methods properly. The default value System.currentTimeMillis() is evaluated at the call site in Kotlin, but this is a subtle footgun — if someone calls this from Java or through a Room-generated wrapper, the default won't apply. It works in your case but is fragile.

5. CRITICAL: Android Lifecycle / Service Issues
5.1 — CoroutineScope Leak in NotificationCaptureService
Kotlin

private val serviceJob = SupervisorJob()
private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
Problem: NotificationListenerService lifecycle is managed by the system. onDestroy() may not always be called (e.g., force-stop, system kill). The SupervisorJob may leak if the service is recreated without proper cleanup.

Additionally, the serviceScope isn't tied to the service lifecycle properly. If the system unbinds and rebinds the service without destroying it, you get multiple scopes.

5.2 — startForegroundService() Called from DebugViewModel
Kotlin

// DebugViewModel.kt
fun triggerManualSync(context: android.content.Context) {
    val intent = android.content.Intent(context, 
        NotificationCaptureService::class.java).apply {
        action = NotificationCaptureService.ACTION_REFRESH_NOTIFICATIONS
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}
Problem: startForegroundService() on a NotificationListenerService is problematic. The service is bound by the system, not started. Calling startForegroundService creates an obligation to call startForeground() within 5 seconds, but onStartCommand doesn't call startForeground() — it only calls refreshActiveNotifications(). This will cause an ANR or crash on Android 8+:

text

Context.startForegroundService() did not then call Service.startForeground()
Fix: Either call startForeground() in onStartCommand() when handling that action, or don't use startForegroundService() — instead communicate via a different mechanism (broadcast, bound service, etc.).

5.3 — BootReceiver Is Annotated with @AndroidEntryPoint But Does Nothing with Hilt
Kotlin

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // ... just logs
    }
}
Problem: @AndroidEntryPoint on a BroadcastReceiver requires Hilt setup, but you don't inject anything. This adds unnecessary overhead and may cause issues if Hilt isn't fully initialized at boot time. Also, the receiver literally does nothing — it just logs. Either make it functional or remove it.

5.4 — Foreground Service Icon Uses Internal Resource
Kotlin

.setSmallIcon(androidx.core.R.drawable.notification_bg)
Problem: androidx.core.R.drawable.notification_bg is an internal AndroidX resource — it's a 9-patch background image, NOT an icon. This will render as a white square or crash on some devices. You should use a proper notification icon drawable.

5.5 — onDestroy() Called on NotificationListenerService
Kotlin

override fun onDestroy() {
    super.onDestroy()
    serviceJob.cancel()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
Problem: onDestroy() is not guaranteed to be called for system-managed services. The serviceJob.cancel() may never execute, leaving coroutines running. Also, STOP_FOREGROUND_REMOVE requires API 24 check, which you do, but the else branch uses deprecated stopForeground(true).

6. HIGH: Concurrency & Thread Safety Bugs
6.1 — CategorizationEngine.invalidateCache() Is Not Thread-Safe
Kotlin

fun invalidateCache() {
    cachedMappings = null
    lastCacheTime = 0
}
Problem: This modifies cachedMappings and lastCacheTime WITHOUT acquiring the cacheMutex. Meanwhile, getMappings() reads these values under the mutex. This is a classic data race:

text

Thread A: invalidateCache() → sets cachedMappings = null
Thread B: getMappings() → reads cachedMappings, sees non-null (stale), returns it
Thread A: sets lastCacheTime = 0
Fix:

Kotlin

suspend fun invalidateCache() {
    cacheMutex.withLock {
        cachedMappings = null
        lastCacheTime = 0
    }
}
6.2 — TransactionClassifier Non-Atomic Read of isLoaded
Kotlin

suspend fun initialize() {
    if (isLoaded) return  // ← Read outside mutex
    mutex.withLock {
        if (isLoaded) return  // ← Read inside mutex (correct, double-check)
        // ...
        isLoaded = true
    }
}
Problem: The first isLoaded check is outside the mutex. While this is a valid double-checked locking pattern in some languages, in Kotlin/JVM, isLoaded is not @Volatile, so the JVM may cache the value and different threads may see stale values. The read could return true before the writes inside the mutex are visible.

Fix: Mark isLoaded as @Volatile:

Kotlin

@Volatile
private var isLoaded = false
6.3 — processAndSave() TOCTOU Race on Deduplication
Kotlin

// Step 0: Check exists
if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) {
    return
}
// Step 1: Insert
val rawId = try {
    dao.insert(notification)
} catch (e: SQLiteConstraintException) {
    return
}
Problem: Between the exists() check and the insert(), another thread (e.g., refreshActiveNotifications()) could insert the same notification. You handle this with the catch, but there's no unique constraint on (packageName, timestamp, title, text) — the raw_notifications table only has a composite index, NOT a unique index. So the catch may never fire, and you get duplicates.

Fix: Add a unique index:

Kotlin

@Entity(
    tableName = "raw_notifications",
    indices = [
        Index(value = ["packageName", "timestamp", "title", "text"], unique = true)
    ]
)
6.4 — TransactionClassifier.scope Never Cancelled
Kotlin

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
Problem: This scope is created when the singleton is created and never cancelled. It's a permanent coroutine scope that survives the entire app lifecycle. While not technically a leak (it's a singleton), any pending saveToDisk() jobs may run during shutdown and could cause Context access issues.

6.5 — approveReview() Race Between Read and Update
Kotlin

val review = pendingReviewDao.getById(reviewId) ?: return
val rowsUpdated = pendingReviewDao.updateStatusIfPending(reviewId, "APPROVED")
if (rowsUpdated == 0) return
Problem: review is fetched before updateStatusIfPending. If another thread modifies the review between these two calls, you're working with stale data. The updateStatusIfPending prevents double-approval, but you then use the stale review object for creating the expense. If someone else modified the amount/merchant between fetch and status update, you'd use old data.

7. HIGH: Logic Bugs & Data Integrity
7.1 — deleteAll() Doesn't Delete All Related Data
Kotlin

suspend fun deleteAll() {
    dao.deleteAll()          // raw notifications
    expenseDao.deleteAll()   // expenses
    sourceStatsDao.resetAllPendingCounts()  // just resets pending counts
}
Problem: This does NOT delete:

pending_reviews — orphaned reviews remain
user_corrections — stale corrections remain
merchant_categories — user-learned mappings remain
source_stats entries themselves — only resets pending counts
blocked_packages — blocked apps remain
Because pending_reviews has a CASCADE foreign key on rawNotificationId, deleting raw notifications WILL cascade-delete pending reviews. But the other tables remain inconsistent.

7.2 — getTotalSpentFlow() vs HomeViewModel Disagree on Calculation
SQL

-- ExpenseDao
SELECT SUM(amount) FROM expenses WHERE transactionType IN ('PURCHASE', 'WITHDRAWAL')

-- HomeViewModel
val purchases = expenses.filter {
    it.transactionType == TransactionType.PURCHASE
}
val totalSpent = purchases.sumOf { it.amount }
Problem: getTotalSpentFlow() includes WITHDRAWAL amounts; HomeViewModel.totalSpent only includes PURCHASE. The "total spent" shown on the home screen differs from what getTotalSpent() returns (used in DebugViewModel).

7.3 — Merchant Normalization Inconsistency
Two different normalizers exist:

CategorizationEngine.normalize(): [^A-ZΑ-Ω0-9 &]
MerchantNormalizer.normalize(): [^A-ZΑ-Ω0-9 &]
They look the same, BUT the MerchantNormalizer also applies noise patterns first. So calling categorizationEngine.normalize("SKLAVENITIS #123") gives "SKLAVENITIS 123" while merchantNormalizer.normalize("SKLAVENITIS #123") gives "SKLAVENITIS". This means the merchant pattern stored in merchant_categories may not match what the categorization engine looks up.

7.4 — approveReview Creates Expense Even When Duplicate (Silently)
Kotlin

if (!isDuplicate) {
    // Create expense...
}
// Record user correction for learning (ALWAYS runs)
val correction = UserCorrection(...)
userCorrectionDao.insert(correction)
Problem: When a duplicate IS detected, the code:

Skips expense creation (correct)
Still records a user correction as wasApproved = true (incorrect — the user approved something that wasn't actually saved)
Still trains the classifier with positive signal
Still learns the merchant → category mapping
This corrupts the learning data.

7.5 — rejectReview Missing Error Handling
Kotlin

fun rejectReview(reviewId: Long) {
    viewModelScope.launch {
        repository.rejectReview(reviewId)
    }
}
Unlike approveReview, rejectReview has no try/catch. If the rejection fails, the user gets no feedback.

7.6 — HomeViewModel now Variable Is Unused
Kotlin

val now = System.currentTimeMillis()
This is computed but never used.

7.7 — Week Calculation Bug
Kotlin

val tempCal = cal.clone() as Calendar
tempCal.firstDayOfWeek = Calendar.MONDAY
tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
if (tempCal.timeInMillis > todayStart) {
    tempCal.add(Calendar.DAY_OF_YEAR, -7)
}
Problem: After setting cal to start of today and then cloning, tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) can jump forward to the next Monday if today is Sunday and firstDayOfWeek is Monday. The > todayStart check attempts to fix this but compares against the wrong base — todayStart is the start of today from cal, but cal was already modified by set(DAY_OF_MONTH, 1) later. Wait — actually monthStart is computed later. Let me re-read...

Actually, cal is used for today, then tempCal is cloned from it, then cal is used again for month. The order is:

cal → today start → todayStart
tempCal = clone of cal (which is today start) → set to Monday → weekStart
cal.set(DAY_OF_MONTH, 1) → monthStart
The issue is step 3 modifies the original cal which was already used for todayStart. If today is the 1st, this works fine. But monthStart calculation mutates cal — it's not wrong per se, but it's fragile code. The real bug potential is in the week calculation when firstDayOfWeek and DAY_OF_WEEK interact on locale boundaries.

9. MEDIUM: UI / Compose Issues
9.1 — Shared ReviewViewModel Instance Between MainScreen and ReviewScreen
Kotlin

// MainScreen
val reviewViewModel: ReviewViewModel = hiltViewModel()  // ← Instance 1

// ReviewScreen (called inside MainScreen)
fun ReviewScreen(viewModel: ReviewViewModel = hiltViewModel()) // ← Instance 2
Problem: hiltViewModel() creates a NEW ViewModel scoped to the nearest NavBackStackEntry or Activity. Since ReviewScreen is called inside MainScreen without navigation, both calls create separate instances. The pendingCount badge on the bottom bar and the actual review list may use different ViewModel instances.

In this case, since both are scoped to the Activity (no navigation), they SHOULD be the same instance. But this is fragile and depends on the Compose hierarchy. If you add Navigation in the future, they'll diverge.

9.2 — CategoryScreen Has Its Own Scaffold Inside the Main Scaffold
Kotlin

// MainScreen → Scaffold → CategoryScreen → Scaffold (nested!)
fun CategoryScreen() {
    Scaffold(
        topBar = { TopAppBar(...) },
        floatingActionButton = { ... }
    ) { ... }
}
Problem: Nested Scaffolds cause:

Double padding
Overlapping top bars
FAB positioning issues
Insets handled incorrectly
The same issue exists for DebugScreen, ReviewScreen, and TransactionsScreen — they ALL have their own Scaffold with TopAppBar inside the main Scaffold.

9.3 — No Loading States
None of the screens show loading indicators. When the database is being queried, users see empty screens briefly. This is especially noticeable on first launch when categories are being seeded.

9.4 — No Error Handling in UI (Except ReviewScreen)
Only ReviewViewModel has error messaging. All other ViewModels silently swallow exceptions:

Kotlin

fun deleteExpense(expense: Expense) {
    viewModelScope.launch {
        repository.deleteExpense(expense)  // ← No try/catch, no error UI
    }
}
9.5 — TransactionsScreen Uses items() Without Stable Keys
Kotlin

items(transactions) { item ->
    TransactionItem(...)
}
Problem: No key parameter provided. When the list updates, Compose can't efficiently diff items and may recompose the entire list. This is especially wasteful since the list is ordered by date DESC and items are prepended.

Fix:

Kotlin

items(transactions, key = { it.expense.id }) { item -> ... }
9.6 — EditReviewDialog Doesn't Validate Amount Input
Kotlin

OutlinedTextField(
    value = amount,
    onValueChange = { amount = it },
    label = { Text("Amount") },
)
Users can type anything — letters, negative numbers, multiple decimal points. No input validation or keyboard type:

Fix:

Kotlin

keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
9.7 — Theme Status Bar Color Issue
Kotlin

SideEffect {
    val window = (view.context as Activity).window
    window.statusBarColor = colorScheme.primary.toArgb()
}
Problem: With enableEdgeToEdge() in MainActivity, setting statusBarColor directly conflicts with edge-to-edge handling. On Android 15+, this will be ignored anyway. The status bar should be transparent with appropriate content color.

10. MEDIUM: Parser & Intelligence Issues
10.1 — Generic Parser Accepts Non-Transaction Amounts
The generic parser's amountPattern matches \d+ without decimal — e.g., "5EUR" would match as 5 EUR. But the pattern is:

regex

([€$£])\s*(\d+(?:[.,]\d{2})?)|(\d+(?:[.,]\d{2})?)\s*([€$£]|EUR|USD|GBP)
The (?:[.,]\d{2})? makes the decimal part optional. So "€5" matches as amount 5.0. This could match prices in promotional notifications like "Starting from €5".

10.2 — GreekBankParser.tryExtract() Fragile Group Parsing
Kotlin

for (i in 1..matcher.groupCount()) {
    val group = matcher.group(i) ?: continue
    if (group.matches(Regex("""\d+[.,]\d{2}"""))) {
        amountStr = group
    } else if (group.matches(Regex("""[€$£]|EUR|USD|GBP""", RegexOption.IGNORE_CASE))) {
        currency = normalizeCurrency(group)
    } else if (group.length > 2) {
        merchant = cleanMerchant(group)
    }
}
Problem: This iterates over ALL regex groups and classifies them by content. If a merchant name happens to be "EUR" or "USD" (unlikely but possible), it's classified as currency. More critically, if a merchant name contains only digits (e.g., "7-Eleven" → group "7" after cleaning), the length > 2 check fails and merchant stays "Unknown".

10.3 — Naive Bayes Classifier Numerical Instability
Kotlin

var logProbPos = ln(totalPositive.toDouble() / total)
Problem: If totalPositive is 0 (all training samples are negative), ln(0) = -Infinity, causing all calculations to produce -Infinity or NaN. Similarly for totalNegative = 0.

Fix: Add Laplace smoothing to class priors too:

Kotlin

var logProbPos = ln((totalPositive + 1.0) / (total + 2.0))
10.4 — MerchantNormalizer Applies Noise Patterns to Already-Uppercased Text But Some Patterns Are Case-Insensitive While Already Upper
The normalize() function uppercases first, then applies patterns with IGNORE_CASE. The IGNORE_CASE is redundant since text is already uppercase, but it also means the patterns like Regex("""BRANCH|STORE""", IGNORE_CASE) will work. However, IGNORE_CASE adds overhead for no reason when the input is already uppercase.

10.5 — Duplicate Regex Compilation on Every Call
Kotlin

// CategorizationEngine.normalize()
fun normalize(merchant: String): String {
    return merchant.uppercase()
        .replace(Regex("[^A-ZΑ-Ω0-9 &]"), "")  // ← New Regex every call
        .trim()
        .replace(Regex("\\s+"), " ")            // ← New Regex every call
}
Problem: Regex() compiles a new pattern on every invocation. For a method called for every notification, this adds GC pressure.

Fix: Use by lazy or companion object:

Kotlin

companion object {
    private val NON_ALPHA = Regex("[^A-ZΑ-Ω0-9 &]")
    private val MULTI_SPACE = Regex("\\s+")
}