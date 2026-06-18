package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks business mileage for tax deduction purposes.
 * Users can log trips with start/end locations, distance, and purpose.
 */
@Entity(
    tableName = "mileage_tracking",
    foreignKeys = [
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["linkedExpenseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["linkedExpenseId"]),
        Index(value = ["date"]),
        Index(value = ["isBusinessTrip"])
    ]
)
data class MileageTracking(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val date: Long, // Trip date
    
    // Distance tracking
    val startOdometer: Double? = null, // Starting odometer reading (km)
    val endOdometer: Double? = null,   // Ending odometer reading (km)
    val distanceKm: Double,              // Calculated or manual distance
    
    // Locations (optional, for reference)
    val startLocation: String? = null,
    val endLocation: String? = null,
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    val endLatitude: Double? = null,
    val endLongitude: Double? = null,
    
    // Business details
    val isBusinessTrip: Boolean = true,
    val tripPurpose: String, // e.g., "Client visit", "Site inspection", "Meeting"
    val businessProject: String? = null,
    val clientName: String? = null,
    
    // Deduction calculation
    val deductionRatePerKm: Double = 0.30, // Default tax rate per km (varies by country/year)
    val calculatedDeduction: Double? = null,  // distanceKm * deductionRatePerKm
    
    // Receipt/expense link (if fuel was purchased for this trip)
    val linkedExpenseId: Long? = null,
    val fuelCost: Double? = null,
    
    // Notes and timestamp
    val notes: String? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L
)
