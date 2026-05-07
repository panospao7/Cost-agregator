// TODO (ARCH-04 Stage 2): Migrate remaining providers (receipt, review, briefing, dedupe, warranty)
// CloudQueryInterpretationService already migrated in Stage 1.

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
