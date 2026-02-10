ExpenseTracker: Comprehensive Evaluation & Future Development Roadmap
Table of Contents
Current State Assessment
Architecture & Code Quality Review
Feature Gap Analysis
Receipt Scanning Feature (Your Idea)
New Feature Proposals
Existing Feature Improvements
Technical Debt & Refactoring
Prioritized Implementation Roadmap
1. Current State Assessment
What the app does well:
Notification-based auto-capture: Solid pipeline from notification → parsing → categorization → expense creation
Multi-source parsing: Revolut, Google Wallet, Greek banks, SMS — well-structured parser registry
Intelligence layer: Naive Bayes classifier, confidence routing, merchant normalization — impressively thorough for an on-device solution
Budget system: Full CRUD with period calculation, notifications at thresholds
Manual entry: Merchant autocomplete from history, category auto-fill
Analytics: Monthly comparisons, spending pace, anomaly detection, recurring expense detection, day-of-week patterns
Review queue: Medium-confidence transactions go to human review with edit capability
Learning system: User corrections feed back into classifier + merchant→category mappings
What's missing or weak:
No receipt/photo scanning (your identified gap)
No income tracking — only expenses
No multi-currency support — EUR hardcoded in many places
No data export/import (CSV, PDF reports)
No cloud sync/backup
No settings screen — debug screen serves double duty
Navigation is cluttered — 7 bottom tabs is too many
No search/filter on transactions
No recurring expense management — detected but not actionable
No split transactions
No shared expenses / debt tracking
No widgets (home screen)
No onboarding flow
No dark/light theme toggle — follows system only
The BootReceiver does nothing meaningful
2. Architecture & Code Quality Review
Strengths:
Clean separation: entities → DAOs → repositories → domain → UI
Hilt DI properly configured
Room migrations handled
Flow-based reactive data pipeline
Debouncing on expensive computations
Thread-safe caches with Mutex
Issues Found:
Critical:

NotificationCaptureService.shouldCapture() uses a whitelist — only MONITORED_PACKAGES are captured. Any new banking app the user installs won't work unless code is updated. Should use blocked-list approach instead (capture everything except blocked).

processAndSaveAll processes in parallel chunks of 20 but processAndSave is @Transaction-annotated — Room transactions + coroutines can deadlock under heavy parallel load.

No database backup/restore mechanism — a single fallbackToDestructiveMigration() means a schema error wipes everything.

sharedExpenses in NotificationRepository creates a CoroutineScope that's never cancelled — memory leak.

Moderate:
5. The MerchantCategoryProvider is referenced but not included in the source — data.provider.MerchantCategoryProvider is used in CategoryRepository.ensureDefaultCategories() but the file is missing.

BudgetMonitor.sendNotification() uses channel "budget_alerts" but the channel is never created (only "expense_tracker_service" is created).

CategorizationEngine.normalize() and MerchantNormalizer.normalize() do the same thing slightly differently — inconsistent normalization.

The InsightsEngine.findAnomalies() has a bug: it queries getLargestExpenseForPeriod for the whole month, not filtered per merchant, so the anomaly attribution to a specific merchant can be wrong.

Bottom navigation with 7 items violates Material Design guidelines (max 5 recommended). On small screens, labels will be truncated.

AddExpenseSheet is rendered as a full-screen composable, not an actual ModalBottomSheet — naming is misleading.

3. Feature Gap Analysis
Feature	Status	Priority
Receipt/Photo scanning	❌ Missing	HIGH
Transaction search & filter	❌ Missing	HIGH
Data export (CSV/PDF)	❌ Missing	HIGH
Settings screen	❌ Missing	HIGH
Income tracking	❌ Missing	MEDIUM
Multi-currency with conversion	⚠️ Partial (stored but not converted)	MEDIUM
Cloud backup	❌ Missing	MEDIUM
Home screen widget	❌ Missing	MEDIUM
Onboarding flow	❌ Missing	MEDIUM
Recurring expense management	⚠️ Detected but not actionable	MEDIUM
Split transactions	❌ Missing	LOW
Shared expenses	❌ Missing	LOW
Biometric lock	❌ Missing	LOW
Tags/labels on expenses	❌ Missing	LOW
4. Receipt Scanning Feature
This is your identified priority. Here's the complete design:

4.1 Architecture
text

Camera/Gallery → Image → ML Kit Text Recognition → Receipt Parser → Expense Creation
4.2 Technology Choice
Google ML Kit Text Recognition (on-device, free, no API key needed):

