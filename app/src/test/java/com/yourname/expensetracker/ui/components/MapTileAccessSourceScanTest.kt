package com.yourname.expensetracker.ui.components

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S19-2 / D7 source pins (comments and string bodies blanked first):
 *  - every production file that constructs an osmdroid `MapView(` disables the data connection in the factory
 *    before `onResume()` starts the tile threads, and applies the gate result in `update` before any early return;
 *  - `SpendingMapScreenContent` defaults to the pending (tiles off) state, so a caller passing nothing gets no tiles.
 * The scan asserts it found the known map files, and a planted-violation self-check proves each rule can fail.
 */
class MapTileAccessSourceScanTest {

    private val sourceRoot: File by lazy {
        listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        ).firstOrNull { it.isDirectory } ?: error("Could not locate production source root")
    }

    private val base = "com/yourname/expensetracker/ui"
    private val spendingMap = "$base/screens/map/SpendingMapScreen.kt"
    private val locationPicker = "$base/components/LocationSearchPicker.kt"

    private val mapViewConstruction = Regex("""(?<![A-Za-z0-9_])MapView\s*\(""")
    private val updateBlock = Regex("""\bupdate\s*=\s*\{""")
    private val applyAllowed = Regex("""setUseDataConnection\(\s*tilesAllowed\s*\)""")

    private fun sanitized(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("missing production file $relative", file.isFile)
        return SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    private fun mapViewFiles(): Map<String, String> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath to SourceTextSanitizer.stripCommentsAndStringBodies(it.readText()) }
            .filter { (_, text) -> mapViewConstruction.containsMatchIn(text) }
            .toMap()

    private fun violations(label: String, text: String): List<String> {
        val out = mutableListOf<String>()
        val starts = mapViewConstruction.findAll(text).map { it.range.first }.toList()
        if (starts.isEmpty()) return listOf("$label: no MapView( construction found")
        starts.forEachIndexed { i, start ->
            val end = starts.getOrNull(i + 1) ?: text.length
            val factory = text.substring(start, end)
            val dc = factory.indexOf("setUseDataConnection(false)")
            val resume = factory.indexOf("onResume()")
            if (dc < 0) out += "$label: factory has no setUseDataConnection(false)"
            else if (resume in 0 until dc) out += "$label: setUseDataConnection(false) comes after onResume()"
            if (factory.contains("setUseDataConnection(true)")) out += "$label: factory enables the data connection"
        }
        val tail = text.substring(starts.last())
        val update = updateBlock.find(tail)
        if (update == null) {
            out += "$label: no update block"
        } else {
            val segment = tail.substring(update.range.first)
            val apply = applyAllowed.find(segment)
            val earlyReturn = segment.indexOf("return@AndroidView")
            if (apply == null) out += "$label: update does not apply setUseDataConnection(tilesAllowed)"
            else if (earlyReturn in 0 until apply.range.first) out += "$label: update returns early before applying the gate"
        }
        return out
    }

    @Test
    fun `S19-2 scan is not vacuous and finds both known map files`() {
        val found = mapViewFiles().keys
        assertTrue("expected $spendingMap in $found", spendingMap in found)
        assertTrue("expected $locationPicker in $found", locationPicker in found)
    }

    @Test
    fun `S19-2 every MapView file disables the data connection before onResume and gates it in update`() {
        val files = mapViewFiles()
        assertTrue("scan found no MapView files", files.size >= 2)
        for ((relative, text) in files) {
            assertEquals(relative, emptyList<String>(), violations(relative, text))
        }
    }

    @Test
    fun `S19-2 each known file has exactly as many data connection disables as MapView constructions`() {
        for (relative in listOf(spendingMap, locationPicker)) {
            val text = sanitized(relative)
            assertEquals(
                relative,
                mapViewConstruction.findAll(text).count(),
                Regex("""setUseDataConnection\(false\)""").findAll(text).count()
            )
        }
    }

    @Test
    fun `S19-2 no production file enables the data connection with a literal true`() {
        for ((relative, text) in mapViewFiles()) {
            assertTrue(relative, !text.contains("setUseDataConnection(true)"))
        }
    }

    @Test
    fun `S19-2 both screens feed the map the gate result and not a constant`() {
        val feeds = Regex("""tilesAllowed\s*=\s*tileAccess\.tilesAllowed""")
        for (relative in listOf(spendingMap, locationPicker)) {
            assertTrue("$relative must pass tileAccess.tilesAllowed", feeds.containsMatchIn(sanitized(relative)))
        }
    }

    @Test
    fun `S19-2 SpendingMapScreenContent tileAccess defaults to the pending state`() {
        val text = sanitized(spendingMap)
        val start = text.indexOf("fun SpendingMapScreenContent(")
        assertTrue("SpendingMapScreenContent not found", start >= 0)
        val close = Regex("""\r?\n\)\s*\{""").find(text, start)
        assertTrue("signature end not found", close != null)
        val signature = text.substring(start, close!!.range.first)
        assertTrue(
            "tileAccess default must be MapTileAccessUiState.Pending",
            Regex("""tileAccess\s*:\s*MapTileAccessUiState\s*=\s*MapTileAccessUiState\.Pending\b""").containsMatchIn(signature)
        )
    }

    @Test
    fun `S19-2 self-check planted violations are caught and a compliant factory with a comment mention passes`() {
        val good = """
            fun Map() {
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).also { mv ->
                            mv.setUseDataConnection(false)
                            mv.onResume()
                        }
                    },
                    update = { mapView ->
                        mapView.setUseDataConnection(tilesAllowed)
                        if (same) return@AndroidView
                    }
                )
            }
        """.trimIndent()
        assertEquals(emptyList<String>(), violations("good", SourceTextSanitizer.stripCommentsAndStringBodies(good)))

        fun planted(text: String) = violations("p", SourceTextSanitizer.stripCommentsAndStringBodies(text))

        assertTrue(planted(good.replace("mv.setUseDataConnection(false)", "// mv.setUseDataConnection(false)")).isNotEmpty())
        val commentOnly = good.replace("mv.setUseDataConnection(false)", "// mv.setUseDataConnection(false)")
        assertTrue(commentOnly != good && planted(commentOnly).isNotEmpty())

        val removedDisable =good.replace("mv.setUseDataConnection(false)", "")
        assertTrue(planted(removedDisable).isNotEmpty())

        val disableAfterResume = removedDisable.replace("mv.onResume()", "mv.onResume()\nmv.setUseDataConnection(false)")
        assertTrue(disableAfterResume != good && planted(disableAfterResume).isNotEmpty())

        val updateWithoutApply = good.replace("mapView.setUseDataConnection(tilesAllowed)", "mapView.invalidate()")
        assertTrue(updateWithoutApply != good && planted(updateWithoutApply).isNotEmpty())

        val applyAfterReturn = good
            .replace("mapView.setUseDataConnection(tilesAllowed)", "")
            .replace("if (same) return@AndroidView", "if (same) return@AndroidView\nmapView.setUseDataConnection(tilesAllowed)")
        assertTrue(applyAfterReturn != good && planted(applyAfterReturn).isNotEmpty())

        val enabledLiteral = good.replace("mv.onResume()", "mv.onResume()\nmv.setUseDataConnection(true)")
        assertTrue(enabledLiteral != good && planted(enabledLiteral).isNotEmpty())

        assertTrue(planted("fun f() { val x = 1 }").isNotEmpty())
    }

    // ---- Review fix pins (reviewer-strict REJECT on 013b78be): the picker with tiles off ------------------------------
    //
    // With tiles off the picker must (1) not let a long press drop a pin on a blank map, (2) not show the "long press the
    // map" hint, and (3) show the blocked card as an overlay inside the fixed-height map Box, not as a sibling that grows
    // the sheet. These are text pins on the Compose source: they cannot prove that a real long press is ignored, that the
    // hint is invisible, or that the card does not move the layout. That needs a Compose UI test or a device check.

    private val longPressArgument = Regex("""\bonLongPress\s*=""")
    private val longPressGated = Regex(
        """\bonLongPress\s*=\s*if\s*\(\s*tileAccess\.tilesAllowed\s*\)\s*onMapLongPressed\s+else\s*\{\s*_\s*,\s*_\s*->\s*\}"""
    )
    private val hintReference = Regex("""R\.string\.location_map_interaction_hint\b""")
    private val tilesAllowedBlock = Regex("""\bif\s*\(\s*tileAccess\.tilesAllowed\s*\)\s*\{""")
    private val resultsMapCall = Regex("""(?<!fun )\bResultsMapView\s*\(""")
    private val boxCall = Regex("""\bBox\s*\(""")
    private val blockedCardCall = Regex("""\bPrivacyBlockedCard\s*\(""")
    private val blockedCardAligned = Regex(
        """\bPrivacyBlockedCard\s*\(\s*state\s*=\s*\w+\s*,\s*modifier\s*=\s*Modifier\s*\.\s*align\s*\("""
    )

    /** Index of the bracket that closes the one opened at [open], or -1. */
    private fun closingIndex(text: String, open: Int, openChar: Char, closeChar: Char): Int {
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == openChar) depth++
            if (text[i] == closeChar) {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
    }

    private fun pickerViolations(label: String, text: String): List<String> {
        val out = mutableListOf<String>()

        // 1. Long press: exactly one onLongPress argument, and it is the gated expression.
        val arguments = longPressArgument.findAll(text).count()
        if (arguments != 1) out += "$label: expected exactly one onLongPress argument, found $arguments"
        else if (!longPressGated.containsMatchIn(text)) out += "$label: onLongPress is not gated on tileAccess.tilesAllowed with a no-op else"

        // 2. Hint: present, and every reference sits inside an `if (tileAccess.tilesAllowed) { ... }` block.
        val gatedRanges = tilesAllowedBlock.findAll(text).map { m ->
            val open = m.range.last
            open..closingIndex(text, open, '{', '}')
        }.toList()
        val hints = hintReference.findAll(text).map { it.range.first }.toList()
        if (hints.isEmpty()) out += "$label: the map interaction hint is missing"
        for (position in hints) {
            if (gatedRanges.none { position in it }) out += "$label: the map interaction hint is not inside an if (tileAccess.tilesAllowed) block"
        }

        // 3. Blocked card: one call, inside the Box that holds the map, aligned, and that Box has a fixed height.
        val cards = blockedCardCall.findAll(text).count()
        if (cards != 1) out += "$label: expected exactly one PrivacyBlockedCard call, found $cards"
        val call = resultsMapCall.find(text)
        if (call == null) {
            out += "$label: no ResultsMapView call found"
        } else {
            val box = boxCall.findAll(text).lastOrNull { it.range.first < call.range.first }
            if (box == null) {
                out += "$label: the map is not inside a Box"
            } else {
                val headerOpen = box.range.last
                val headerClose = closingIndex(text, headerOpen, '(', ')')
                val blockOpen = if (headerClose < 0) -1 else text.indexOf('{', headerClose)
                val blockClose = if (blockOpen < 0) -1 else closingIndex(text, blockOpen, '{', '}')
                if (headerClose < 0 || blockOpen < 0 || blockClose < 0) {
                    out += "$label: the map Box could not be parsed"
                } else {
                    val header = text.substring(headerOpen, headerClose)
                    val block = text.substring(blockOpen, blockClose)
                    if (!header.contains("height(")) out += "$label: the map Box has no fixed height"
                    if (!blockedCardCall.containsMatchIn(block)) out += "$label: PrivacyBlockedCard is not inside the map Box"
                    if (!blockedCardAligned.containsMatchIn(block)) out += "$label: PrivacyBlockedCard is not aligned inside the map Box"
                }
            }
        }
        return out
    }

    @Test
    fun `picker with tiles off ignores long press hides the hint and overlays the blocked card`() {
        assertEquals(emptyList<String>(), pickerViolations(locationPicker, sanitized(locationPicker)))
    }

    @Test
    fun `picker pins self-check planted violations are caught and a compliant sample passes`() {
        val good = """
            fun Picker() {
                Box(modifier = Modifier.fillMaxWidth().height(260.dp).padding(top = 4.dp)) {
                    ResultsMapView(
                        tilesAllowed = tileAccess.tilesAllowed,
                        onLongPress = if (tileAccess.tilesAllowed) onMapLongPressed else { _, _ -> },
                        modifier = Modifier.fillMaxSize()
                    )
                    tileAccess.blocked?.let { blockedState ->
                        PrivacyBlockedCard(
                            state = blockedState,
                            modifier = Modifier.align(Alignment.Center).padding(8.dp)
                        )
                    }
                }
                if (tileAccess.tilesAllowed) {
                    Text(text = stringResource(R.string.location_map_interaction_hint))
                }
            }
        """.trimIndent()
        assertEquals(emptyList<String>(), pickerViolations("good", SourceTextSanitizer.stripCommentsAndStringBodies(good)))

        fun planted(text: String) = pickerViolations("p", SourceTextSanitizer.stripCommentsAndStringBodies(text))

        val longPressOpen = good.replace(
            "onLongPress = if (tileAccess.tilesAllowed) onMapLongPressed else { _, _ -> }",
            "onLongPress = onMapLongPressed"
        )
        assertTrue(longPressOpen != good && planted(longPressOpen).isNotEmpty())

        val longPressCommentedOnly = good.replace(
            "onLongPress = if (tileAccess.tilesAllowed) onMapLongPressed else { _, _ -> }",
            "onLongPress = onMapLongPressed // if (tileAccess.tilesAllowed) onMapLongPressed else { _, _ -> }"
        )
        assertTrue(longPressCommentedOnly != good && planted(longPressCommentedOnly).isNotEmpty())

        val hintAlways = good.replace("if (tileAccess.tilesAllowed) {", "run {")
        assertTrue(hintAlways != good && planted(hintAlways).isNotEmpty())

        val hintRemoved = good.replace("R.string.location_map_interaction_hint", "R.string.other")
        assertTrue(hintRemoved != good && planted(hintRemoved).isNotEmpty())

        val cardSibling = """
            fun Picker() {
                Box(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                    ResultsMapView(
                        onLongPress = if (tileAccess.tilesAllowed) onMapLongPressed else { _, _ -> },
                    )
                }
                tileAccess.blocked?.let { blockedState ->
                    PrivacyBlockedCard(state = blockedState, modifier = Modifier.align(Alignment.Center))
                }
                if (tileAccess.tilesAllowed) { Text(stringResource(R.string.location_map_interaction_hint)) }
            }
        """.trimIndent()
        assertTrue(planted(cardSibling).isNotEmpty())

        val noHeight = good.replace(".height(260.dp)", "")
        assertTrue(noHeight != good && planted(noHeight).isNotEmpty())

        val notAligned = good.replace("Modifier.align(Alignment.Center).padding(8.dp)", "Modifier.padding(8.dp)")
        assertTrue(notAligned != good && planted(notAligned).isNotEmpty())

        val secondCard = good.replace(
            "if (tileAccess.tilesAllowed) {",
            "PrivacyBlockedCard(state = other, modifier = Modifier.align(Alignment.Center))\n    if (tileAccess.tilesAllowed) {"
        )
        assertTrue(secondCard != good && planted(secondCard).isNotEmpty())

        assertTrue(planted("fun f() { val x = 1 }").isNotEmpty())
    }
}
