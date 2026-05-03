package com.yourname.expensetracker.domain.config

/**
 * Centralized configuration constants for the ExpenseTracker app.
 * Replaces magic numbers and hardcoded thresholds throughout the codebase.
 */
object AppConfig {
    // Amount limits
    const val MAX_TRANSACTION_AMOUNT = 1_000_000.0
    const val MAX_RECEIPT_AMOUNT = 50_000.0

    // Recurring detection thresholds
    const val RECURRING_AMOUNT_VARIANCE_THRESHOLD = 0.35
    const val RECURRING_CONFIDENCE_THRESHOLD = 0.50
    const val RECURRING_MIN_OCCURRENCES = 3

    // Cache expiry
    const val MERCHANT_CACHE_EXPIRY_MS = 300_000L  // 5 minutes
    const val SOURCE_STATS_CACHE_EXPIRY_MS = 300_000L

    // Notification cooldowns
    const val DAILY_NOTIFICATION_COOLDOWN_MS = 6 * 60 * 60 * 1000L  // 6 hours
    const val WEEKLY_NOTIFICATION_COOLDOWN_MS = 24 * 60 * 60 * 1000L  // 24 hours

    // Flow timeouts
    const val FLOW_SUBSCRIPTION_TIMEOUT_MS = 5000L
    const val DEBOUNCE_DELAY_MS = 300L

    // Forecasting
    const val LIKELY_EXPENSE_WEIGHT = 0.7
    const val DEFAULT_HORIZON_DAYS = 31

    // OCR
    const val MAX_OCR_IMAGE_DIMENSION = 1024
    const val MAX_OCR_FILE_SIZE_MB = 20

    // Budget thresholds
    const val DEFAULT_WARNING_THRESHOLD = 0.80f
    const val DEFAULT_CRITICAL_THRESHOLD = 0.95f

    // Duplicate detection window
    const val DUPLICATE_WINDOW_MS = 300_000L  // 5 minutes

    // ── Geolocation & Maps (Segment 17) ──────────────────────────────────────

    object Location {
        /** Age threshold below which a transaction is considered "recent enough"
         *  to bias Nominatim with the device's current GPS coordinates. */
        const val RECENT_TRANSACTION_THRESHOLD_MS = 2 * 60 * 60 * 1000L  // 2 hours

        /** Nominatim rate-limit: minimum gap between successive requests (ms). */
        const val NOMINATIM_MIN_INTERVAL_MS = 1_100L  // 1.1 sec → safe under 1 req/sec policy

        /** User-Agent header required by Nominatim usage policy. */
        const val NOMINATIM_USER_AGENT = "ExpenseTracker/Android"

        /** Nominatim base URL. */
        const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"

        /** Overpass API base URL. */
        const val OVERPASS_BASE_URL = "https://overpass-api.de/api/interpreter"

        /** Radius in metres for Overpass POI lookup around device location.
         *  150 m was too small for typical urban GPS accuracy (~50 m CEP);
         *  250 m covers the 95th-percentile scatter without returning too many POIs. */
        const val OVERPASS_SEARCH_RADIUS_M = 250

        /** Haversine radius (km) within which a user correction is considered area-local. */
        const val CORRECTION_AREA_RADIUS_KM = 5.0f

        /** How long a merchant_locations cache entry remains valid before re-geocoding. */
        const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000L  // 30 days

        /** Max results to request from Nominatim per query. */
        const val NOMINATIM_MAX_RESULTS = 5

        /** Greece bounding box used as Nominatim viewbox bias for name-only queries. */
        const val GREECE_VIEWBOX = "19.3,34.8,29.6,42.0"

        /** Country code bias for Nominatim. */
        const val GREECE_COUNTRY_CODE = "gr"

        // Location source constants (stored in Expense.locationSource)
        const val SOURCE_NOMINATIM_GPS_BIAS = "NOMINATIM_GPS_BIAS"
        const val SOURCE_NOMINATIM_NAME_ONLY = "NOMINATIM_NAME_ONLY"
        const val SOURCE_OVERPASS_POI = "OVERPASS_POI"
        const val SOURCE_USER_MANUAL = "USER_MANUAL"
        const val SOURCE_USER_CONFIRMED_POI = "USER_CONFIRMED_POI"
        const val SOURCE_DEVICE_GPS = "DEVICE_GPS"
        const val SOURCE_PHOTON = "PHOTON"
        const val SOURCE_GEOAPIFY = "GEOAPIFY"
        const val SOURCE_GOOGLE_PLACES = "GOOGLE_PLACES"

