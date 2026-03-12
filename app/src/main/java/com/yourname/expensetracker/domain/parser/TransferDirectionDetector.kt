package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

// Combine CASE_INSENSITIVE with UNICODE_CASE so Greek (and all Unicode) characters
// are also matched case-insensitively. CASE_INSENSITIVE alone only handles ASCII.
private val CI = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

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
        Pattern.compile("""received.*\s+from""", CI),
        Pattern.compile("""received.*\s+via""", CI),
        Pattern.compile("""you\s+received""", CI),          // "you received" standalone
        Pattern.compile("""(?:has\s+been\s+|been\s+)?credited""", CI),  // credited (any form)
        Pattern.compile("""deposit(?:ed)?""", CI),           // deposited / deposit standalone
        Pattern.compile("""credited.*\s+(?:to|with)""", CI),
        Pattern.compile("""deposit(?:ed)?.*\s+(?:received|to)""", CI),
        Pattern.compile("""money\s+received""", CI),
        Pattern.compile("""transfer\s+(?:in|received|inbound)""", CI),
        Pattern.compile("""salary\s+(?:credited|received|deposited|deposit)""", CI),
        Pattern.compile("""refund""", CI),                   // refund standalone
        Pattern.compile("""refund\s+(?:received|credited|deposited|processed)""", CI),
        Pattern.compile("""refunded""", CI),
        Pattern.compile("""incoming\s+(?:transfer|payment)""", CI),
        Pattern.compile("""credit\s+transfer""", CI),
        Pattern.compile("""wire\s+received""", CI),
        Pattern.compile("""ACH\s+(?:credit|deposit)""", CI),
        Pattern.compile("""\+\s*[€$£¥]?\s*\d+[.,]?\d*"""),  // +€50.00 or +50.00

        // English - "From ... to you/me" patterns
        Pattern.compile("""from\s+(.+?)\s+(?:to|→|->|→)\s*(?:you|me|my|account)""", CI),

        // Revolut-specific
        Pattern.compile("""revolut\s+to\s+revolut""", CI),
        Pattern.compile("""added\s+money""", CI),
        Pattern.compile("""topped\s+up""", CI),
        Pattern.compile("""\+\s*[€$£¥]?\s*\d""", CI),  // +€50

        // Greek (Ελληνικά)
        Pattern.compile("""ελήφθη\s+(?:από|απ)""", CI),  // received from
        Pattern.compile("""πιστώθηκε\s+(?:σε|στον|στην|με)""", CI),  // credited to/with
        Pattern.compile("""κατάθεση""", CI),  // deposit
        Pattern.compile("""καταθεση""", CI),  // deposit without accent
        Pattern.compile("""κατ[αά][θά]θεση""", CI),  // deposit various
        Pattern.compile("""προς\s+λήψη""", CI),  // to receive
        Pattern.compile("""μισθός""", CI),  // salary
        Pattern.compile("""μισθοδοσία""", CI),  // salary deposit
        Pattern.compile("""μισθοδοσια""", CI),  // salary deposit without accent
        Pattern.compile("""επιστροφή\s+χρημάτων""", CI),  // refund
        Pattern.compile("""εισερχόμενη""", CI),  // incoming
        Pattern.compile("""εισπραξη""", CI),  // collection
        Pattern.compile("""είσπραξη""", CI),  // collection with accent
        Pattern.compile("""πίστωση""", CI),  // credit
        Pattern.compile("""πιστωση""", CI),  // credit without accent
        Pattern.compile("""λογαριασμού""", CI),  // account
        Pattern.compile("""λογαριασμου""", CI),  // account without accent
        Pattern.compile("""από\s+(.+?)\s+(?:σε|στο|στην)\s*(?:εσάς|μένα|λογαριασμό)""", CI),  // from X to you/me/account
        Pattern.compile("""εμβασμα""", CI),  // transfer
        Pattern.compile("""εμβασμα\s+""", CI),  // transfer (with space after)

        // Greek Bank Codes — Π (Pi) prefix signals a credit/incoming transaction
        Pattern.compile("""(?:^|\s)[ΠπPp]\s+\S""", CI),  // "Π <word>" at start or after space (Credit code)
        Pattern.compile("""πιστωτικό""", CI),  // credit
        Pattern.compile("""πιστωτικο""", CI)  // credit without accent
    )
    
    // ==================== OUTGOING PATTERNS (Money Sent) ====================
    
    val outgoingPatterns = listOf(
        // English - General
        Pattern.compile("""sent\s+(?:to|via)""", CI),
        Pattern.compile("""transferred\s+(?:to|out)""", CI),
        Pattern.compile("""transfer\s+(?:out|sent|outbound)""", CI),
        Pattern.compile("""withdrawal\s+(?:to|from)""", CI),
        Pattern.compile("""withdrew""", CI),                  // "withdrew" standalone
        Pattern.compile("""paid\s+(?:to|via)""", CI),
        Pattern.compile("""outgoing\s+(?:transfer|payment)""", CI),
        Pattern.compile("""debit\s+transfer""", CI),
        Pattern.compile("""wire\s+sent""", CI),
        Pattern.compile("""ACH\s+debit""", CI),
        Pattern.compile("""bill\s+payment""", CI),
        Pattern.compile("""-\s*[€$£¥]?\s*\d+[.,]?\d*"""),  // -€50.00 or -50.00
        Pattern.compile("""debited""", CI),
        Pattern.compile("""has been debited""", CI),

        // English - "To ... from you/me" patterns
        Pattern.compile("""to\s+(.+?)\s+(?:from|→|->|→)\s*(?:you|me|my|account)""", CI),

        // Revolut-specific
        Pattern.compile("""you\s+paid""", CI),
        Pattern.compile("""you\s+sent""", CI),
        Pattern.compile("""payment\s+to""", CI),
        Pattern.compile("""\-\s*[€$£¥]?\s*\d""", CI),  // -€50
        Pattern.compile("""sent\s+via""", CI),
        Pattern.compile("""transfer\s+to""", CI),
        Pattern.compile("""paid\s+to""", CI),

        // Greek (Ελληνικά)
        Pattern.compile("""απεστάλη\s+(?:σε|προς)""", CI),  // sent to
        Pattern.compile("""μεταφορά\s+σε""", CI),  // transfer to
        Pattern.compile("""μεταφορα\s+σε""", CI),  // transfer to without accent
        Pattern.compile("""μεταφορά""", CI),  // transfer
        Pattern.compile("""μεταφορα""", CI),  // transfer without accent
        Pattern.compile("""ανάληψη""", CI),  // withdrawal
        Pattern.compile("""αναληψη""", CI),  // withdrawal without accent
        Pattern.compile("""πληρωμή""", CI),  // payment
        Pattern.compile("""πληρωμη""", CI),  // payment without accent
        Pattern.compile("""εξερχόμενη""", CI),  // outgoing
        Pattern.compile("""εξερχομενη""", CI),  // outgoing without accent
        Pattern.compile("""χρέωση""", CI),  // debit/charge
        Pattern.compile("""χρεωση""", CI),  // debit without accent
        Pattern.compile("""χρεωση\s+λογαριασμού""", CI),  // account debit
        Pattern.compile("""χρεωση\s+λογαριασμου""", CI),  // account debit without accent
        Pattern.compile("""σε\s+(.+?)\s+(?:από|απ)\s*(?:εσάς|μένα|λογαριασμό)""", CI),  // to X from you/me/account

        // Greek Bank Codes — Χ (Chi) prefix signals a debit/outgoing transaction
        Pattern.compile("""(?:^|\s)[ΧχXx]\s+\S""", CI),  // "Χ <word>" at start or after space (Debit code)
        Pattern.compile("""χρεωστικό""", CI),  // debit
        Pattern.compile("""χρεωστικο""", CI),  // debit without accent

        // Payment-specific
        Pattern.compile("""purchase\s+(?:at|from)""", CI),
        Pattern.compile("""bought\s+(?:at|from)""", CI),
        Pattern.compile("""card\s+payment""", CI)
    )
    
    // ==================== ACCOUNT NAME EXTRACTION ====================
    
    private val ACCOUNT_PATTERNS = listOf(
        // "From X" patterns
        Pattern.compile("""from[:\s]+([^\s,]+(?:\s+[^\s,]+){0,5})""", CI),
        Pattern.compile("""from\s+(.+?)(?:\s+(?:to|→|->|via)|\s*$|\s+(?:for|on))""", CI),

        // "To X" patterns  
        Pattern.compile("""to[:\s]+([^\s,]+(?:\s+[^\s,]+){0,5})""", CI),
        Pattern.compile("""to\s+(.+?)(?:\s+(?:from|→|->|via)|\s*$|\s+(?:for|on))""", CI),

        // Account-specific patterns
        Pattern.compile("""(?:account|acc|a/c)[:\s]+(.+?)(?:\s|$|,|\.)""", CI),
        Pattern.compile("""(?:via|through)[:\s]+(.+?)(?:\s|$|,|\.)""", CI),

        // Greek patterns
        Pattern.compile("""από[:\s]+(.+?)(?:\s+(?:σε|στο|στην)|\s*$)""", CI),  // from
        Pattern.compile("""σε[:\s]+(.+?)(?:\s+(?:από|απ)|\s*$)""", CI),  // to
        Pattern.compile("""προς\s+(.+?)(?:\s|$)""", CI),  // towards/to
        Pattern.compile("""μεταφορά\s+(?:προς|σε)\s+(.+)""", CI),  // transfer to
        Pattern.compile("""μεταφορα\s+(?:προς|σε)\s+(.+)""", CI),  // transfer to without accent

        // Person/Entity names after keywords
        Pattern.compile("""(?:sent to|paid to|transferred to)[:\s]+(.+?)(?:\s+(?:for|on|via)|\s*$)""", CI),
        Pattern.compile("""(?:received from|credited by)[:\s]+(.+?)(?:\s+(?:for|on|via)|\s*$)""", CI)
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
        // WITHDRAWAL is always OUTGOING
        if (transactionType == TransactionType.WITHDRAWAL) {
            return TransferDirection.OUTGOING
        }
        
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