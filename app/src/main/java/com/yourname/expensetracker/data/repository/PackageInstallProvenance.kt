package com.yourname.expensetracker.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.yourname.expensetracker.domain.ai.service.InstallOrigin
import com.yourname.expensetracker.domain.ai.service.InstallProvenance
import timber.log.Timber

/**
 * [InstallProvenance] backed by the own-package [PackageInfo] install and update times (spec 1.8).
 *
 * - firstInstallTime == lastUpdateTime -> NEW_INSTALL
 * - firstInstallTime <  lastUpdateTime -> UPGRADE
 * - anything else (missing times, clock anomaly, any exception) -> UNKNOWN
 *
 * Only the app's own package is queried: no new permission, no QUERY_ALL_PACKAGES, no network.
 * It is not Hilt-injected: the AI settings DataStore delegate builds it from the application
 * context when the store is first created (see AiSettingsRepositoryImpl).
 */
internal class PackageInstallProvenance(
    private val context: Context
) : InstallProvenance {

    override fun classify(): InstallOrigin {
        return try {
            classify(readOwnPackageInfo())
        } catch (_: Exception) {
            // classify() is not suspend and runs no cancellable work. Bounded diagnostic only:
            // no exception message, stack trace or package details are logged.
            Timber.w("Install provenance unavailable; treating as UNKNOWN")
            InstallOrigin.UNKNOWN
        }
    }

    private fun readOwnPackageInfo(): PackageInfo {
        val packageManager = context.packageManager
        val packageName = context.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
    }

    private fun classify(info: PackageInfo): InstallOrigin {
        val first = info.firstInstallTime
        val last = info.lastUpdateTime
        return when {
            first <= 0L || last <= 0L -> InstallOrigin.UNKNOWN
            first == last -> InstallOrigin.NEW_INSTALL
            first < last -> InstallOrigin.UPGRADE
            else -> InstallOrigin.UNKNOWN
        }
    }
}
