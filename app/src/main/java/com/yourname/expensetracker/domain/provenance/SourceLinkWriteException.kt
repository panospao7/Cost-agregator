package com.yourname.expensetracker.domain.provenance

/**
 * Thrown when a source-link write fails fatally during expense creation.
 *
 * This exception causes the entire transaction (expense insert + CREATED event)
 * to be rolled back, ensuring that no expense exists without its provenance.
 */
class SourceLinkWriteException(message: String) : IllegalStateException(message)
