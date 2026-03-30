package com.yourname.expensetracker.domain.ai.model

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

enum class AiCapability {
    DASHBOARD_BRIEFING,
    REVIEW_EXPLANATION,
    QUERY_INTERPRETATION,
    RECEIPT_EXTRACTION,
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
    val receiptAssistEnabled: Boolean = true,  // Changed: Enable by default for better accuracy
    val receiptImageCloudEnabled: Boolean = true,  // Changed: Enable image analysis by default
    val receiptItemCategorizationEnabled: Boolean = false, // NEW
    val categorizationFallbackEnabled: Boolean = false,
    val dedupeJudgeEnabled: Boolean = false,
    val proactiveBriefingsEnabled: Boolean = false,
    val receiptQuickSaveEnabled: Boolean = false,
    val reviewQuickApproveEnabled: Boolean = false,
    val redactBeforeCloud: Boolean = true,
    val wifiOnlyForCloud: Boolean = false,
    val storeConversationHistory: Boolean = false,
    val preferredMode: AiMode = AiMode.AUTO
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
    val weatherHeadline: String,
    val weatherSummary: String,
    val discretionaryBudget: Double,
    val totalCommitted: Double,
    val totalLikely: Double,
    val pendingReviewCount: Int,
    val currentMonthSpent: Double,
    val topCategories: List<String>,
    val budgetWarnings: List<String>,
    val upcomingItems: List<String>
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
