package com.yourname.expensetracker.domain.location

import kotlin.math.floor

/**
 * Shared coarse merchant-area grid used by both SQL clustering and Kotlin cache keys.
 */
object MerchantLocationGrid {
    const val GRID_DEGREES = 0.045
    private const val GRID_DEGREES_SQL = "0.045"

    // SQLite CAST() truncates toward zero, so negative coordinates need an explicit
    // adjustment to match true mathematical floor().
    const val LATITUDE_BUCKET_SQL: String =
        "CAST((latitude / $GRID_DEGREES_SQL) AS INTEGER) - CASE WHEN (latitude / $GRID_DEGREES_SQL) < CAST((latitude / $GRID_DEGREES_SQL) AS INTEGER) THEN 1 ELSE 0 END"

    const val LONGITUDE_BUCKET_SQL: String =
        "CAST((longitude / $GRID_DEGREES_SQL) AS INTEGER) - CASE WHEN (longitude / $GRID_DEGREES_SQL) < CAST((longitude / $GRID_DEGREES_SQL) AS INTEGER) THEN 1 ELSE 0 END"

    fun bucketCoordinate(value: Double): Long = floor(value / GRID_DEGREES).toLong()
}
