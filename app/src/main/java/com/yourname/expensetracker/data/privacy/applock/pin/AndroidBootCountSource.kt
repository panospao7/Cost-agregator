package com.yourname.expensetracker.data.privacy.applock.pin

import android.content.Context
import android.provider.Settings
import com.yourname.expensetracker.domain.privacy.applock.pin.BootCountSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Reads `Settings.Global.BOOT_COUNT`. Any exception or a negative value gives null (S24 design decision 3). */
@Singleton
class AndroidBootCountSource @Inject constructor(
    @ApplicationContext private val context: Context
) : BootCountSource {

    override fun currentBootCount(): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).takeIf { it >= 0 }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