Works offline
Fast (< 1 second for most receipts)
Supports Latin and Greek text
No privacy concerns (no cloud processing)
4.3 New Entity: ScannedReceipt
Kotlin

@Entity(
    tableName = "scanned_receipts",
    foreignKeys = [
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["expenseId"])]
)
data class ScannedReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePath: String,           // Local file path
    val rawOcrText: String,          // Full OCR output
    val parsedTotal: Double?,        // Extracted total
    val parsedMerchant: String?,     // Extracted store name
    val parsedDate: Long?,           // Extracted date
    val parsedItems: String?,        // JSON array of line items
    val parsedTaxAmount: Double?,    // Extracted tax/VAT
    val currency: String = "EUR",
    val confidence: Float,           // OCR confidence
    val expenseId: Long? = null,     // Link to created expense
    val createdAt: Long = System.currentTimeMillis()
)
4.4 Receipt Parser Engine
Kotlin

@Singleton
class ReceiptParser @Inject constructor() {

    data class ParsedReceipt(
        val merchantName: String?,
        val total: Double?,
        val subtotal: Double?,
        val tax: Double?,
        val date: Long?,
        val currency: String,
        val lineItems: List<LineItem>,
        val confidence: Float
    )

    data class LineItem(
        val description: String,
        val quantity: Double?,
        val unitPrice: Double?,
        val totalPrice: Double
    )

    // Total amount patterns (Greek + English receipts)
    private val totalPatterns = listOf(
        // Greek patterns
        Pattern.compile("""(?:ΣΥΝΟΛΟ|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|ΓΕΝΙΚΟ\s*ΣΥΝΟΛΟ|TOTAL)\s*[:\s]*€?\s*(\d+[.,]\d{2})""", 
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        // English patterns
        Pattern.compile("""(?:TOTAL|GRAND\s*TOTAL|AMOUNT\s*DUE|BALANCE\s*DUE)\s*[:\s]*[€$£]?\s*(\d+[.,]\d{2})""", 
            Pattern.CASE_INSENSITIVE),
        // Amount at bottom (common format)
        Pattern.compile("""(?:€|EUR)\s*(\d+[.,]\d{2})\s*$""", Pattern.MULTILINE),
    )

    // Tax patterns
    private val taxPatterns = listOf(
        Pattern.compile("""(?:ΦΠΑ|VAT|TAX)\s*[:\s]*€?\s*(\d+[.,]\d{2})""", 
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
    )

    // Date patterns
    private val datePatterns = listOf(
        Pattern.compile("""(\d{2})[/\-.](\d{2})[/\-.](\d{4})"""),  // DD/MM/YYYY
        Pattern.compile("""(\d{4})[/\-.](\d{2})[/\-.](\d{2})"""),  // YYYY/MM/DD
        Pattern.compile("""(\d{2})[/\-.](\d{2})[/\-.](\d{2})"""),  // DD/MM/YY
    )

    // Line item pattern: "description  qty x price  total" or just "description  price"
    private val lineItemPattern = Pattern.compile(
        """^(.{3,40}?)\s{2,}(\d+[.,]\d{2})\s*€?\s*$""",
        Pattern.MULTILINE
    )

    fun parse(ocrText: String): ParsedReceipt {
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }
        
        // 1. Extract merchant (usually first 1-3 lines)
        val merchant = extractMerchant(lines)
        
        // 2. Extract total (scan from bottom up — total is usually at the end)
        val total = extractTotal(ocrText)
        
        // 3. Extract tax
        val tax = extractTax(ocrText)
        
        // 4. Extract date
        val date = extractDate(ocrText)
        
        // 5. Extract line items
        val lineItems = extractLineItems(ocrText)
        
        // 6. Calculate subtotal
        val subtotal = if (total != null && tax != null) total - tax else null
        
        // 7. Confidence based on what we found
        val confidence = calculateConfidence(merchant, total, date, lineItems)
        
        return ParsedReceipt(
            merchantName = merchant,
            total = total,
            subtotal = subtotal,
            tax = tax,
            date = date,
            currency = detectCurrency(ocrText),
            lineItems = lineItems,
            confidence = confidence
        )
    }

    private fun extractMerchant(lines: List<String>): String? {
        // First non-empty, non-numeric, non-date line is usually the merchant
        // Skip lines that look like addresses (contain numbers + street words)
        for (line in lines.take(5)) {
            val cleaned = line.trim()
            if (cleaned.length < 3) continue
            if (cleaned.matches(Regex("""^\d+[/\-.].*"""))) continue  // Date
            if (cleaned.matches(Regex("""^[\d\s.,€$£]+$"""))) continue  // Just numbers
            if (cleaned.contains(Regex("""(?i)(ΑΦΜ|ΔΟΥ|ΤΗΛ|TEL|VAT|RECEIPT|ΑΠΟΔΕΙΞΗ)"""))) continue
            return cleaned.take(40)
        }
        return null
    }

    private fun extractTotal(text: String): Double? {
        // Try each pattern, prefer matches closer to the end of text
        val allMatches = mutableListOf<Pair<Double, Int>>() // value, position
        
        for (pattern in totalPatterns) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val amount = matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
                if (amount != null && amount > 0 && amount < 50000) {
                    allMatches.add(Pair(amount, matcher.start()))
                }
            }
        }
        
