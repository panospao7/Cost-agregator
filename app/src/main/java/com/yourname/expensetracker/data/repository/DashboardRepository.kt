package com.yourname.expensetracker.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.yourname.expensetracker.data.database.model.DashboardWidgetConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class DashboardRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    private val _configFlow = MutableStateFlow(getDashboardConfig())
    val configFlow: StateFlow<List<DashboardWidgetConfig>> = _configFlow.asStateFlow()

    fun getDashboardConfig(): List<DashboardWidgetConfig> {
        val json = prefs.getString("layout_config", null) ?: return getDefaultConfig()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<DashboardWidgetConfig>()
            val savedIds = mutableSetOf<String>()
            
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                savedIds.add(id)
                list.add(
                    DashboardWidgetConfig(
                        id = id,
                        order = obj.getInt("order"),
                        isVisible = obj.optBoolean("isVisible", true)
                    )
                )
            }
            
            // Merge new defaults that aren't in saved config
            val defaults = getDefaultConfig()
            var nextOrder = (list.maxOfOrNull { it.order } ?: 0) + 1
            
            defaults.forEach { def ->
                if (def.id !in savedIds) {
                    list.add(def.copy(order = nextOrder++))
                }
            }
            
            list.sortedBy { it.order }
        } catch (e: Exception) {
            getDefaultConfig()
        }
    }

    fun saveDashboardConfig(config: List<DashboardWidgetConfig>) {
        val array = JSONArray()
        config.forEach {
            val obj = JSONObject()
            obj.put("id", it.id)
            obj.put("order", it.order)
            obj.put("isVisible", it.isVisible)
            array.put(obj)
        }
        prefs.edit().putString("layout_config", array.toString()).apply()
        _configFlow.value = config
    }

    private fun getDefaultConfig(): List<DashboardWidgetConfig> {
        return listOf(
            DashboardWidgetConfig("financial_weather", 0),
            DashboardWidgetConfig("safe_to_spend", 1),
            DashboardWidgetConfig("financial_runway", 2),
            DashboardWidgetConfig("spending_pace", 3),
            DashboardWidgetConfig("review_alert", 4),
            DashboardWidgetConfig("spending_trend", 5),
            DashboardWidgetConfig("insight", 6),
            DashboardWidgetConfig("period_summary", 7),
            DashboardWidgetConfig("budget_health", 8),
            DashboardWidgetConfig("top_categories", 9),
            DashboardWidgetConfig("recent_transactions", 10),
            DashboardWidgetConfig("budget_block_party", 11)
        )
    }
}
