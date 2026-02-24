package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ReceiptParserTest {

    private lateinit var parser: ReceiptParser
    private lateinit var merchantRules: MerchantRulesRepository

    @Before
    fun setup() {
        merchantRules = MerchantRulesRepository()
        parser = ReceiptParser(merchantRules)
    }

    @Test
    fun testExactHallucinationMap() {
        // Simulating: ZYNOAO for ΣΥΝΟΛΟ, and oIA for ΦΠΑ
        val ocr = """
            ΓΡΗΓΟΡΗΣ
            KAAAPH ABIA: 20,00
            oIA 24%: 4,80
            ZYNOAO 24,80
        """.trimIndent()
        
        val parsed = parser.parse(ocr)
        assertEquals("Exact hallucination map failed.", 24.80, parsed.total)
    }

    @Test
    fun testLatinIntrusion() {
        // Simulating the Latin S inside the Greek string: ΠΟSΟ/AMOUNT
        val ocr = """
            PORTOBELLOS
            ΠΟSΟ/AMOUNT: €80,43
        """.trimIndent()
        
        val parsed = parser.parse(ocr)
        assertEquals("Latin intrusion strip failed.", 80.43, parsed.total)
    }

    @Test
    fun testGeometricArtifacts() {
        // Simulating: Arrow points ">" between ΣΥΝΟΛΟ and the amount
        val ocr = """
            ΓΕΛΑΣΤΟ ΚΡΕΜΜΥΔΙ
            ΣΥΝΟΛΟ   >    € 6,80
            METPHTA       € 6,80
        """.trimIndent()
        
        val parsed = parser.parse(ocr)
        assertEquals("Geometric artifact strip failed.", 6.80, parsed.total)
    }

    @Test
    fun testFuzzyMatching() {
        // Simulating a dynamic hallucination: ZΥN0/\0 which is not explicitly in the map
        val ocr = """
            ΣΚΛΑΒΕΝΙΤΗΣ
            ZΥN0/\0 12,50
            METEHTA 12,50
        """.trimIndent()
        
        val parsed = parser.parse(ocr)
        assertEquals("Fuzzy matching fallback failed.", 12.50, parsed.total)
    }
}
