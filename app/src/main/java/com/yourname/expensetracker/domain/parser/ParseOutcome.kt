package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.provenance.ParseProvenance

sealed interface ParseOutcome {
    val parsed: ParsedTransaction?
    val provenance: ParseProvenance

    data class Parsed(
        override val parsed: ParsedTransaction,
        override val provenance: ParseProvenance
    ) : ParseOutcome

    data class NoParse(
        override val provenance: ParseProvenance
    ) : ParseOutcome {
        override val parsed: ParsedTransaction? = null
    }
}
