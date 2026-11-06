package com.yourname.expensetracker.ui.screens.captureskip

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Resolves a display label for a package name (P-01-3 "Apps to skip").
 *
 * Uses only [PackageManager.getApplicationInfo] and [PackageManager.getApplicationLabel]; no
 * package-visibility permission or `<queries>` is declared, so an app the OS hides from us (or an
 * uninstalled app) falls back to the package name itself. Results are cached per instance; nothing
 * is logged (package names are never written to logs).
 *
 * Blocking binder calls: callers must run [label] off the main thread.
 */
open class AppLabelResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, String>()

    open fun label(packageName: String): String =
        cache.getOrPut(packageName) { resolve(packageName) }

    private fun resolve(packageName: String): String {
        val pm = context.packageManager
        return try {
            val label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
                .toString()
                .trim()
            label.ifEmpty { packageName }
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        } catch (e: RuntimeException) {
            // Includes SecurityException from package-visibility filtering; fall back, never throw.
            packageName
        }
    }
}
