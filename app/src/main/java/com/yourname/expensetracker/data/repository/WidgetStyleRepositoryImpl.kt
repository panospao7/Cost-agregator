package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yourname.expensetracker.domain.widget.model.StyledWidgets
import com.yourname.expensetracker.domain.widget.model.WidgetStyle
import com.yourname.expensetracker.domain.widget.model.WidgetStyleConfig
import com.yourname.expensetracker.domain.widget.service.WidgetStyleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetStyleDataStore: DataStore<Preferences> by preferencesDataStore(name = "widget_styles")

@Singleton
class WidgetStyleRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : WidgetStyleRepository {

    companion object {
        private val STYLES_KEY = stringPreferencesKey("widget_styles_json")
    }

    override fun config(): Flow<WidgetStyleConfig> =
        context.widgetStyleDataStore.data.map { prefs ->
            val jsonStr = prefs[STYLES_KEY] ?: "{}"
            parseConfig(jsonStr)
        }

    override suspend fun update(transform: (WidgetStyleConfig) -> WidgetStyleConfig) {
        context.widgetStyleDataStore.edit { prefs ->
            val current = parseConfig(prefs[STYLES_KEY] ?: "{}")
            val updated = transform(current)
            prefs[STYLES_KEY] = serializeConfig(updated)
        }
    }

    private fun parseConfig(jsonStr: String): WidgetStyleConfig {
        return try {
            val json = JSONObject(jsonStr)
            val styles = mutableMapOf<String, WidgetStyle>()
            
            StyledWidgets.all.forEach { widgetId ->
                if (json.has(widgetId)) {
                    val styleStr = json.getString(widgetId)
                    styles[widgetId] = WidgetStyle.valueOf(styleStr)
                }
            }
            
            WidgetStyleConfig(styles)
        } catch (e: Exception) {
            WidgetStyleConfig() // Return default on parse error
        }
    }

    private fun serializeConfig(config: WidgetStyleConfig): String {
        val json = JSONObject()
        config.widgetStyles.forEach { (widgetId, style) ->
            json.put(widgetId, style.name)
        }
        return json.toString()
    }
}
