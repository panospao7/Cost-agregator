package com.yourname.expensetracker.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Behaviour tests for SpendingHeatmapEngine: grid-based clustering,
 * log-normalised weights, positive-spend filtering, average position,
 * extreme geometry and spend conservation.
 */
class SpendingHeatmapEngineTest {

    private val heatmapEngine = SpendingHeatmapEngine()

    private class SingleCellRow(
        val label: String,
        val expenses: List<LocatedExpense>,
        val expectedSpend: Double,
        val expectedCount: Int
    )

    private class CellCountRow(
        val label: String,
        val expenses: List<LocatedExpense>,
        val expectedCells: IntRange,
        val extra: (List<HeatmapPoint>) -> Unit = {}
    )

    private class ZeroNegativeRow(
        val label: String,
        val expenses: List<LocatedExpense>,
        // null = heatmap must be empty
        val expectedSpend: Double?,
        val expectedCount: Int?,
        val expectedWeight: Float?
    )

    private class WeightRangeRow(
        val label: String,
        val expenses: List<LocatedExpense>,
        val expectedCells: Int?,
        val extra: (List<HeatmapPoint>) -> Unit = {}
    )

    // ============================================================================
    // CLUSTERING
    // ============================================================================

    @Test
    fun `co-located expenses collapse into one cell with summed spend and count`() {
        val random = Random(20260102)
        val timestamp = 1_700_000_000_000L
        val rows = listOf(
            SingleCellRow(
                "three expenses within ~1m",
                listOf(
                    createExpense(1, 40.712800, -74.006000, 10.0),
                    createExpense(2, 40.712805, -74.006005, 20.0),
                    createExpense(3, 40.712801, -74.006002, 15.0)
                ),
                45.0, 3
            ),
            SingleCellRow(
                "100 expenses within ~10m",
                (1..100).map { i ->
                    createExpense(
                        id = i.toLong(),
                        lat = 40.7128 + (random.nextDouble() - 0.5) * 0.0001,
                        lon = -74.0060 + (random.nextDouble() - 0.5) * 0.0001,
                        amount = (i * 10).toDouble()
                    )
                },
                50500.0, 100
            ),
            SingleCellRow(
                "50 expenses at identical coordinates",
                (1..50).map { i -> createExpense(i.toLong(), 40.712800, -74.006000, 10.0) },
                500.0, 50
            ),
            SingleCellRow(
                "same timestamp",
                listOf(
                    createExpense(1, 40.7128, -74.0060, 100.0, timestamp),
                    createExpense(2, 40.7128, -74.0060, 200.0, timestamp),
                    createExpense(3, 40.7128, -74.0060, 300.0, timestamp)
                ),
                600.0, 3
            ),
            SingleCellRow(
                "duplicate expense ids are separate expenses",
                listOf(
                    createExpense(1, 40.7128, -74.0060, 100.0),
                    createExpense(1, 40.7128, -74.0060, 200.0),
                    createExpense(1, 40.7128, -74.0060, 300.0)
                ),
                600.0, 3
            ),
            SingleCellRow(
                "mixed very large and very small values",
                listOf(
                    createExpense(1, 40.7128, -74.0060, 0.001),
                    createExpense(2, 40.7128, -74.0060, 1000000.0),
                    createExpense(3, 40.7128, -74.0060, 50.0)
                ),
                1000050.001, 3
            )
        )
        rows.forEach { row ->
            val heatmap = heatmapEngine.compute(row.expenses)
            assertEquals("cell count for row: ${row.label}", 1, heatmap.size)
            assertEquals("totalSpend for row: ${row.label}", row.expectedSpend, heatmap[0].totalSpend, 0.01)
            assertEquals("count for row: ${row.label}", row.expectedCount, heatmap[0].count)
            assertTrue("weight in 0..1 for row: ${row.label}", heatmap[0].weight in 0f..1f)
        }
    }

