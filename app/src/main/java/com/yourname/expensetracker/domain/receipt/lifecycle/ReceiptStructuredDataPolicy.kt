package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * The ONLY persisted representation of a redacted receipt line item
 * (RP-12 12b, P3-007). This exact schema is the contract:
 * [quantity], [unitPrice], [totalPrice], [currency]. The [categoryId] /
 * [categoryName] fields exist for a future approved-category carry-through
 * and are NEVER populated at insert time (no category has been approved when
 * the row is written). Item name, description, brand, SKU, and free-text
 * notes are never part of this schema.
 */
data class RedactedReceiptItem(
    val quantity: Double?,
    val unitPrice: Double?,
    val totalPrice: Double,
    val currency: String,
    val categoryId: Long? = null,
    val categoryName: String? = null
)

/**
 * RP-12 12b (P3-007): the single RawStorageMode transformer applied BEFORE a
 * receipt row is inserted — one source shaping what structured receipt data is
 * persisted:
 *
 *  - [RawStorageMode.STORE_RAW]: persist the produced structured items/merchant
 *    unchanged ([fullItemsJson] passes through — no format drift);
 *  - [RawStorageMode.STORE_REDACTED]: persist the typed [RedactedReceiptItem]
 *    JSON projection and drop the parsed merchant;
 *  - [RawStorageMode.STORE_METADATA_ONLY] / [RawStorageMode.DO_NOT_STORE]:
 *    persist no item JSON and no parsed merchant;
 *  - any internal serialization failure yields null — the MOST RESTRICTIVE
 *    representation (callers already pass their fail-closed resolved mode).
 *
 * Fingerprints and processing status are derived from the ephemeral values
 * BEFORE this transformer runs — they are hashes/flags, not stored payloads.
 */
object ReceiptStructuredDataPolicy {

    /** Marker inside the persisted JSON so consumers can detect the schema. */
    private const val SCHEMA_KEY = "schema"
    private const val SCHEMA_VALUE = "REDACTED_V1"
    private const val ITEMS_KEY = "items"

    /**
     * @param fullItemsJson the STORE_RAW serialization produced upstream
     *        (parser's `lineItemsToJson`) — passed through untouched for
     *        STORE_RAW so the persisted format never drifts.
     * @param items the ephemeral parsed line items, used to build the
     *        redacted projection when the mode requires it.
     * @param currency the receipt's resolved currency, stamped on every
     *        redacted item (LineItem itself carries no currency).
     */
    fun persistedItems(
        fullItemsJson: String?,
        items: List<ReceiptParser.LineItem>?,
        currency: String,
        mode: RawStorageMode
    ): String? = try {
        when (mode) {
            RawStorageMode.STORE_RAW -> fullItemsJson
            RawStorageMode.STORE_REDACTED -> items
                ?.takeIf { it.isNotEmpty() }
                ?.let { list ->
                    redactedItemsJson(
                        list.map { RedactedSourceItem(it.quantity, it.unitPrice, it.totalPrice) },
                        currency
                    )
                }
            RawStorageMode.STORE_METADATA_ONLY, RawStorageMode.DO_NOT_STORE -> null
        }
    } catch (_: Exception) {
        null // fail closed: most restrictive representation
    }

    /**
     * Email-path variant: the ephemeral items arrive as the parser-format JSON
     * string (`EmailReceiptData.items`), so this overload parses the known
     * parser keys (description/totalPrice/quantity/unitPrice) and redacts.
     * Malformed or missing fields degrade to the most restrictive output.
     */
    fun persistedItemsFromParserJson(
        fullItemsJson: String?,
        currency: String,
        mode: RawStorageMode
    ): String? = try {
        when (mode) {
            RawStorageMode.STORE_RAW -> fullItemsJson
            RawStorageMode.STORE_REDACTED -> fullItemsJson
                ?.takeIf { it.isNotBlank() }
                ?.let { json -> parseParserJson(json).takeIf { it.isNotEmpty() } }
                ?.let { redactedItemsJson(it, currency) }
            RawStorageMode.STORE_METADATA_ONLY, RawStorageMode.DO_NOT_STORE -> null
        }
    } catch (_: Exception) {
        null // fail closed: most restrictive representation
    }

    fun persistedMerchant(merchant: String?, mode: RawStorageMode): String? = when (mode) {
        RawStorageMode.STORE_RAW -> merchant
        RawStorageMode.STORE_REDACTED,
        RawStorageMode.STORE_METADATA_ONLY,
        RawStorageMode.DO_NOT_STORE -> null // merchant omitted outside STORE_RAW
    }

    /** True when [json] is the redacted schema (no consumer may treat it as full items). */
    fun isRedactedItemsJson(json: String?): Boolean = try {
        if (json.isNullOrBlank()) {
            false
        } else {
            JSONObject(json).optString(SCHEMA_KEY, "") == SCHEMA_VALUE
        }
    } catch (_: Exception) {
        false // not our wrapper — treat as unknown, never as redacted
    }

    private fun redactedItemsJson(items: List<RedactedSourceItem>, currency: String): String {
        val array = JSONArray()
        items.forEach { item ->
            // Schema contract: quantity, unitPrice, totalPrice, currency only.
            // No description/name/brand/SKU/notes; no category at insert time.
            array.put(JSONObject().apply {
                putOpt("quantity", item.quantity)
                putOpt("unitPrice", item.unitPrice)
                put("totalPrice", item.totalPrice)
                put("currency", currency)
            })
        }
        return JSONObject().apply {
            put(SCHEMA_KEY, SCHEMA_VALUE)
            put(ITEMS_KEY, array)
        }.toString()
    }

    /** Minimal numeric projection used while redacting. */
    private data class RedactedSourceItem(
        val quantity: Double?,
        val unitPrice: Double?,
        val totalPrice: Double
    )

    private fun parseParserJson(json: String): List<RedactedSourceItem> {
        val array = JSONArray(json)
        val items = mutableListOf<RedactedSourceItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val total = if (obj.has("totalPrice")) obj.optDouble("totalPrice") else Double.NaN
            if (total.isNaN()) continue // no trustworthy price — nothing may be persisted
            items.add(
                RedactedSourceItem(
                    quantity = if (obj.has("quantity")) obj.optDouble("quantity") else null,
                    unitPrice = if (obj.has("unitPrice")) obj.optDouble("unitPrice") else null,
                    totalPrice = total
                )
            )
        }
        return items
    }
}
