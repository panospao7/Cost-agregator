package com.yourname.expensetracker.data.privacy.applock

import android.os.SystemClock
import com.yourname.expensetracker.domain.privacy.applock.MonotonicClock
import javax.inject.Inject

/**
 * Production [MonotonicClock]: `SystemClock.elapsedRealtime()`, which is monotonic and keeps counting
 * during deep sleep (domain-spec 3.4). It is not a wall-clock read.
 */
class SystemMonotonicClock @Inject constructor() : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}
