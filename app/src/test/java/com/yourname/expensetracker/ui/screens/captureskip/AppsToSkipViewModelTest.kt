package com.yourname.expensetracker.ui.screens.captureskip

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.BlockedPackage
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.ui.util.blockedWriteException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P-01-3: [AppsToSkipViewModel] maps the blocked list and the seen packages to screen state, routes
 * block/unblock through the repository only, reports maintenance-blocked writes with the shared
 * message, rethrows cancellation and ignores a second tap on a row whose write is still running.
 * Synthetic package names only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppsToSkipViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val blockedFlow = MutableStateFlow<List<BlockedPackage>>(emptyList())
    private val seenFlow = MutableStateFlow<List<String>>(emptyList())
    private val repository = mockk<NotificationRepository>(relaxed = true)
    private val labelResolver = mockk<AppLabelResolver>()
    private val parserRegistry = mockk<AppParserRegistry>()
    private val labels = mutableMapOf(
        "com.synthetic.zeta" to "Zeta Pay",
        "com.synthetic.alpha" to "alpha bank",
        "com.synthetic.beta" to "Beta Wallet"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.getBlockedPackages() } returns blockedFlow
        every { repository.getAllPackages() } returns seenFlow
        every { parserRegistry.hasParserFor(any()) } returns false
        every { labelResolver.label(any()) } answers { labels[firstArg<String>()] ?: firstArg<String>() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AppsToSkipViewModel(repository, labelResolver, parserRegistry, dispatcher)

    private fun blocked(vararg names: String) = names.map { BlockedPackage(packageName = it, blockedAt = 1L) }

    private val blockedMessage = UiText.StringResource(R.string.ui_write_blocked_maintenance)
    private val genericMessage = UiText.StringResource(R.string.capture_skip_error_generic)

    // ── State mapping ─────────────────────────────────────────────────────────────

    @Test
    fun `state starts loading before any subscriber and is empty and loaded with empty sources`() = runTest(dispatcher) {
        val vm = viewModel()
        assertTrue(vm.uiState.value.isLoading)

        backgroundScope.launch { vm.uiState.collect { } }

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.loadFailed)
        assertTrue(state.blocked.isEmpty())
        assertTrue(state.candidates.isEmpty())
        assertTrue(state.inFlight.isEmpty())
    }

    @Test
    fun `candidates are the seen packages minus the blocked ones and both lists are label sorted`() = runTest(dispatcher) {
        blockedFlow.value = blocked("com.synthetic.beta")
        seenFlow.value = listOf("com.synthetic.zeta", "com.synthetic.beta", "com.synthetic.alpha")
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        val state = vm.uiState.value

        assertEquals(listOf(SkipAppItem("com.synthetic.beta", "Beta Wallet")), state.blocked)
        // Case-insensitive label order: "alpha bank" before "Zeta Pay".
        assertEquals(
            listOf(
                SkipAppItem("com.synthetic.alpha", "alpha bank"),
                SkipAppItem("com.synthetic.zeta", "Zeta Pay")
            ),
            state.candidates
        )
    }

    @Test
    fun `a blocked package that was never seen still appears in blocked and never in candidates`() = runTest(dispatcher) {
        blockedFlow.value = blocked("com.synthetic.ghost")
        seenFlow.value = listOf("com.synthetic.alpha")
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        val state = vm.uiState.value

        assertEquals(listOf("com.synthetic.ghost"), state.blocked.map { it.packageName })
        assertEquals(listOf("com.synthetic.alpha"), state.candidates.map { it.packageName })
    }

    @Test
    fun `duplicate package rows are shown once`() = runTest(dispatcher) {
        blockedFlow.value = blocked("com.synthetic.beta", "com.synthetic.beta")
        seenFlow.value = listOf("com.synthetic.alpha", "com.synthetic.alpha", "com.synthetic.zeta")
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        val state = vm.uiState.value

        assertEquals(1, state.blocked.size)
        assertEquals(listOf("com.synthetic.alpha", "com.synthetic.zeta"), state.candidates.map { it.packageName })
    }

    @Test
    fun `an unavailable label falls back to the package name on the row`() = runTest(dispatcher) {
        seenFlow.value = listOf("com.synthetic.unlabelled")
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        assertEquals(
            listOf(SkipAppItem("com.synthetic.unlabelled", "com.synthetic.unlabelled")),
            vm.uiState.value.candidates
        )
    }

    @Test
    fun `state follows the sources - an unblocked seen app returns to candidates`() = runTest(dispatcher) {
        blockedFlow.value = blocked("com.synthetic.alpha")
        seenFlow.value = listOf("com.synthetic.alpha")
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        assertTrue(vm.uiState.value.candidates.isEmpty())

        blockedFlow.value = emptyList()

        assertTrue(vm.uiState.value.blocked.isEmpty())
        assertEquals(listOf("com.synthetic.alpha"), vm.uiState.value.candidates.map { it.packageName })
    }

    @Test
    fun `a failing source yields loadFailed and no lists`() = runTest(dispatcher) {
        every { repository.getAllPackages() } returns flow<List<String>> { throw IllegalStateException("source down") }
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        val state = vm.uiState.value

        assertFalse(state.isLoading)
        assertTrue(state.loadFailed)
        assertTrue(state.blocked.isEmpty())
        assertTrue(state.candidates.isEmpty())
    }

    // ── Writes ────────────────────────────────────────────────────────────────────

    @Test
    fun `block calls the repository once and shows no message`() = runTest(dispatcher) {
        val vm = viewModel()
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.block("com.synthetic.alpha")

        coVerify(exactly = 1) { repository.blockPackage("com.synthetic.alpha") }
        coVerify(exactly = 0) { repository.unblockPackage(any()) }
        assertTrue(messages.isEmpty())
        assertTrue(vm.uiState.value.inFlight.isEmpty())
    }

    @Test
    fun `unblock calls the repository once and shows no message`() = runTest(dispatcher) {
        val vm = viewModel()
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.unblock("com.synthetic.alpha")

        coVerify(exactly = 1) { repository.unblockPackage("com.synthetic.alpha") }
        coVerify(exactly = 0) { repository.blockPackage(any()) }
        assertTrue(messages.isEmpty())
        assertTrue(vm.uiState.value.inFlight.isEmpty())
    }

    @Test
    fun `a write blocked by maintenance shows the ui_write_blocked_maintenance message and frees the row`() = runTest(dispatcher) {
        coEvery { repository.blockPackage(any()) } throws blockedWriteException()
        val vm = viewModel()
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.block("com.synthetic.alpha")

        assertEquals(listOf<UiText>(blockedMessage), messages)
        assertTrue(vm.uiState.value.inFlight.isEmpty())
    }

    @Test
    fun `an unblock blocked by maintenance shows the same message`() = runTest(dispatcher) {
        coEvery { repository.unblockPackage(any()) } throws blockedWriteException()
        val vm = viewModel()
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.unblock("com.synthetic.alpha")

        assertEquals(listOf<UiText>(blockedMessage), messages)
    }

    @Test
    fun `any other write failure shows the generic message and not the maintenance one`() = runTest(dispatcher) {
        coEvery { repository.blockPackage(any()) } throws IllegalStateException("db exploded com.synthetic.alpha")
        val vm = viewModel()
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.block("com.synthetic.alpha")

        assertEquals(listOf<UiText>(genericMessage), messages)
        assertTrue(vm.uiState.value.inFlight.isEmpty())
    }

    @Test
    fun `cancellation is not turned into a message and the row is freed`() = runTest(dispatcher) {
        coEvery { repository.blockPackage(any()) } throws CancellationException("cancelled")
        val vm = viewModel()
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.block("com.synthetic.alpha")

        assertTrue("cancellation must not surface as a message: $messages", messages.isEmpty())
        assertTrue(vm.uiState.value.inFlight.isEmpty())
    }

    // ── Parser-app warning ────────────────────────────────────────────────────────

    @Test
    fun `block on a parser app holds a pending confirm and does not block yet`() = runTest(dispatcher) {
        every { parserRegistry.hasParserFor("com.synthetic.alpha") } returns true
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        vm.block("com.synthetic.alpha")

        assertEquals(SkipAppItem("com.synthetic.alpha", "alpha bank"), vm.uiState.value.pendingBlockConfirm)
        coVerify(exactly = 0) { repository.blockPackage(any()) }
        assertTrue(vm.uiState.value.inFlight.isEmpty())
    }

    @Test
    fun `confirmPendingBlock blocks the pending app once and clears the pending state`() = runTest(dispatcher) {
        every { parserRegistry.hasParserFor("com.synthetic.alpha") } returns true
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        vm.block("com.synthetic.alpha")

        vm.confirmPendingBlock()

        coVerify(exactly = 1) { repository.blockPackage("com.synthetic.alpha") }
        assertNull(vm.uiState.value.pendingBlockConfirm)
    }

    @Test
    fun `dismissPendingBlock clears the pending state without blocking`() = runTest(dispatcher) {
        every { parserRegistry.hasParserFor("com.synthetic.alpha") } returns true
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        vm.block("com.synthetic.alpha")

        vm.dismissPendingBlock()

        assertNull(vm.uiState.value.pendingBlockConfirm)
        coVerify(exactly = 0) { repository.blockPackage(any()) }
    }

    @Test
    fun `confirmPendingBlock without a pending app writes nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        vm.confirmPendingBlock()

        coVerify(exactly = 0) { repository.blockPackage(any()) }
        assertNull(vm.uiState.value.pendingBlockConfirm)
    }

    @Test
    fun `confirm after dismiss does not block`() = runTest(dispatcher) {
        every { parserRegistry.hasParserFor("com.synthetic.alpha") } returns true
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        vm.block("com.synthetic.alpha")
        vm.dismissPendingBlock()

        vm.confirmPendingBlock()

        coVerify(exactly = 0) { repository.blockPackage(any()) }
    }

    @Test
    fun `block on a non parser app blocks directly with no pending confirm`() = runTest(dispatcher) {
        every { parserRegistry.hasParserFor("com.synthetic.alpha") } returns false
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        vm.block("com.synthetic.alpha")

        coVerify(exactly = 1) { repository.blockPackage("com.synthetic.alpha") }
        assertNull(vm.uiState.value.pendingBlockConfirm)
    }

    @Test
    fun `block of a parser app is ignored while its write is running`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.blockPackage("com.synthetic.alpha") } coAnswers { gate.await() }
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        vm.block("com.synthetic.alpha")
        every { parserRegistry.hasParserFor("com.synthetic.alpha") } returns true

        vm.block("com.synthetic.alpha")

        assertNull(vm.uiState.value.pendingBlockConfirm)
        coVerify(exactly = 1) { repository.blockPackage("com.synthetic.alpha") }
        gate.complete(Unit)
    }

    // ── Double tap ────────────────────────────────────────────────────────────────

    @Test
    fun `a second tap on a row whose write is running is ignored until it finishes`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.blockPackage("com.synthetic.alpha") } coAnswers { gate.await() }
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        vm.block("com.synthetic.alpha")
        vm.block("com.synthetic.alpha")
        vm.unblock("com.synthetic.alpha")

        assertEquals(setOf("com.synthetic.alpha"), vm.uiState.value.inFlight)
        coVerify(exactly = 1) { repository.blockPackage("com.synthetic.alpha") }
        coVerify(exactly = 0) { repository.unblockPackage(any()) }

        gate.complete(Unit)

        assertTrue(vm.uiState.value.inFlight.isEmpty())
        // Control: once finished, the same row can be written again.
        vm.block("com.synthetic.alpha")
        coVerify(exactly = 2) { repository.blockPackage("com.synthetic.alpha") }
    }

    @Test
    fun `a running write on one row does not block a tap on another row`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.blockPackage("com.synthetic.alpha") } coAnswers { gate.await() }
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        vm.block("com.synthetic.alpha")
        vm.block("com.synthetic.zeta")

        coVerify(exactly = 1) { repository.blockPackage("com.synthetic.alpha") }
        coVerify(exactly = 1) { repository.blockPackage("com.synthetic.zeta") }
        gate.complete(Unit)
    }
}