    @Test
    fun `distinct locations stay in separate cells`() {
        val gridSize = 0.0015
        val baseLat = 40.7128 - (40.7128 % gridSize)
        val rows = listOf(
            CellCountRow(
                "~150m apart north and east",
                listOf(
                    createExpense(1, 40.712800, -74.006000, 10.0),
                    createExpense(2, 40.712800 + 0.0015, -74.006000, 20.0),
                    createExpense(3, 40.712800, -74.006000 + 0.0015, 15.0)
                ),
                3..3
            ),
            CellCountRow(
                "either side of a grid boundary",
                listOf(
                    createExpense(1, baseLat + gridSize - 0.0001, -74.0060, 10.0),
                    createExpense(2, baseLat + gridSize + 0.0001, -74.0060, 20.0)
                ),
                2..2
            ),
            CellCountRow(
                "five global cities",
                listOf(
                    createExpense(1, 40.7128, -74.0060, 100.0),
                    createExpense(2, 51.5074, -0.1278, 200.0),
                    createExpense(3, -33.8688, 151.2093, 150.0),
                    createExpense(4, 35.6762, 139.6503, 180.0),
                    createExpense(5, -23.5505, -46.6333, 120.0)
                ),
                5..5
            ) { heatmap -> assertTrue("all weights valid (global)", heatmap.all { it.weight in 0f..1f }) },
            CellCountRow(
                "extreme latitudes near both poles",
                listOf(
                    createExpense(1, 89.9, 0.0, 100.0),
                    createExpense(2, -89.9, 0.0, 100.0)
                ),
                2..2
            ) { heatmap ->
                assertTrue("a point near north pole", heatmap.any { it.latitude > 89.0 })
                assertTrue("a point near south pole", heatmap.any { it.latitude < -89.0 })
            },
            CellCountRow(
                "either side of the antimeridian",
                listOf(
                    createExpense(1, 0.0, 179.9, 100.0),
                    createExpense(2, 0.0, -179.9, 100.0)
                ),
                2..2
            ) { heatmap ->
                assertTrue("a point east of 179", heatmap.any { it.longitude > 179.0 })
                assertTrue("a point west of -179", heatmap.any { it.longitude < -179.0 })
            }
        )
        assertCellCounts(rows)
    }

    @Test
    fun `cell count bounds hold at scale and for unusual geometry`() {
        val random = Random(20260101)
        val rows = listOf(
            CellCountRow(
                "1000 expenses across a diagonal of cells",
                (1..1000).map { i ->
                    createExpense(
                        id = i.toLong(),
                        lat = 40.7128 + (i % 20) * 0.0015,
                        lon = -74.0060 + (i % 20) * 0.0015,
                        amount = (i % 100).toDouble()
                    )
                },
                10..Int.MAX_VALUE
            ),
            CellCountRow(
                "10000 random expenses produce a heatmap",
                (1..10000).map { i ->
                    createExpense(
                        id = i.toLong(),
                        lat = 40.7128 + (random.nextDouble() - 0.5) * 0.1,
                        lon = -74.0060 + (random.nextDouble() - 0.5) * 0.1,
                        amount = random.nextDouble() * 1000
                    )
                },
                1..Int.MAX_VALUE
            ),
            CellCountRow(
                "five locations with 100 expenses each collapse to five cells",
                (0..4).flatMap { location ->
                    (1..100).map { expense ->
                        createExpense(
                            id = (location * 100 + expense).toLong(),
                            lat = 40.7128 + location * 0.01,
                            lon = -74.0060 + location * 0.01,
                            amount = (expense * 10).toDouble()
                        )
                    }
                },
                5..5
            ),
            CellCountRow(
                "high latitude close longitudes",
                listOf(
                    createExpense(1, 89.0, 0.0, 100.0),
                    createExpense(2, 89.0, 0.002, 100.0)
                ),
                1..2
            ),
            CellCountRow(
                "southern hemisphere nearby points",
                listOf(
                    createExpense(1, -33.8688, 151.2093, 100.0),
                    createExpense(2, -33.8700, 151.2100, 50.0)
                ),
                1..2
            ) { heatmap -> assertTrue("negative latitude retained", heatmap[0].latitude < 0) }
        )
        assertCellCounts(rows)
    }

    // ============================================================================
    // SPEND FILTERING
    // ============================================================================

