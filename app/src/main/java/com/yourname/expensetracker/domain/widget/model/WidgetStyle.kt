package com.yourname.expensetracker.domain.widget.model

/**
 * Visual style options for dashboard widgets.
 */
enum class WidgetStyle {
    MODERN,   // Clean, rounded, Material Design aesthetic
    RETRO     // 8-bit arcade, pixel borders, neon colors
}

/**
 * Per-widget style configuration.
 * Maps widget IDs to their preferred style.
 */
data class WidgetStyleConfig(
    val widgetStyles: Map<String, WidgetStyle> = emptyMap()
) {
    /**
     * Get the style for a specific widget. Defaults to MODERN if not set.
     */
    fun getStyle(widgetId: String): WidgetStyle {
        return widgetStyles[widgetId] ?: WidgetStyle.MODERN
    }
    
    /**
     * Set the style for a specific widget.
     */
    fun setStyle(widgetId: String, style: WidgetStyle): WidgetStyleConfig {
        return copy(widgetStyles = widgetStyles + (widgetId to style))
    }
    
    /**
     * Toggle between MODERN and RETRO for a specific widget.
     */
    fun toggleStyle(widgetId: String): WidgetStyleConfig {
        val currentStyle = getStyle(widgetId)
        val newStyle = if (currentStyle == WidgetStyle.MODERN) WidgetStyle.RETRO else WidgetStyle.MODERN
        return setStyle(widgetId, newStyle)
    }
}

/**
 * Widget IDs that support style switching.
 */
object StyledWidgets {
    const val BUDGET_BLOCK_PARTY = "budget_block_party"
    const val TOP_CATEGORIES = "top_categories"
    const val TOTALS_DASHBOARD = "totals_dashboard"
    
    val all = listOf(BUDGET_BLOCK_PARTY, TOP_CATEGORIES, TOTALS_DASHBOARD)
}
