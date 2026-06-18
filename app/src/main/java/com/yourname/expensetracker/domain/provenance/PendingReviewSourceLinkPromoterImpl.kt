package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.data.database.dao.EntitySourceLinkDao
import com.yourname.expensetracker.data.database.entity.EntitySourceLink
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PR3: Default implementation of [PendingReviewSourceLinkPromoter].
 *
 * Reads all source links for target=PENDING_REVIEW, targetId=pendingReviewId,
 * transforms each into a new payload targeting EXPENSE with role/status mapping,
 * and writes them via [SourceLinkWriter.linkExpense].
 *
 * Also writes a direct PENDING_REVIEW / APPROVED_FROM link with isPrimary=true.
 *
 * Does NOT open its own transaction — callers must wrap in a Room transaction.
 */
@Singleton
class PendingReviewSourceLinkPromoterImpl @Inject constructor(
    private val entitySourceLinkDao: EntitySourceLinkDao,
    private val sourceLinkWriter: SourceLinkWriter
) : PendingReviewSourceLinkPromoter {

    override suspend fun promotePendingReviewLinksToExpense(
        pendingReviewId: Long,
        expenseId: Long,
        correlationId: String?,
        source: ExpenseSource
    ): PendingReviewPromotionResult {
        val existingLinks = entitySourceLinkDao.getForTarget(
            targetType = TargetEntityType.PENDING_REVIEW.name,
            targetId = pendingReviewId
        )

        if (existingLinks.isEmpty()) {
            // Still write the primary APPROVED_FROM link even if no links to promote
            Timber.d("No source links to promote from pending review reviewId=%d", pendingReviewId)
        }

        var inserted = 0
        var alreadyExists = 0
        var failed = 0
        val failures = mutableListOf<String>()
        val sourceTypeName = source.name

        // Promote each existing link
        for (link in existingLinks) {
            val promotedPayload = transformToExpensePayload(link, expenseId, pendingReviewId, sourceTypeName)
            if (promotedPayload == null) {
                // Skip: FAILED or SUPERSEDED status
                continue
            }

            val result = sourceLinkWriter.linkExpense(
                expenseId = expenseId,
                payload = promotedPayload,
                correlationId = correlationId
            )
            when (result) {
                is SourceLinkWriteResult.Created -> inserted++
                is SourceLinkWriteResult.AlreadyExists -> alreadyExists++
                is SourceLinkWriteResult.Failed -> {
                    failed++
                    failures.add("Promotion failed for link ${link.id}: ${result.errorClass}")
                    Timber.w("Source link promotion failed for linkId=%d: %s", link.id, result.errorClass)
                }
            }
        }

        // Always write a direct PENDING_REVIEW / APPROVED_FROM link with isPrimary=true
        val approvedFromPayload = SourceLinkPayload(
            sourceType = sourceTypeName,
            sourceEntityType = SourceEntityType.PENDING_REVIEW,
            sourceEntityLocalId = pendingReviewId,
            role = SourceLinkRole.APPROVED_FROM,
            status = SourceLinkStatus.ACTIVE,
            isPrimary = true,
            metadata = SafeProvenanceMetadata.fromMap(
                mapOf("stage" to "review_approval_promotion")
            )
        )
        val approvedResult = sourceLinkWriter.linkExpense(
            expenseId = expenseId,
            payload = approvedFromPayload,
            correlationId = correlationId
        )
        when (approvedResult) {
            is SourceLinkWriteResult.Created -> inserted++
            is SourceLinkWriteResult.AlreadyExists -> alreadyExists++
            is SourceLinkWriteResult.Failed -> {
                failed++
                failures.add("APPROVED_FROM link failed: ${approvedResult.errorClass}")
                Timber.w("APPROVED_FROM link failed for expenseId=%d: %s", expenseId, approvedResult.errorClass)
            }
        }

        val attempted = existingLinks.size + 1 // +1 for the APPROVED_FROM link
        return PendingReviewPromotionResult(
            attempted = attempted,
            inserted = inserted,
            alreadyExists = alreadyExists,
            failed = failed,
            failures = failures
        )
    }

    /**
     * Transforms a pending-review source link into an expense-targeting payload.
     *
     * Role transformation:
     * - REVIEWED_FROM → CREATED_FROM
     * - Others preserved
     *
     * Status transformation:
     * - ACTIVE → ACTIVE
     * - DUPLICATE → DUPLICATE
     * - REDACTED → REDACTED
     * - LEGACY_PARTIAL → LEGACY_PARTIAL
     * - FAILED/SUPERSEDED → skip (return null)
     *
     * Promotion metadata is added to the link's existing metadata.
     */
    private fun transformToExpensePayload(
        link: EntitySourceLink,
        expenseId: Long,
        pendingReviewId: Long,
        sourceTypeName: String
    ): SourceLinkPayload? {
        // Skip FAILED or SUPERSEDED links
        val status = try {
            SourceLinkStatus.valueOf(link.linkStatus)
        } catch (e: IllegalArgumentException) {
            SourceLinkStatus.FAILED
        }
        if (status == SourceLinkStatus.FAILED || status == SourceLinkStatus.SUPERSEDED) {
            return null
        }

        // Transform role
        val role = try {
            val originalRole = SourceLinkRole.valueOf(link.linkRole)
            if (originalRole == SourceLinkRole.REVIEWED_FROM) {
                SourceLinkRole.CREATED_FROM
            } else {
                originalRole
            }
        } catch (e: IllegalArgumentException) {
            SourceLinkRole.CREATED_FROM
        }

        // Build promotion metadata on top of existing metadata
        val existingMetadata = try {
            link.metadataJson?.let { JSONObject(it) } ?: JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        existingMetadata.put("promotedFromTargetType", TargetEntityType.PENDING_REVIEW.name)
        existingMetadata.put("promotedFromPendingReviewId", pendingReviewId)
        existingMetadata.put("promotedFromSourceLinkId", link.id)
        existingMetadata.put("promotedFromRole", link.linkRole)

        val promotionMetadataMap = mutableMapOf<String, Any?>()
        for (key in existingMetadata.keys()) {
            promotionMetadataMap[key] = existingMetadata.get(key)
        }
        val metadata = SafeProvenanceMetadata.fromMap(promotionMetadataMap)

        return SourceLinkPayload(
            sourceType = sourceTypeName,
            sourceEntityType = try {
                SourceEntityType.valueOf(link.sourceEntityType)
            } catch (e: IllegalArgumentException) {
                SourceEntityType.UNKNOWN
            },
            sourceEntityLocalId = link.sourceEntityLocalId,
            // Do not forward external IDs — they are already hashed for the original
            // PENDING_REVIEW target and would be double-hashed on the expense target.
            externalId = null,
            externalFingerprint = null,
            providerId = link.providerId,
            importBatchId = link.importBatchId,
            importRowNumber = link.importRowNumber,
            role = role,
            status = status,
            confidence = link.confidence,
            // Promoted links are never primary — the APPROVED_FROM link is the primary one
            isPrimary = false,
            metadata = metadata
        )
    }
}
