package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import kotlinx.coroutines.flow.Flow

@Dao
interface ExchangeRateDao {
    
    /**
     * Uses REPLACE intentionally: exchange rates are keyed on a unique composite
     * index (fromCurrency + toCurrency + validDate), so newer rate data for the
     * same currency pair and date should overwrite older data. No risk of
     * accidental cross-row data loss — each pair/date is independently keyed.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(rate: ExchangeRate): Long
    
    /**
     * Bulk variant — same REPLACE semantics as [insertOrUpdate].
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(rates: List<ExchangeRate>)
    
    @Query("SELECT * FROM exchange_rates WHERE fromCurrency = :fromCurrency AND toCurrency = :toCurrency LIMIT 1")
    suspend fun getRate(fromCurrency: String, toCurrency: String): ExchangeRate?
    
    @Query("SELECT * FROM exchange_rates WHERE fromCurrency = :fromCurrency AND toCurrency = :toCurrency LIMIT 1")
    fun getRateFlow(fromCurrency: String, toCurrency: String): Flow<ExchangeRate?>
    
    /**
     * Returns all exchange rates where the target/to currency matches [targetCurrency].
     * CURR-10: Renamed from getAllRatesForBase for clarity — the query filters on
     * `toCurrency`, not `fromCurrency`, so "getRatesToCurrency" is more accurate.
     *
     * @param targetCurrency The currency code (e.g. "EUR") to filter rates by target.
     */
    @Query("SELECT * FROM exchange_rates WHERE toCurrency = :targetCurrency ORDER BY fromCurrency")
    fun getRatesToCurrency(targetCurrency: String): Flow<List<ExchangeRate>>
    
    @Query("SELECT * FROM exchange_rates ORDER BY lastUpdated DESC LIMIT 1")
    suspend fun getLatestRate(): ExchangeRate?
    
    @Query("DELETE FROM exchange_rates WHERE lastUpdated < :olderThan")
    suspend fun deleteOldRates(olderThan: Long)

    @Query("SELECT * FROM exchange_rates WHERE fromCurrency = :fromCurrency AND toCurrency = :toCurrency AND validDate <= :validDate ORDER BY validDate DESC LIMIT 1")
    suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, validDate: Long): ExchangeRate?

    @Query("SELECT COUNT(*) FROM exchange_rates")
    suspend fun getRateCount(): Int

    @Query("DELETE FROM exchange_rates")
    suspend fun deleteAllRates()
}
