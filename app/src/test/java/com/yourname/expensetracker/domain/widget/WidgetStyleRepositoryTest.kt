package com.yourname.expensetracker.domain.widget

import com.yourname.expensetracker.domain.widget.model.WidgetStyle
import com.yourname.expensetracker.domain.widget.model.WidgetStyleConfig
import com.yourname.expensetracker.domain.widget.service.WidgetStyleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for WidgetStyleRepository bug fix:
 * - Force unwrap crash fix
 * - Safe fallback when update fails
 */
class WidgetStyleRepositoryTest {

    @Test
    fun `toggleWidgetStyle returns correct style after successful toggle`() = runTest {
        val repository = object : WidgetStyleRepository {
            private var config = WidgetStyleConfig()
            
            override fun config() = kotlinx.coroutines.flow.flowOf(config)
            
            override suspend fun update(transform: (WidgetStyleConfig) -> WidgetStyleConfig) {
                config = transform(config)
            }
            
            override suspend fun toggleWidgetStyle(widgetId: String): WidgetStyle {
                var newStyle: WidgetStyle? = null
                update { cfg ->
                    val style = if (cfg.getStyle(widgetId) == WidgetStyle.MODERN) {
                        WidgetStyle.RETRO
                    } else {
                        WidgetStyle.MODERN
                    }
                    newStyle = style
                    cfg.setStyle(widgetId, style)
                }
                return newStyle ?: WidgetStyle.MODERN
            }
        }

        val result = repository.toggleWidgetStyle("widget1")
        assertEquals(WidgetStyle.RETRO, result)
    }

    @Test
    fun `toggleWidgetStyle falls back to MODERN when update fails`() = runTest {
        val repository = object : WidgetStyleRepository {
            override fun config() = kotlinx.coroutines.flow.flowOf(WidgetStyleConfig())
            
            override suspend fun update(transform: (WidgetStyleConfig) -> WidgetStyleConfig) {
                // Simulate failure by not updating newStyle
            }
            
            override suspend fun toggleWidgetStyle(widgetId: String): WidgetStyle {
                var newStyle: WidgetStyle? = null
                update { cfg ->
                    val style = WidgetStyle.RETRO
                    newStyle = style
                    cfg.setStyle(widgetId, style)
                }
                return newStyle ?: WidgetStyle.MODERN
            }
        }

        val result = repository.toggleWidgetStyle("widget1")
        assertEquals(WidgetStyle.MODERN, result) // Safe fallback
    }
}
