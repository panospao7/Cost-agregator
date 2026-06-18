package com.yourname.expensetracker.domain.widget.service

import com.yourname.expensetracker.domain.widget.model.WidgetStyleConfig
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for per-widget visual style preferences.
 *
 * Backed by DataStore (not Room) for lightweight key-value storage.
 * All writes are expressed as a transform to avoid read-modify-write races.
 */
interface WidgetStyleRepository {
    /** Live stream of the current widget style configuration. Emits immediately on first collection. */
    fun config(): Flow<WidgetStyleConfig>

    /** Apply [transform] to the current configuration and persist the result. */
    suspend fun update(transform: (WidgetStyleConfig) -> WidgetStyleConfig)
    
    /**
     * Convenience method to toggle style for a specific widget.
     * Returns the new style after toggling.
     */
    suspend fun toggleWidgetStyle(widgetId: String): com.yourname.expensetracker.domain.widget.model.WidgetStyle {
        var newStyle: com.yourname.expensetracker.domain.widget.model.WidgetStyle? = null
        update { config ->
            val style = if (config.getStyle(widgetId) == com.yourname.expensetracker.domain.widget.model.WidgetStyle.MODERN) {
                com.yourname.expensetracker.domain.widget.model.WidgetStyle.RETRO
            } else {
                com.yourname.expensetracker.domain.widget.model.WidgetStyle.MODERN
            }
            newStyle = style
            config.setStyle(widgetId, style)
        }
        return newStyle ?: com.yourname.expensetracker.domain.widget.model.WidgetStyle.MODERN // Safe fallback
    }
}