        // Return the largest amount found near the bottom
        // (receipts often have subtotals before the total)
        return allMatches
            .sortedByDescending { it.second }  // Bottom of receipt first
            .firstOrNull()?.first
            ?: allMatches.maxByOrNull { it.first }?.first  // Fallback: largest amount
    }

    private fun extractTax(text: String): Double? {
        for (pattern in taxPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
            }
        }
        return null
    }

    private fun extractDate(text: String): Long? {
        for (pattern in datePatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return try {
                    val groups = (1..matcher.groupCount()).map { matcher.group(it) }
                    val cal = Calendar.getInstance()
                    
                    when {
                        groups[0].length == 4 -> { // YYYY/MM/DD
                            cal.set(groups[0].toInt(), groups[1].toInt() - 1, groups[2].toInt())
                        }
                        groups[2].length == 4 -> { // DD/MM/YYYY
                            cal.set(groups[2].toInt(), groups[1].toInt() - 1, groups[0].toInt())
                        }
                        else -> { // DD/MM/YY
                            cal.set(2000 + groups[2].toInt(), groups[1].toInt() - 1, groups[0].toInt())
                        }
                    }
                    cal.timeInMillis
                } catch (e: Exception) { null }
            }
        }
        return null
    }

    private fun extractLineItems(text: String): List<LineItem> {
        val items = mutableListOf<LineItem>()
        val matcher = lineItemPattern.matcher(text)
        
        while (matcher.find()) {
            val desc = matcher.group(1)?.trim() ?: continue
            val price = matcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: continue
            
            // Skip if it looks like a total/subtotal line
            if (desc.contains(Regex("""(?i)(TOTAL|ΣΥΝΟΛΟ|VAT|ΦΠΑ|CHANGE|ΡΕΣΤΑ)"""))) continue
            
            items.add(LineItem(
                description = desc,
                quantity = null,
                unitPrice = null,
                totalPrice = price
            ))
        }
        
        return items
    }

    private fun detectCurrency(text: String): String {
        return when {
            text.contains("€") || text.contains("EUR", ignoreCase = true) -> "EUR"
            text.contains("$") || text.contains("USD", ignoreCase = true) -> "USD"
            text.contains("£") || text.contains("GBP", ignoreCase = true) -> "GBP"
            else -> "EUR"
        }
    }

    private fun calculateConfidence(
        merchant: String?,
        total: Double?,
        date: Long?,
        items: List<LineItem>
    ): Float {
        var score = 0f
        if (merchant != null) score += 0.2f
        if (total != null) score += 0.4f  // Most important
        if (date != null) score += 0.2f
        if (items.isNotEmpty()) score += 0.2f
        return score
    }
}
4.5 Receipt Scan Screen
Kotlin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScanScreen(
    onDismiss: () -> Unit,
    viewModel: ReceiptScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) viewModel.processPhoto()
    }
    
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.processGalleryImage(it) }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Receipt") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.step) {
                ScanStep.CAPTURE -> {
                    // Image preview or capture prompt
                    Card(
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (state.imageUri != null) {
                                AsyncImage(
                                    model = state.imageUri,
                                    contentDescription = "Receipt",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("📸", fontSize = 48.sp)
                                    Text("Take a photo or select from gallery")
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { 
                                val uri = viewModel.createTempPhotoUri()
                                cameraLauncher.launch(uri)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📷 Camera")
                        }
                        OutlinedButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🖼️ Gallery")
                        }
                    }
                }
                
                ScanStep.PROCESSING -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Scanning receipt...")
                }
                
                ScanStep.REVIEW -> {
                    // Show extracted data for review
                    val parsed = state.parsedReceipt
                    
                    // Merchant
                    OutlinedTextField(
                        value = state.editMerchant,
                        onValueChange = { viewModel.updateMerchant(it) },
                        label = { Text("Merchant") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Amount
                    OutlinedTextField(
                        value = state.editAmount,
                        onValueChange = { viewModel.updateAmount(it) },
                        label = { Text("Total Amount") },
                        leadingIcon = { Text("€") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Category
                    Text("Category", style = MaterialTheme.typography.labelLarge)
                    CategoryGrid(
                        categories = categories,
                        selectedId = state.selectedCategoryId,
                        onSelect = { viewModel.selectCategory(it) }
                    )
                    
                    // Line items preview (if any)
                    if (parsed?.lineItems?.isNotEmpty() == true) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Detected Items:", fontWeight = FontWeight.Medium)
                        parsed.lineItems.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(item.description, modifier = Modifier.weight(1f))
                                Text("€${String.format("%.2f", item.totalPrice)}")
                            }
                        }
                    }
                    
                    // OCR confidence
                    Text(
                        "Confidence: ${(state.ocrConfidence * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Save button
                    Button(
                        onClick = { viewModel.saveExpense() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Expense")
                    }
                }
                
                ScanStep.DONE -> {
                    // Success animation
                    Text("✅", fontSize = 64.sp)
                    Text("Expense saved!", style = MaterialTheme.typography.titleLarge)
                    LaunchedEffect(Unit) {
                        delay(1500)
                        onDismiss()
                    }
                }
                
                ScanStep.ERROR -> {
                    Text("❌ ${state.errorMessage}")
                    Button(onClick = { viewModel.retry() }) {
                        Text("Try Again")
                    }
                }
            }
        }
    }
}
4.6 Required Dependencies
groovy