        // Multi-service geocoding base URLs
        const val PHOTON_BASE_URL = "https://photon.komoot.io"
        const val GEOAPIFY_BASE_URL = "https://api.geoapify.com"
        const val GOOGLE_PLACES_BASE_URL = "https://places.googleapis.com"
    }

    // ── AI Layer (Phase 1) ────────────────────────────────────────────────────

    object Ai {
        // TTLs
        /** How long a dashboard briefing artifact stays fresh before regeneration. */
        const val DASHBOARD_BRIEFING_TTL_MS = 24L * 60 * 60 * 1000L      // 24 hours

        /** How long a review explanation artifact stays fresh before regeneration. */
        const val REVIEW_EXPLANATION_TTL_MS = 30L * 24 * 60 * 60 * 1000L // 30 days

        /** How long a receipt assist artifact stays fresh before regeneration. */
        const val RECEIPT_ASSIST_TTL_MS = 30L * 24 * 60 * 60 * 1000L // 30 days

        /** How long review capture assist artifacts stay fresh before regeneration. */
        const val REVIEW_CAPTURE_ASSIST_TTL_MS = 30L * 24 * 60 * 60 * 1000L // 30 days

        /** How long receipt item categorization artifacts stay fresh before regeneration. */
        const val RECEIPT_ITEMS_TTL_MS = 30L * 24 * 60 * 60 * 1000L // 30 days

        // Input size limits (cloud privacy)
        /** Max characters of raw notification text sent to the cloud for a review explanation. */
        const val MAX_REVIEW_TEXT_CHARS_FOR_CLOUD = 500

        /** Max characters for any single AI briefing response stored in the artifact. */
        const val MAX_BRIEFING_LENGTH_CHARS = 600

        // Prompt versioning — bump to invalidate cached artifacts for that capability
        const val PROMPT_VERSION_DASHBOARD = "v1"
        const val PROMPT_VERSION_REVIEW    = "v1"
        const val PROMPT_VERSION_QUERY     = "v1"
        const val PROMPT_VERSION_RECEIPT   = "v1"
        const val PROMPT_VERSION_RECEIPT_ITEMS = "v1" // NEW: Receipt item categorization
        const val PROMPT_VERSION_CATEGORIZATION = "v1"
        const val PROMPT_VERSION_DEDUPE    = "v1"
        const val DASHBOARD_BRIEFING_CLOUD_PROVIDER = "google-ai-studio"
        const val DASHBOARD_BRIEFING_CLOUD_MODEL = "gemini-2.5-flash"
        const val QUERY_INTERPRETATION_CLOUD_PROVIDER = "google-ai-studio"
        const val QUERY_INTERPRETATION_CLOUD_MODEL = "gemini-2.5-flash"
        const val REVIEW_EXPLANATION_CLOUD_PROVIDER = "google-ai-studio"
        const val REVIEW_EXPLANATION_CLOUD_MODEL = "gemini-2.5-flash"
        const val RECEIPT_ASSIST_CLOUD_PROVIDER = "google-ai-studio"
        const val RECEIPT_ASSIST_CLOUD_MODEL = "gemini-2.5-flash"
        const val CATEGORIZATION_ASSIST_CLOUD_PROVIDER = "google-ai-studio"
        const val CATEGORIZATION_ASSIST_CLOUD_MODEL = "gemini-2.5-flash"
        const val DEDUPE_JUDGE_CLOUD_PROVIDER = "google-ai-studio"
        const val DEDUPE_JUDGE_CLOUD_MODEL = "gemini-2.5-flash"
        const val RECEIPT_ITEM_CATEGORIZATION_CLOUD_PROVIDER = "google-ai-studio"
        const val RECEIPT_ITEM_CATEGORIZATION_CLOUD_MODEL = "gemini-2.5-flash"
        const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DASHBOARD_BRIEFING_TIMEOUT_SECONDS = 12L
        const val QUERY_INTERPRETATION_TIMEOUT_SECONDS = 12L
        const val REVIEW_EXPLANATION_TIMEOUT_SECONDS = 12L
        const val RECEIPT_ASSIST_TIMEOUT_SECONDS = 12L
        const val CATEGORIZATION_ASSIST_TIMEOUT_SECONDS = 10L
        const val DEDUPE_JUDGE_TIMEOUT_SECONDS = 10L
        const val MAX_REVIEW_EXPLANATION_HEADLINE_CHARS = 80
        const val MAX_REVIEW_EXPLANATION_BODY_CHARS = 320
        const val MAX_REVIEW_EXPLANATION_CAUTION_CHARS = 140
        const val DASHBOARD_BRIEFING_MAX_OUTPUT_TOKENS = 256
        const val QUERY_INTERPRETATION_MAX_OUTPUT_TOKENS = 320
        const val REVIEW_EXPLANATION_MAX_OUTPUT_TOKENS = 384
        const val RECEIPT_ASSIST_MAX_OUTPUT_TOKENS = 384
        const val CATEGORIZATION_ASSIST_MAX_OUTPUT_TOKENS = 220
        const val DEDUPE_JUDGE_MAX_OUTPUT_TOKENS = 220
        const val CLOUD_RECEIPT_ITEM_MAX_TOKENS = 300

