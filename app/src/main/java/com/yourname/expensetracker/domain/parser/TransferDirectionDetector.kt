package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects transfer direction (INCOMING/OUTGOING) from notification text.
 * 
 * Supports multiple languages and bank-specific patterns.
 * Uses a scoring system to handle ambiguous cases.
 */
@Singleton
class TransferDirectionDetector @Inject constructor() {
    
    companion object {
        private const val TAG = "TransferDirectionDetector"
    }
    
    // ==================== INCOMING PATTERNS (Money Received) ====================
    
    val incomingPatterns = listOf(
        // English - General
        Pattern.compile("""received\s+(?:from|via)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""credited\s+(?:to|with)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""deposit(?:ed)?\s+(?:received|to)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""money\s+received""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""transfer\s+(?:in|received|inbound)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""salary\s+(?:credited|received|deposited)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""refund\s+(?:received|credited|deposited)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""incoming\s+(?:transfer|payment)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""credit\s+transfer""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""wire\s+received""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""ACH\s+(?:credit|deposit)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""\+\s*[€$£¥]?\s*\d+[.,]?\d*"""),  // +€50.00 or +50.00
        
        // English - "From ... to you/me" patterns
        Pattern.compile("""from\s+(.+?)\s+(?:to|→|->|→)\s*(?:you|me|my|account)""", Pattern.CASE_INSENSITIVE),
        
        // Revolut-specific
        Pattern.compile("""revolut\s+to\s+revolut""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""added\s+money""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""topped\s+up""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""\+\s*[€$£¥]?\s*\d""", Pattern.CASE_INSENSITIVE),  // +€50
        
        // Greek (Ελληνικά)
        Pattern.compile("""ελήφθη\s+(?:από|απ)""", Pattern.CASE_INSENSITIVE),  // received from
        Pattern.compile("""πιστώθηκε\s+(?:σε|στον|στην|με)""", Pattern.CASE_INSENSITIVE),  // credited to/with
        Pattern.compile("""κατάθεση""", Pattern.CASE_INSENSITIVE),  // deposit
        Pattern.compile("""προς\s+λήψη""", Pattern.CASE_INSENSITIVE),  // to receive
        Pattern.compile("""μισθός""", Pattern.CASE_INSENSITIVE),  // salary
        Pattern.compile("""επιστροφή\s+χρημάτων""", Pattern.CASE_INSENSITIVE),  // refund
        Pattern.compile("""εισερχόμενη""", Pattern.CASE_INSENSITIVE),  // incoming
        Pattern.compile("""πίστωση""", Pattern.CASE_INSENSITIVE),  // credit
        Pattern.compile("""από\s+(.+?)\s+(?:σε|στο|στην)\s*(?:εσάς|μένα|λογαριασμό)""", Pattern.CASE_INSENSITIVE),  // from X to you/me/account
        
        // Greek Bank Codes
        Pattern.compile("""\bΠ[Ι.]?(?:ΙΣ)?\b"""),  // Π or ΠΙ or ΠΙΣ (Credit)
        Pattern.compile("""πιστωτικό""", Pattern.CASE_INSENSITIVE)  // credit
    )
    
    // ==================== OUTGOING PATTERNS (Money Sent) ====================
    