    @Test
    fun `non-positive spend is excluded and positive spend is kept`() {
        val rows = listOf(
            ZeroNegativeRow("empty list", emptyList(), null, null, null),
            ZeroNegativeRow(
                "negative only",
                listOf(
                    createExpense(1, 40.7128, -74.0060, -10.0),
                    createExpense(2, 40.7200, -74.0100, -25.0)
                ),
                null, null, null
            ),
            ZeroNegativeRow(
                "zero spend in its own cell is dropped",
                listOf(
                    createExpense(1, 40.7128, -74.0060, 0.0),
                    createExpense(2, 40.7128 + 0.0015, -74.0060, 100.0)
                ),
                100.0, 1, null
            ),
            ZeroNegativeRow(
                "negative in a positive cluster is not accumulated",
                listOf(
                    createExpense(1, 40.712800, -74.006000, 50.0),
                    createExpense(2, 40.712805, -74.006005, -20.0),
                    createExpense(3, 40.712810, -74.006010, 25.0)
                ),
                75.0, 2, 1.0f
            ),
            ZeroNegativeRow(
                "single expense",
                listOf(createExpense(1, 40.7128, -74.0060, 50.0)),
                50.0, 1, 1.0f
            )
        )
        rows.forEach { row ->
            val heatmap = heatmapEngine.compute(row.expenses)
            if (row.expectedSpend == null) {
                assertTrue("empty heatmap for row: ${row.label}", heatmap.isEmpty())
            } else {
                assertEquals("cell count for row: ${row.label}", 1, heatmap.size)
                assertEquals("totalSpend for row: ${row.label}", row.expectedSpend, heatmap[0].totalSpend, 0.01)
                assertEquals("count for row: ${row.label}", row.expectedCount, heatmap[0].count)
                if (row.expectedWeight != null) {
                    assertEquals("weight for row: ${row.label}", row.expectedWeight, heatmap[0].weight, 0.01f)
                }
            }
        }
    }

    // ============================================================================
    // WEIGHTS
    // ============================================================================

    @Test
    fun `weights stay within 0 to 1 for extreme and ordinary values`() {
        val rows = listOf(
            WeightRangeRow(
                "very small values",
                listOf(
                    createExpense(1, 40.7128, -74.0060, 0.01),
                    createExpense(2, 40.7128 + 0.0015, -74.0060, 0.05)
                ),
                2
            ),
            WeightRangeRow(
                "very large next to small",
                listOf(
                    createExpense(1, 40.7128, -74.0060, 1000000.0),
                    createExpense(2, 40.7200, -74.0100, 10.0)
                ),
                2
            ),
            WeightRangeRow(
                "20 cells with increasing spend",
                (1..20).map { i ->
                    createExpense(
                        id = i.toLong(),
                        lat = 40.7128 + i * 0.0015,
                        lon = -74.0060,
                        amount = (i * 100).toDouble()
                    )
                },
                null
            ),
            WeightRangeRow(
                "two Double.MAX_VALUE/2 amounts in one cell",
                listOf(
                    createExpense(1, 40.7128, -74.0060, Double.MAX_VALUE / 2),
                    createExpense(2, 40.7128, -74.0060, Double.MAX_VALUE / 2)
                ),
                1
            ) { heatmap -> assertTrue("max-double spend stays positive", heatmap[0].totalSpend > 0) }
        )
        rows.forEach { row ->
            val heatmap = heatmapEngine.compute(row.expenses)
            if (row.expectedCells != null) {
                assertEquals("cell count for row: ${row.label}", row.expectedCells, heatmap.size)
            }
            assertTrue("all weights in 0..1 for row: ${row.label}", heatmap.all { it.weight in 0f..1f })
            row.extra(heatmap)
        }
    }

    @Test
    fun `weights are log compressed, peak at 1 and tie for equal spend`() {
        val compressed = heatmapEngine.compute(
            listOf(
                createExpense(1, 40.7128, -74.0060, 10.0),
                createExpense(2, 40.7200, -74.0100, 1000.0)
            )
        )
        assertEquals("log-compress row: cells", 2, compressed.size)
        val weight1 = compressed.find { it.totalSpend == 10.0 }?.weight ?: 0f
        val weight2 = compressed.find { it.totalSpend == 1000.0 }?.weight ?: 0f
        assertTrue("log-compress row: 100x spend must weigh < 10x", weight2 / weight1 < 10f)

        val peak = heatmapEngine.compute(
            listOf(
                createExpense(1, 40.7128, -74.0060, 100.0),
                createExpense(2, 40.7200, -74.0100, 500.0),
                createExpense(3, 40.7300, -74.0200, 1000.0)
            )
        )
        assertEquals("peak row: max weight must be 1.0", 1.0f, peak.maxOf { it.weight }, 0.01f)

        val ties = heatmapEngine.compute(
            listOf(
                createExpense(1, 40.7128, -74.0060, 100.0),
                createExpense(2, 40.7200, -74.0100, 100.0),
                createExpense(3, 40.7300, -74.0200, 100.0)
            )
        )
        assertEquals("ties row: equal spend gives equal weights", 1, ties.map { it.weight }.distinct().size)
    }