        // On-device (Gemini Nano) constants
        const val ON_DEVICE_CATEGORIZATION_TEMPERATURE = 0.1f
        const val ON_DEVICE_CATEGORIZATION_MAX_TOKENS = 150
        const val ON_DEVICE_PROVIDER_NAME = "mlkit-genai-nano"
        const val ON_DEVICE_CATEGORIZATION_MODEL = "gemini-nano"
        const val ON_DEVICE_REVIEW_TEMPERATURE = 0.2f
        const val ON_DEVICE_REVIEW_MAX_TOKENS = 180
        const val ON_DEVICE_REVIEW_MODEL = "gemini-nano-review"
        const val ON_DEVICE_RECEIPT_TEMPERATURE = 0.1f
        const val ON_DEVICE_RECEIPT_MAX_TOKENS = 220
        const val ON_DEVICE_RECEIPT_MODEL = "gemini-nano-receipt"
        const val ON_DEVICE_DEDUPE_TEMPERATURE = 0.1f
        const val ON_DEVICE_DEDUPE_MAX_TOKENS = 180
        const val ON_DEVICE_DEDUPE_MODEL = "gemini-nano-dedupe"
        const val ON_DEVICE_RECEIPT_ITEM_TEMPERATURE = 0.1f
        const val ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS = 300
        const val ON_DEVICE_RECEIPT_ITEM_MODEL = "gemini-nano-receipt-items"
        const val ON_DEVICE_QUERY_TEMPERATURE = 0.1f
        const val ON_DEVICE_QUERY_MAX_TOKENS = 220
        const val ON_DEVICE_QUERY_MODEL = "gemini-nano-query"
        const val ON_DEVICE_QUERY_TIMEOUT_MS = 30_000L  // 30 seconds
        const val ON_DEVICE_BRIEFING_TEMPERATURE = 0.2f
        const val ON_DEVICE_BRIEFING_MAX_TOKENS = 180
        const val ON_DEVICE_BRIEFING_MODEL = "gemini-nano-briefing"
        
        // On-device notification parsing constants
        const val ON_DEVICE_NOTIFICATION_TEMPERATURE = 0.1f
        const val ON_DEVICE_NOTIFICATION_MAX_TOKENS = 180
        const val ON_DEVICE_NOTIFICATION_MODEL = "gemini-nano-notification"
        const val MAX_NOTIFICATION_TEXT_CHARS_FOR_AI = 300

        // Query interpretation / assistant limits
        const val MAX_QUERY_INPUT_CHARS = 400
        const val MAX_QUERY_HISTORY_TURNS_FOR_MODEL = 8
        const val MAX_QUERY_RESULT_ROWS = 8
        const val MAX_QUERY_GROUP_BUCKETS = 8
        const val MAX_QUERY_CLARIFICATION_OPTIONS = 4
        const val MAX_RECEIPT_OCR_CHARS_FOR_AI = 4_000
        const val MAX_CAPTURE_SUPPORTING_TEXT_CHARS = 800
        const val MAX_CATEGORY_OPTIONS_FOR_AI = 30
        const val MAX_DEDUPE_CANDIDATES_FOR_AI = 5
        const val MIN_RECEIPT_CONFIDENCE_FOR_AI_FALLBACK = 0.70f
        const val MIN_CATEGORY_CONFIDENCE_FOR_AI_FALLBACK = 0.70f

        // Worker tags
        const val WORK_NAME_DAILY_BRIEFING = "ai_daily_briefing"
    }
}
