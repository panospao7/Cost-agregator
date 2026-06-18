package com.yourname.expensetracker.data.rescue

/**
 * Toggle for the financial rescue path.
 * Set to true to enable raw-SQLite recovery on next app launch.
 * The rescue coordinator checks this flag before running.
 */
object RescueConfig {
    // Set to true ONLY for the rescue build. Set back to false and rebuild
    // before any normal use. Leaving this true in a production build allows
    // any app to launch RescueActivity and trigger data destruction.
    const val ENABLE_FINANCIAL_RESCUE = false
}
