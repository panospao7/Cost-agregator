// TODO (P8-P1-1): Unify into a single CloudPayloadRedactor interface covering all
// provider types (receipt, query, briefing, dedupe). Currently RedactionSanitizer
// and CloudPiiSanitizer have overlapping but separate responsibilities.

package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.domain.common.sha256Prefix
import javax.inject.Inject

interface RedactionSanitizer {
    fun sanitizeMerchant(value: String): String
}

class DefaultRedactionSanitizer @Inject constructor() : RedactionSanitizer {
    override fun sanitizeMerchant(value: String): String {
        val trimmed = value.trim().take(80)
        if (trimmed.isBlank()) return "merchant_unknown"
        return "merchant_${trimmed.sha256Prefix()}"
    }
}