    val outgoingPatterns = listOf(
        // English - General
        Pattern.compile("""sent\s+(?:to|via)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""transferred\s+(?:to|out)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""transfer\s+(?:out|sent|outbound)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""withdrawal\s+(?:to|from)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""paid\s+(?:to|via)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""outgoing\s+(?:transfer|payment)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""debit\s+transfer""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""wire\s+sent""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""ACH\s+debit""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""bill\s+payment""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""-\s*[€$£¥]?\s*\d+[.,]?\d*"""),  // -€50.00 or -50.00
        
        // English - "To ... from you/me" patterns
        Pattern.compile("""to\s+(.+?)\s+(?:from|→|->|→)\s*(?:you|me|my|account)""", Pattern.CASE_INSENSITIVE),
        
        // Revolut-specific
        Pattern.compile("""you\s+paid""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""you\s+sent""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""payment\s+to""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""\-\s*[€$£¥]?\s*\d""", Pattern.CASE_INSENSITIVE),  // -€50
        Pattern.compile("""sent\s+via""", Pattern.CASE_INSENSITIVE),
        
        // Greek (Ελληνικά)
        Pattern.compile("""απεστάλη\s+(?:σε|προς)""", Pattern.CASE_INSENSITIVE),  // sent to
        Pattern.compile("""μεταφορά\s+σε""", Pattern.CASE_INSENSITIVE),  // transfer to
        Pattern.compile("""ανάληψη""", Pattern.CASE_INSENSITIVE),  // withdrawal
        Pattern.compile("""πληρωμή""", Pattern.CASE_INSENSITIVE),  // payment
        Pattern.compile("""εξερχόμενη""", Pattern.CASE_INSENSITIVE),  // outgoing
        Pattern.compile("""χρέωση""", Pattern.CASE_INSENSITIVE),  // debit/charge
        Pattern.compile("""σε\s+(.+?)\s+(?:από|απ)\s*(?:εσάς|μένα|λογαριασμό)""", Pattern.CASE_INSENSITIVE),  // to X from you/me/account
        
        // Greek Bank Codes
        Pattern.compile("""\bΧ[Ρ.]?(?:ΡΕ)?\b"""),  // Χ or ΧΡ or ΧΡΕ (Debit)
        Pattern.compile("""χρεωστικό""", Pattern.CASE_INSENSITIVE),  // debit
        
        // Payment-specific
        Pattern.compile("""purchase\s+(?:at|from)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""bought\s+(?:at|from)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""card\s+payment""", Pattern.CASE_INSENSITIVE)
    )
    
    // ==================== ACCOUNT NAME EXTRACTION ====================
    
    private val ACCOUNT_PATTERNS = listOf(
        // "From X" patterns
        Pattern.compile("""from[:\s]+([^\s,]+(?:\s+[^\s,]+){0,5})""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""from\s+(.+?)(?:\s+(?:to|→|->|via)|\s*$|\s+(?:for|on))""", Pattern.CASE_INSENSITIVE),
        
        // "To X" patterns  
        Pattern.compile("""to[:\s]+([^\s,]+(?:\s+[^\s,]+){0,5})""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""to\s+(.+?)(?:\s+(?:from|→|->|via)|\s*$|\s+(?:for|on))""", Pattern.CASE_INSENSITIVE),
        
        // Account-specific patterns
        Pattern.compile("""(?:account|acc|a/c)[:\s]+(.+?)(?:\s|$|,|\.)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:via|through)[:\s]+(.+?)(?:\s|$|,|\.)""", Pattern.CASE_INSENSITIVE),
        
        // Greek patterns
        Pattern.compile("""από[:\s]+(.+?)(?:\s+(?:σε|στο|στην)|\s*$)""", Pattern.CASE_INSENSITIVE),  // from
        Pattern.compile("""σε[:\s]+(.+?)(?:\s+(?:από|απ)|\s*$)""", Pattern.CASE_INSENSITIVE),  // to
        
        // Person/Entity names after keywords
        Pattern.compile("""(?:sent to|paid to|transferred to)[:\s]+(.+?)(?:\s+(?:for|on|via)|\s*$)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:received from|credited by)[:\s]+(.+?)(?:\s+(?:for|on|via)|\s*$)""", Pattern.CASE_INSENSITIVE)
    )
    
    // ==================== DIRECTION DETECTION ====================
    
    /**
     * Detects transfer direction from notification text.
     * 
     * @param title Notification title
     * @param text Notification text
     * @param bigText Notification bigText (expanded content)
     * @param transactionType The detected transaction type
     * @return TransferDirection if detected, null if cannot determine
     */
    fun detectDirection(
        title: String?,
        text: String?,
        bigText: String?,
        transactionType: TransactionType
    ): TransferDirection? {
        // Only detect for TRANSFER and DEPOSIT types
        if (transactionType != TransactionType.TRANSFER && 
            transactionType != TransactionType.DEPOSIT) {
            return null
        }
        
        val allText = listOfNotNull(title, text, bigText)
            .joinToString(" ") { it.trim() }
            .trim()
        
        if (allText.isBlank()) {
            return null
        }
        
        // Count pattern matches
        val incomingScore = incomingPatterns.count { pattern ->
            pattern.matcher(allText).find()
        }
        
        val outgoingScore = outgoingPatterns.count { pattern ->
            pattern.matcher(allText).find()
        }
        
        return when {
            incomingScore > outgoingScore -> TransferDirection.INCOMING
            outgoingScore > incomingScore -> TransferDirection.OUTGOING
            incomingScore > 0 && outgoingScore > 0 -> resolveAmbiguousCase(allText)
            else -> null
        }
    }
    
