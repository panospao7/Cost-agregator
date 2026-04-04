package com.yourname.expensetracker.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.yourname.expensetracker.data.database.model.DashboardWidgetConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashboardRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    private val _configFlow = MutableStateFlow(getDashboardConfig())
    val configFlow: StateFlow<List<DashboardWidgetConfig>> = _configFlow.asStateFlow()

    suspend fun loadDashboardConfig() {
        withContext(Dispatchers.IO) {
            val config = getDashboardConfigInternal()
            _configFlow.value = config
        }
    }

    fun getDashboardConfig(): List<DashboardWidgetConfig> {
        return getDashboardConfigInternal()
    }

    fun getDashboardConfigSync(): List<DashboardWidgetConfig> {
        return getDashboardConfigInternal()
    }

    private fun getDashboardConfigInternal(): List<DashboardWidgetConfig> {
        val json = prefs.getString("layout_config", null) ?: return getDefaultConfig()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<DashboardWidgetConfig>()
            val savedIds = mutableSetOf<String>()
            
            synchronized(this) {
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
            }
            
            val defaults = getDefaultConfig()
            var nextOrder = (list.maxOfOrNull { it.order } ?: 0) + 1
            
            synchronized(this) {
                defaults.forEach { def ->
                    if (def.id !in savedIds) {
                        list.add(def.copy(order = nextOrder++))
                    }
                }
            }
            
            list.sortedBy { it.order }
        } catch (e: Exception) {
            getDefaultConfig()
        }
    }

    suspend fun saveDashboardConfig(config: List<DashboardWidgetConfig>) {
        withContext(Dispatchers.IO) {
            saveDashboardConfigInternal(config)
        }
    }

    fun saveDashboardConfigSync(config: List<DashboardWidgetConfig>) {
        saveDashboardConfigInternal(config)
    }

    private fun saveDashboardConfigInternal(config: List<DashboardWidgetConfig>) {
        val array = JSONArray()
        synchronized(this) {
            config.forEach {
                val obj = JSONObject()
                obj.put("id", it.id)
                obj.put("order", it.order)
                obj.put("isVisible", it.isVisible)
                array.put(obj)
            }
        }
        prefs.edit().putString("layout_config", array.toString()).apply()
        _configFlow.value = config
    }

    private fun getDefaultConfig(): List<DashboardWidgetConfig> {
        return listOf(
            DashboardWidgetConfig("financial_weather", 0),
            DashboardWidgetConfig("money_radar", 1),  // NEW: Today's unified alerts (F4)
            DashboardWidgetConfig("financial_stress_forecast", 2),
            DashboardWidgetConfig("lifestyle_savings_prompt", 3),
            DashboardWidgetConfig("savings_sweep_prompt", 4),
            DashboardWidgetConfig("financial_health_score_v2", 5),
            DashboardWidgetConfig("financial_health_score", 6),  // NEW: Game-like health score
            DashboardWidgetConfig("totals_dashboard", 7),
            DashboardWidgetConfig("no_spend_streak", 8),  // NEW: Gamification widget
            DashboardWidgetConfig("safe_to_spend", 9),
            DashboardWidgetConfig("financial_runway", 10),
            DashboardWidgetConfig("monte_carlo_forecast", 11),
            DashboardWidgetConfig("spending_pace", 12),
            DashboardWidgetConfig("review_alert", 13),
            DashboardWidgetConfig("spending_trend", 14),
            DashboardWidgetConfig("insight", 15),
            DashboardWidgetConfig("period_summary", 16),
            DashboardWidgetConfig("budget_health", 17),
            DashboardWidgetConfig("top_categories", 18),
            DashboardWidgetConfig("recent_transactions", 19),
            DashboardWidgetConfig("budget_block_party", 20)
        )
    }
}
