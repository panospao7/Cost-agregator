package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptScanViewModelStressTest : ViewModelTestUtils() {

    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var timeProvider: TimeProvider

    private lateinit var viewModel: ReceiptScanViewModel

    @Before
    override fun setup() {
        super.setup()
        receiptRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        savedStateHandle = SavedStateHandle()
        timeProvider = mockk(relaxed = true)

        every { timeProvider.now() } returns System.currentTimeMillis()
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { receiptRepository.createTempPhotoUri() } returns Uri.parse("content://test/photo.jpg")

        viewModel = ReceiptScanViewModel(
            receiptRepository,
            categoryRepository,
            savedStateHandle,
            timeProvider
        )
    }

    @Test
    fun `stress - initial step is CAPTURE`() = runTest {
        assertEquals(ScanStep.CAPTURE, viewModel.state.value.step)
    }

    @Test
    fun `stress - createTempPhotoUri returns uri and updates state`() = runTest {
        val uri = viewModel.createTempPhotoUri()
        advanceUntilIdle()
        assertNotNull(uri)
        assertEquals(uri, viewModel.state.value.tempCameraUri)
    }

    @Test
    fun `stress - processPhoto with no uri does not crash`() = runTest {
        viewModel.processPhoto()
        advanceUntilIdle()
        assertEquals(ScanStep.CAPTURE, viewModel.state.value.step)
    }

    @Test
    fun `stress - processGalleryImage with uri updates step to PROCESSING`() = runTest {
        val uri = Uri.parse("content://test/gallery.jpg")
        viewModel.processGalleryImage(uri)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.step != ScanStep.CAPTURE)
        assertEquals(uri, viewModel.state.value.imageUri)
    }
}