    /**
     * Extracts account name or counterparty from notification text.
     * 
     * @param title Notification title
     * @param text Notification text
     * @param bigText Notification bigText
     * @return Account/counterparty name if found, null otherwise
     */
    fun extractAccountName(
        title: String?,
        text: String?,
        bigText: String?
    ): String? {
        val allText = listOfNotNull(title, text, bigText)
            .joinToString(" ") { it.trim() }
            .trim()
        
        if (allText.isBlank()) {
            return null
        }
        
        for (pattern in ACCOUNT_PATTERNS) {
            val matcher = pattern.matcher(allText)
            if (matcher.find()) {
                val name = matcher.group(1)?.trim() ?: continue
                
                // Clean up the name
                val cleanedName = name
                    .replace(Regex("""[\n\r\t]"""), " ")  // Remove newlines
                    .replace(Regex("""\s+"""), " ")       // Normalize whitespace
                    .trim()
                
                // Validate length
                if (cleanedName.length in 2..50) {
                    return cleanedName
                }
            }
        }
        
        return null
    }
    
    /**
     * Resolves ambiguous cases where both incoming and outgoing patterns match.
     */
    private fun resolveAmbiguousCase(text: String): TransferDirection? {
        val lowerText = text.lowercase()
        
        return when {
            // Strong incoming indicators
            lowerText.contains("you received") ||
            lowerText.contains("you've received") ||
            lowerText.contains("credited to your") ||
            lowerText.contains("deposited to your") -> TransferDirection.INCOMING
            
            // Strong outgoing indicators
            lowerText.contains("you sent") ||
            lowerText.contains("you've sent") ||
            lowerText.contains("you paid") ||
            lowerText.contains("you've paid") ||
            lowerText.contains("sent from your") -> TransferDirection.OUTGOING
            
            // Amount with +/- signs (check context)
            text.contains("+€") || text.contains("+$") || text.contains("+£") -> {
                // Check if it's clearly outgoing context
                if (lowerText.contains("sent") || lowerText.contains("paid to")) {
                    TransferDirection.OUTGOING
                } else {
                    TransferDirection.INCOMING
                }
            }
            
            text.contains("-€") || text.contains("-$") || text.contains("-£") -> {
                // Check if it's clearly incoming context
                if (lowerText.contains("received") || lowerText.contains("credited")) {
                    TransferDirection.INCOMING
                } else {
                    TransferDirection.OUTGOING
                }
            }
            
            // Greek disambiguation
            lowerText.contains("ελήφθη") ||  // received
            lowerText.contains("πιστώθηκε") ||  // credited
            lowerText.contains("πιστωτικό") -> TransferDirection.INCOMING  // credit
            
            lowerText.contains("απεστάλη") ||  // sent
            lowerText.contains("χρεωστικό") ||  // debit
            lowerText.contains("χρέωση") -> TransferDirection.OUTGOING  // charge
            
            // Still ambiguous
            else -> null
        }
    }
    
    /**
     * Gets detection confidence based on pattern match quality.
     * 
     * @return Float between 0.0 and 1.0
     */
    fun getDetectionConfidence(
        title: String?,
        text: String?,
        bigText: String?,
        transactionType: TransactionType
    ): Float {
        val direction = detectDirection(title, text, bigText, transactionType)
            ?: return 0.0f
        
        val allText = listOfNotNull(title, text, bigText).joinToString(" ")
        
        val relevantPatterns = when (direction) {
            TransferDirection.INCOMING -> incomingPatterns
            TransferDirection.OUTGOING -> outgoingPatterns
        }
        
        // Count how many patterns matched
        val matchCount = relevantPatterns.count { it.matcher(allText).find() }
        
        // More matches = higher confidence (up to 0.95)
        return when {
            matchCount >= 3 -> 0.95f
            matchCount == 2 -> 0.85f
            matchCount == 1 -> 0.75f
            else -> 0.65f
        }
    }
}