package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RP-12 12b (P3-007): the persisted structured-data contract per RawStorageMode.
 *
 *  - STORE_RAW: parser JSON + merchant pass through unchanged;
 *  - STORE_REDACTED: ONLY the typed RedactedReceiptItem projection is persisted
 *    (quantity, unitPrice, totalPrice, currency — never name/description/brand/
 *    SKU/notes/merchant), marked with the schema key;
 *  - STORE_METADATA_ONLY / DO_NOT_STORE: nothing persisted;
 *  - failures degrade to the most restrictive representation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ReceiptStructuredDataPolicyTest {

    private val fullJson =
        """[{"description":"Milk","totalPrice":1.5,"itemIndex":0,"quantity":2.0,"unitPrice":0.75}]"""

    private val items = listOf(
        ReceiptParser.LineItem(
            description = "Milk",
            quantity = 2.0,
            unitPrice = 0.75,
            totalPrice = 1.5,
            itemIndex = 0
        )
    )

    @Test
    fun `STORE_RAW passes the parser JSON and merchant through unchanged`() {
        assertEquals(fullJson, ReceiptStructuredDataPolicy.persistedItems(fullJson, items, "EUR", RawStorageMode.STORE_RAW))
        assertEquals("Merchant", ReceiptStructuredDataPolicy.persistedMerchant("Merchant", RawStorageMode.STORE_RAW))
    }

    @Test
    fun `STORE_REDACTED persists only the typed projection fields`() {
        val persisted = ReceiptStructuredDataPolicy.persistedItems(fullJson, items, "EUR", RawStorageMode.STORE_REDACTED)
        assertTrue(persisted != null)
        assertTrue("schema marker expected", ReceiptStructuredDataPolicy.isRedactedItemsJson(persisted))

        val arr = JSONObject(persisted!!).getJSONArray("items")
        assertEquals(1, arr.length())
        val entry = arr.getJSONObject(0)
        // Exact allowed key set — no description/name/brand/SKU/notes/category.
        assertEquals(
            setOf("quantity", "unitPrice", "totalPrice", "currency"),
            entry.keys().asSequence().toSet()
        )
        assertEquals(2.0, entry.getDouble("quantity"), 0.0)
        assertEquals(0.75, entry.getDouble("unitPrice"), 0.0)
        assertEquals(1.5, entry.getDouble("totalPrice"), 0.0)
        assertEquals("EUR", entry.getString("currency"))
        // Merchant is omitted entirely outside STORE_RAW.
        assertNull(ReceiptStructuredDataPolicy.persistedMerchant("Merchant", RawStorageMode.STORE_REDACTED))
    }

    @Test
    fun `STORE_REDACTED via parser JSON drops the description key`() {
        val persisted = ReceiptStructuredDataPolicy.persistedItemsFromParserJson(
            fullJson, "EUR", RawStorageMode.STORE_REDACTED
        )
        assertTrue(persisted != null)
        val arr = JSONObject(persisted!!).getJSONArray("items")
        assertEquals(1, arr.length())
        val entry = arr.getJSONObject(0)
        assertFalse(entry.has("description"))
        assertEquals(1.5, entry.getDouble("totalPrice"), 0.0)
    }

    @Test
    fun `restricted modes persist neither items nor merchant`() {
        for (mode in listOf(RawStorageMode.STORE_METADATA_ONLY, RawStorageMode.DO_NOT_STORE)) {
            assertNull(ReceiptStructuredDataPolicy.persistedItems(fullJson, items, "EUR", mode))
            assertNull(ReceiptStructuredDataPolicy.persistedItemsFromParserJson(fullJson, "EUR", mode))
            assertNull(ReceiptStructuredDataPolicy.persistedMerchant("Merchant", mode))
        }
    }

    @Test
    fun `malformed parser JSON fails closed to the most restrictive representation`() {
        assertNull(
            ReceiptStructuredDataPolicy.persistedItemsFromParserJson(
                "{not json", "EUR", RawStorageMode.STORE_REDACTED
            )
        )
        assertNull(
            ReceiptStructuredDataPolicy.persistedItemsFromParserJson(
                """[{"description":"no price"}]""", "EUR", RawStorageMode.STORE_REDACTED
            )
        )
    }

    @Test
    fun `empty and null inputs persist as null in every restricted mode`() {
        assertNull(ReceiptStructuredDataPolicy.persistedItems(null, null, "EUR", RawStorageMode.STORE_REDACTED))
        assertNull(ReceiptStructuredDataPolicy.persistedItems(fullJson, emptyList(), "EUR", RawStorageMode.STORE_REDACTED))
    }

    @Test
    fun `isRedactedItemsJson is false for parser arrays null and garbage`() {
        assertFalse(ReceiptStructuredDataPolicy.isRedactedItemsJson(fullJson))
        assertFalse(ReceiptStructuredDataPolicy.isRedactedItemsJson(null))
        assertFalse(ReceiptStructuredDataPolicy.isRedactedItemsJson("{oops"))
        assertTrue(ReceiptStructuredDataPolicy.isRedactedItemsJson("""{"schema":"REDACTED_V1","items":[]}"""))
    }
}
