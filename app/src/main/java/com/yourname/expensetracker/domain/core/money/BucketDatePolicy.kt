package com.yourname.expensetracker.domain.core.money

/**
 * Policy for determining the date used when converting currency buckets.
 */
sealed interface BucketDatePolicy {
    /** Each bucket must have a non-null bucketDate; fail if missing. */
    data object RequireBucketDate : BucketDatePolicy
    /** Use a single fixed date for all buckets. */
    data class FixedDate(val atMillis: Long) : BucketDatePolicy
    /** Use latest available rate (no date needed). */
    data object Latest : BucketDatePolicy
}