    // ============================================================================
    // POSITION
    // ============================================================================

    @Test
    fun `cell position is the cluster average and exact for a single expense`() {
        val cluster = heatmapEngine.compute(
            listOf(
                createExpense(1, 40.712800, -74.006000, 10.0),
                createExpense(2, 40.712805, -74.006005, 10.0),
                createExpense(3, 40.712810, -74.006010, 10.0)
            )
        )
        assertEquals("cluster row: cell count", 1, cluster.size)
        assertEquals(
            "cluster row: latitude",
            (40.712800 + 40.712805 + 40.712810) / 3, cluster[0].latitude, 0.0001
        )
        assertEquals(
            "cluster row: longitude",
            (-74.006000 + -74.006005 + -74.006010) / 3, cluster[0].longitude, 0.0001
        )

        val single = heatmapEngine.compute(listOf(createExpense(1, 40.712800, -74.006000, 100.0)))
        assertEquals("single row: latitude", 40.712800, single[0].latitude, 0.000001)
        assertEquals("single row: longitude", -74.006000, single[0].longitude, 0.000001)
    }

    // ============================================================================
    // CONSERVATION AND DETERMINISM
    // ============================================================================

    @Test
    fun `total spend is preserved across clusters and results are deterministic`() {
        val spread = listOf(
            createExpense(1, 40.7128, -74.0060, 100.0),
            createExpense(2, 40.7200, -74.0100, 200.0),
            createExpense(3, 40.7300, -74.0200, 300.0)
        )
        assertEquals(
            "conservation row: total spend",
            600.0,
            heatmapEngine.compute(spread).sumOf { it.totalSpend },
            0.01
        )

        val expenses = (1..100).map { i ->
            createExpense(
                id = i.toLong(),
                lat = 40.7128 + (i % 10) * 0.0015,
                lon = -74.0060 + (i % 10) * 0.0015,
                amount = (i * 10).toDouble()
            )
        }
        val heatmap1 = heatmapEngine.compute(expenses)
        val heatmap2 = heatmapEngine.compute(expenses)
        val heatmap3 = heatmapEngine.compute(expenses)

        assertEquals("determinism row: run 1 vs 2 cell count", heatmap1.size, heatmap2.size)
        assertEquals("determinism row: run 2 vs 3 cell count", heatmap2.size, heatmap3.size)
        assertEquals(
            "determinism row: total spend",
            heatmap1.sumOf { it.totalSpend }, heatmap2.sumOf { it.totalSpend }, 0.01
        )
        assertTrue("determinism row: heatmap is not empty", heatmap1.isNotEmpty())
        assertEquals("determinism row: run 1 vs 2 full cells", heatmap1, heatmap2)
        assertEquals("determinism row: run 2 vs 3 full cells", heatmap2, heatmap3)
    }

    private fun assertCellCounts(rows: List<CellCountRow>) {
        rows.forEach { row ->
            val heatmap = heatmapEngine.compute(row.expenses)
            assertTrue(
                "cell count ${heatmap.size} must be in ${row.expectedCells} for row: ${row.label}",
                heatmap.size in row.expectedCells
            )
            row.extra(heatmap)
        }
    }

    private fun createExpense(
        id: Long,
        lat: Double,
        lon: Double,
        amount: Double,
        date: Long = System.currentTimeMillis()
    ): LocatedExpense {
        return LocatedExpense(
            expenseId = id,
            latitude = lat,
            longitude = lon,
            amount = amount,
            merchant = "Test Merchant",
            date = date,
            locationSource = "test",
            placeId = null
        )
    }
}
