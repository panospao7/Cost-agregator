package com.yourname.expensetracker.domain.provenance

enum class DuplicateSourceLinkPolicy {
    LINK_SOURCE_TO_EXISTING,
    RECORD_ATTEMPT_ONLY,
    DO_NOT_LINK
}