// build.gradle (app)
implementation 'com.google.mlkit:text-recognition:16.0.0'
// For Greek text support:
implementation 'com.google.mlkit:text-recognition-latin:16.0.0'
// For image loading in Compose:
implementation 'io.coil-kt:coil-compose:2.5.0'
4.7 Manifest Permissions
XML

<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
5. New Feature Proposals
5.1 🔍 Transaction Search & Filter (HIGH PRIORITY)
Currently there's no way to search or filter transactions. This is a basic UX necessity.

Implementation:

Kotlin

// In ExpenseDao - add search queries
@Query("""
    SELECT * FROM expenses 
    WHERE merchant LIKE '%' || :query || '%'
    OR notes LIKE '%' || :query || '%'
    ORDER BY date DESC
    LIMIT 50
""")
fun searchExpenses(query: String): Flow<List<Expense>>

@Query("""
    SELECT * FROM expenses
    WHERE (:categoryId IS NULL OR categoryId = :categoryId)
    AND (:minAmount IS NULL OR amount >= :minAmount)
    AND (:maxAmount IS NULL OR amount <= :maxAmount)
    AND (:startDate IS NULL OR date >= :startDate)
    AND (:endDate IS NULL OR date <= :endDate)
    AND (:paymentMethod IS NULL OR paymentMethod = :paymentMethod)
    ORDER BY date DESC
""")
fun filterExpenses(
    categoryId: Long? = null,
    minAmount: Double? = null,
    maxAmount: Double? = null,
    startDate: Long? = null,
    endDate: Long? = null,
    paymentMethod: String? = null
): Flow<List<Expense>>
UI: Add a search bar at the top of TransactionsScreen with filter chips below (category, date range, amount range, payment method).

5.2 📊 Data Export (HIGH PRIORITY)
Kotlin

@Singleton
class ExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao
) {
    suspend fun exportToCsv(startDate: Long? = null, endDate: Long? = null): Uri {
        val expenses = if (startDate != null && endDate != null) {
            expenseDao.getExpensesBetween(startDate, endDate)
        } else {
            expenseDao.getAll()
        }
        
        val categories = categoryDao.getAll().associateBy { it.id }
        
        val csv = buildString {
            appendLine("Date,Merchant,Amount,Currency,Category,Payment Method,Type,Notes")
            for (exp in expenses) {
                val cat = exp.categoryId?.let { categories[it]?.name } ?: "Uncategorized"
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(exp.date))
                appendLine("\"$date\",\"${exp.merchant}\",${exp.amount},${exp.currency},\"$cat\",${exp.paymentMethod},${exp.transactionType},\"${exp.notes ?: ""}\"")
            }
        }
        
        val file = File(context.getExternalFilesDir(null), 
            "expenses_${System.currentTimeMillis()}.csv")
        file.writeText(csv)
        
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    
    suspend fun exportToPdf(startDate: Long, endDate: Long): Uri {
        // Generate monthly summary PDF with category breakdown, charts, etc.
        // Use Android's PdfDocument API
    }
}
5.3 ⚙️ Settings Screen (HIGH PRIORITY)
Currently missing. Should contain:

Currency selection (default currency)
Notification monitoring toggle (which apps to monitor)
Auto-accept confidence threshold (slider: 0.7 - 0.95)
Budget notification preferences (on/off, cooldown period)
Data management (export, import, delete all)
App appearance (theme selection, dark/light/system)
About (version, licenses)
Backup & Restore
Kotlin

@Entity(tableName = "user_settings")
data class UserSettings(
    @PrimaryKey val key: String,
    val value: String
)

// Settings keys
object SettingsKeys {
    const val DEFAULT_CURRENCY = "default_currency"
    const val AUTO_ACCEPT_THRESHOLD = "auto_accept_threshold"
    const val BUDGET_NOTIFICATIONS_ENABLED = "budget_notifications_enabled"
    const val NOTIFICATION_COOLDOWN_HOURS = "notification_cooldown_hours"
    const val THEME_MODE = "theme_mode" // "system", "light", "dark"
    const val MONITOR_ALL_APPS = "monitor_all_apps" // vs whitelist
    const val FIRST_DAY_OF_MONTH = "first_day_of_month" // for budget periods
    const val BIOMETRIC_LOCK = "biometric_lock"
}
5.4 💰 Income Tracking (MEDIUM PRIORITY)
Expand the Expense entity or create a separate Income entity:

Kotlin

// Option A: Extend TransactionType (simpler, less disruptive)
enum class TransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    INCOME,      // NEW
    REFUND,      // NEW
    UNKNOWN
}

// Add a balance view to HomeScreen
data class FinancialSummary(
    val totalIncome: Double,
    val totalExpenses: Double,
    val netBalance: Double,
    val savingsRate: Float  // (income - expenses) / income
)
This also enriches analytics with income vs expense comparison charts.

5.5 🏠 Home Screen Widget (MEDIUM PRIORITY)
Kotlin

class ExpenseWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Show: today's spending, month total, top budget status
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(16.dp)
            ) {
                Text("Today: €${todaySpent}")
                Text("Month: €${monthSpent}")
                if (budgetWarning) {
                    Text("⚠️ Budget warning!", style = TextStyle(color = ColorProvider(Color.Red)))
                }
            }
        }
    }
}
5.6 🔄 Recurring Expense Management (MEDIUM PRIORITY)
Currently, recurring expenses are detected but not actionable. Add:

Kotlin

@Entity(tableName = "recurring_expenses")
data class RecurringExpenseRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchant: String,
    val expectedAmount: Double,
    val tolerance: Float = 0.15f,      // 15% variation allowed
    val frequency: RecurringFrequency,
    val nextExpectedDate: Long,
    val categoryId: Long?,
    val isActive: Boolean = true,
    val autoAccept: Boolean = false,    // Skip review queue
    val notifyIfMissed: Boolean = true, // Alert if expected charge doesn't appear
    val createdAt: Long = System.currentTimeMillis()
)

enum class RecurringFrequency {
    WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, YEARLY
}
Feature behavior:

User can mark detected recurring expenses as "tracked"
If autoAccept, matching transactions bypass review queue entirely
If notifyIfMissed, send a notification like "Netflix hasn't charged you this month — cancelled?"
Show upcoming expected charges in home screen
5.7 🏷️ Tags System (LOW PRIORITY)
Allow multiple tags per expense (e.g., "vacation", "business", "gift"):

Kotlin

@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: String
)

@Entity(
    tableName = "expense_tags",
    primaryKeys = ["expenseId", "tagId"]
)
data class ExpenseTag(
    val expenseId: Long,
    val tagId: Long
)
Enables filtering by trip, project, or context.

5.8 🔐 Biometric Lock (LOW PRIORITY)
Kotlin

// Use BiometricPrompt API
class BiometricAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun canAuthenticate(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }
    
    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }
        }
        
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock ExpenseTracker")
            .setSubtitle("Verify your identity to access financial data")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        
        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }
}
5.9 📤 Cloud Backup (Google Drive)
Kotlin

