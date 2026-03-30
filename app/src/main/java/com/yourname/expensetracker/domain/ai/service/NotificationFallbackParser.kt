package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.parser.ParsedTransaction

/**
 * Interface for AI-powered notification parsing fallback.
 * 
 * This service is invoked when all deterministic parsers fail to parse a notification.
 * It uses on-device AI (Gemini Nano via ML Kit) to extract transaction details
 * from notifications in any language or unstructured format.
 * 
 * The AI is advisory only - it returns structured data that must still pass through
 * the ConfidenceRouter for final routing decisions.
 * 
 * Implementation note: This should only use on-device AI (not cloud) for privacy
 * and latency reasons when processing notifications.
 */
interface NotificationFallbackParser {
    /**
     * Attempt to parse notification using AI when deterministic parsers fail.
     * 
     * This method should:
     * 1. Check if AI is enabled and available
     * 2. Build an appropriate prompt for the notification
     * 3. Call the on-device model
     * 4. Parse the AI response into structured data
     * 5. Return null if AI unavailable or unable to parse
     * 
     * @param title Notification title
     * @param text Notification text
     * @param bigText Expanded notification text (bigText)
     * @param packageName Source app package name
     * @return ParsedTransaction if AI successfully parsed, null otherwise
     */
    suspend fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        packageName: String
    ): ParsedTransaction?
}
