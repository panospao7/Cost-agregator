package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.domain.model.UiText

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

enum class AiCapability {
    DASHBOARD_BRIEFING,
    REVIEW_EXPLANATION,
    QUERY_INTERPRETATION,
    RECEIPT_EXTRACTION,
    WARRANTY_EXTRACTION,
    CATEGORIZATION_FALLBACK,
    DEDUPE_JUDGE,
    LOCATION_SUMMARY,
    NOTIFICATION_PARSE,
    REVIEW_PRIORITIZATION,
    SEMANTIC_DEDUPE,
    RECEIPT_ITEM_CATEGORIZATION // NEW: AI categorization of individual receipt items
}

enum class AiMode {
    ON_DEVICE,
    CLOUD,
    AUTO
}

enum class AiRoute {
    ON_DEVICE,
    CLOUD,
    DETERMINISTIC_FALLBACK,
    DISABLED
}

enum class OnDeviceModelStatus {
    AVAILABLE,
    NOT_INSTALLED,
    DOWNLOADING,
    UNAVAILABLE,
    UNSUPPORTED_DEVICE,
    UNSUPPORTED_ANDROID_VERSION,
    DISABLED_BY_POLICY,
    UNKNOWN
}

enum class AiTargetType {
    DASHBOARD,
    PENDING_REVIEW,
    SCANNED_RECEIPT,
    EXPENSE,
    ANALYTICS,
    QUERY_SESSION
}

enum class AiArtifactStatus {
    QUEUED,
    RUNNING,
    READY,
    FAILED,
    DISMISSED,
    APPLIED
}

// ---------------------------------------------------------------------------
// Core reference model
// ---------------------------------------------------------------------------

data class AiTargetRef(
    val type: AiTargetType,
    val id: Long? = null,
    val key: String
)

data class AiRouteDecision(
    val route: AiRoute,
    val reason: String,
    val providerName: String? = null,
    val modelName: String? = null
)

// ---------------------------------------------------------------------------
// Settings model (persisted via DataStore — no Room dependency)
// ---------------------------------------------------------------------------

data class AiSettings(
    val aiEnabled: Boolean = true,
    val allowCloudAi: Boolean = false,
    val allowOnDeviceAi: Boolean = true,
    val assistantEnabled: Boolean = false,
    val queryInterpretationEnabled: Boolean = false,
    val dashboardBriefingEnabled: Boolean = false,
    val reviewExplanationEnabled: Boolean = false,
    val receiptAssistEnabled: Boolean = false, // PRIVACY FIX: Align with DataStore default — off by default
    val receiptImageCloudEnabled: Boolean = false, // PRIVACY FIX: Align with DataStore default — cloud image upload must be opt-in
    val receiptItemCategorizationEnabled: Boolean = false, // NEW
    val categorizationFallbackEnabled: Boolean = false,
    val dedupeJudgeEnabled: Boolean = false,
    val proactiveBriefingsEnabled: Boolean = false,
    val receiptQuickSaveEnabled: Boolean = false,
    val reviewQuickApproveEnabled: Boolean = false,
    val redactBeforeCloud: Boolean = true,
    val wifiOnlyForCloud: Boolean = false,
    val storeConversationHistory: Boolean = false,
    val preferredMode: AiMode = AiMode.AUTO,
    val warrantyExtractionEnabled: Boolean = true
)

data class AiEngagementState(
    val lastDeliveredDashboardBriefingKey: String? = null,
    val lastOpenedDashboardBriefingKey: String? = null
)

// ---------------------------------------------------------------------------
// Phase 1 — Dashboard Briefing domain contract
// ---------------------------------------------------------------------------

data class DashboardBriefingInput(
    val dateKey: String,
    val weatherHeadline: UiText,
    val weatherSummary: UiText,
    val discretionaryBudget: Double,
    val totalCommitted: Double,
    val totalLikely: Double,
    val pendingReviewCount: Int,
    val currentMonthSpent: Double,
    val topCategories: List<String>,
    val budgetWarnings: List<DashboardBudgetWarningInput>,
    val upcomingItems: List<DashboardUpcomingItemInput>,
    val transactionInsight: TransactionInsightPromptInput? = null,
    /** Optional minimum amount filter for AI-driven transaction filtering. */
    val minAmount: Double? = null,
    /** Optional maximum amount filter for AI-driven transaction filtering. */
    val maxAmount: Double? = null
)

enum class TransactionInsightAmountBucket {
    UNDER_20,
    RANGE_20_49,
    RANGE_50_99,
    RANGE_100_249,
    RANGE_250_499,
    RANGE_500_999,
    RANGE_1000_PLUS
}

data class TransactionInsightPromptInput(
    val merchantName: String,
    val promptAmount: Double,
    val currencyCode: String,
    val redactForPrompt: Boolean,
    val amountBucket: TransactionInsightAmountBucket,
    val isHighValue: Boolean
)

data class DashboardBudgetWarningInput(
    val categoryLabel: UiText,
    val percentUsed: Int
)

data class DashboardUpcomingItemInput(
    val description: String,
    val amount: Double,
    val dateMillis: Long,
    val currencyCode: String? = null
)

data class DashboardBriefing(
    val title: String,
    val text: String,
    val tone: String,
    val confidence: Float? = null
)

// ---------------------------------------------------------------------------
// Phase 1 — Review Explanation domain contract
// ---------------------------------------------------------------------------

data class ReviewExplanationInput(
    val reviewId: Long,
    val merchant: String,
    val amount: Double,
    val currency: String,
    val suggestedType: String,
    val suggestedCategoryId: Long?,
    val confidence: Float,
    val matchType: String?,
    val explanation: String?,
    val packageName: String,
    val notificationTitle: String?,
    val notificationText: String?
)

data class ReviewExplanation(
    val headline: String,
    val body: String,
    val caution: String? = null,
    val confidence: Float? = null
)