@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) {
    suspend fun createBackup(): File {
        // Checkpoint the WAL to ensure all data is in the main db file
        database.query("PRAGMA wal_checkpoint(FULL)", null)
        
        val dbFile = context.getDatabasePath("expense_tracker_db")
        val backupFile = File(context.cacheDir, "backup_${System.currentTimeMillis()}.db")
        dbFile.copyTo(backupFile, overwrite = true)
        
        return backupFile
    }
    
    suspend fun restoreFromBackup(backupUri: Uri) {
        // Close database, replace file, reopen
        database.close()
        
        val dbFile = context.getDatabasePath("expense_tracker_db")
        context.contentResolver.openInputStream(backupUri)?.use { input ->
            dbFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        
        // Restart app or rebuild database instance
    }
}
6. Existing Feature Improvements
6.1 Fix Notification Monitoring (CRITICAL)
Current problem: Only whitelisted packages are captured. This means the user gets no benefit from new banking apps without an app update.

Solution: Invert the logic — capture everything, filter later.

Kotlin

// In NotificationCaptureService
private fun shouldCapture(packageName: String): Boolean {
    // ALWAYS capture unless explicitly blocked or is a known irrelevant app
    if (IGNORED_PACKAGES.contains(packageName)) return false
    if (packageName == applicationContext.packageName) return false // Don't capture our own
    return true
}
Then the parser + confidence router will handle filtering. Non-financial notifications will get low confidence and auto-reject. This is already how the system is designed to work — the whitelist is a bottleneck.

6.2 Fix Navigation (7 tabs → 4 tabs + drawer)
Current: 7 bottom tabs (Home, Transactions, Review, Analytics, Budget, Categories, Debug)

Proposed:

Bottom nav (4 tabs): Home, Transactions, Analytics, Budget
Review badge on Home or moved to a sheet triggered from Home
Categories: Accessible from Settings
Debug: Accessible from Settings (hidden behind long-press or developer toggle)
Settings: Accessible from top-right icon on Home
6.3 Improve Analytics Screen
Current gaps:

No visual charts for category breakdown (pie/donut chart)
No month-over-month comparison chart
Day-of-week pattern computed but not shown
Add:

Donut chart for category breakdown
Monthly trend line chart (last 6 months)
Day-of-week bar chart
"Insights" as prominent cards, not a horizontal scroll row
6.4 Improve Budget Screen
Add:

Visual budget ring/arc instead of linear progress bar
Budget history (how did you do last month?)
Carry-over calculation for rollover budgets
Per-category spending velocity ("At this pace, you'll exceed by the 25th")
6.5 Fix Budget Notification Channel
Kotlin

// In BudgetMonitor or App initialization
private fun createBudgetNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            "budget_alerts",
            "Budget Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when you approach or exceed budget limits"
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
6.6 Improve Review Screen
Add swipe gestures (swipe right = approve, swipe left = reject) — the text says "Swipe through" but there's no swipe implementation
Show similar past transactions for context
Batch actions: "Approve all high-confidence" button
6.7 Multi-Currency Enhancement
Currently, currency is stored per transaction but there's no conversion or reporting in different currencies.

Kotlin

@Singleton
class CurrencyConverter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Cache exchange rates (update daily from ECB free API)
    private var rates: Map<String, Double> = mapOf(
        "EUR" to 1.0,
        "USD" to 1.08,
        "GBP" to 0.86
    )
    
    fun convert(amount: Double, from: String, to: String): Double {
        val fromRate = rates[from] ?: return amount
        val toRate = rates[to] ?: return amount
        return amount / fromRate * toRate
    }
    
    suspend fun updateRates() {
        // Fetch from ECB: https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml
        // Free, no API key, daily update
    }
}

## 7. AI & Cloud Strategy (New Proposal)

### 7.1 Global Insights & Federated Learning
The current classifier is powerful but limited by the data of a single user. To reach "zero-touch" expense tracking, the system should evolve into a crowdsourced intelligence network.

**Concept:**
- **Anonymized Pattern Sharing**: Users can opt-in to share "fingerprints" of their successful parses (ignoring amounts and personal data) to a central repository.
- **Global Merchant Map**: A cloud-hosted database of normalized merchant patterns and their high-confidence categories.
- **Federated Training**: Periodically pull down global model updates to improve the local Naive Bayes weights without compromising privacy.

**Benefits:**
- **Cold-Start Problem**: New users get high accuracy from day one.
- **Dynamic Adaption**: If a new digital bank or merchant (e.g., "TEMU") appears, the system learns it globally as soon as the first few users categorize it.
- **Crowdsourced Parsers**: The "Generic" parser can be replaced by a collection of community-contributed regex patterns for local banks and services.
