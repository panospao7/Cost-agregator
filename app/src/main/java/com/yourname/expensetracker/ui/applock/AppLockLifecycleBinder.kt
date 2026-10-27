package com.yourname.expensetracker.ui.applock

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.diagnostics.DiagnosticThrowableSummary
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one source of the foreground and background signals of the app lock (INV-25, TECH-3 in the plan), and the
 * one caller of [AppLockController.resolve] (S20-a). `MainActivity.onCreate` (S20-c) calls [install] once per
 * process, so `MainApplication` is not edited.
 *
 * ## Started-activity count (AE-41)
 *
 * It registers `Application.ActivityLifecycleCallbacks` and keeps an exact count of started activities:
 * - 0 to 1 calls [AppLockController.onAppForegrounded];
 * - 1 to 0 calls [AppLockController.onAppBackgrounded], unless that stop is a configuration change.
 *
 * A configuration change stops and restarts the Activity: the stop is not reported, and the matching start
 * (the next 0 to 1) is not reported either, so a rotation, like the shade or split-screen (which never stop the
 * Activity), is neither a background nor a foreground. A start that follows a real, reported stop is always
 * reported, also when the system recreates the Activity for a configuration change while it was in the background.
 *
 * If that matching start never comes (the relaunched instance is destroyed without starting, for example it
 * finishes in `onCreate`), the held-back background is reported when that instance is destroyed
 * (`onActivityDestroyed` with the count at zero, a pending suppression and `isChangingConfigurations == false`),
 * once, and the next start is then a foreground.
 *
 * Every real stop is reported, including the stops caused by the app's own prompts (an OEM credential Activity):
 * the binder never suppresses an event while a prompt is in flight. The controller decides what each one means
 * (no T13 from LOCKED or AUTHENTICATING; T32 to T34 for the open-origin prompts).
 *
 * ## Resolution
 *
 * At [install] it launches [AppLockController.resolve] on the application scope, and again on every start while
 * the controller state is still `Resolving` (a scope that was cancelled leaves `Resolving` until a later call
 * resolves it). `resolve` is idempotent, so extra calls return at once. A `CancellationException` is rethrown;
 * any other exception from `resolve` is logged by class name only (never a message or stack trace).
 *
 * The binder must be installed before the first Activity starts. Started Activities are counted by identity: an
 * Activity already started when the binder is installed is never counted, and the later stop of an
 * Activity that was never counted is ignored, so it can neither cancel a counted start nor report a background
 * (the count is never negative and a late install cannot cause a missed background).
 *
 * Main thread only: the lifecycle callbacks run on it and the count is confined to it.
 */
@Singleton
class AppLockLifecycleBinder @Inject constructor(
    private val controller: AppLockController,
    @ApplicationScope private val scope: CoroutineScope
) {

    private val installed = AtomicBoolean(false)

    // Main-thread confined (Application.ActivityLifecycleCallbacks run on the main thread).
    // The started Activities, by IDENTITY: only an Activity whose start was counted can later be un-counted by its
    // stop. An Activity that started before the binder was installed is never in the set, so its later stop cannot
    // cancel the start of a counted Activity (that would be a fail-open background miss).
    private val startedActivities: MutableSet<Activity> =
        Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private var suppressNextForeground = false

    private val startedCount: Int
        get() = startedActivities.size

    /** The exact number of started activities as the binder counts them. Test hook only. */
    @get:VisibleForTesting
    internal val startedActivityCount: Int
        get() = startedCount

    /**
     * Registers the lifecycle callbacks and launches the first resolution. Idempotent: only the first call
     * registers and launches; later calls (with any application) do nothing.
     */
    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(callbacks)
        launchResolve()
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            // A repeated start of an instance that is already counted changes nothing.
            if (startedActivities.add(activity) && startedCount == 1) {
                if (suppressNextForeground) {
                    suppressNextForeground = false // the start that completes a configuration change
                } else {
                    controller.onAppForegrounded()
                }
            }
            if (controller.state.value is AppLockState.Resolving) launchResolve()
        }

        override fun onActivityStopped(activity: Activity) {
            // A stop of an Activity whose start was never counted (it started before install) is ignored.
            if (!startedActivities.remove(activity)) return
            if (startedCount != 0) return
            if (activity.isChangingConfigurations) {
                suppressNextForeground = true
            } else {
                suppressNextForeground = false
                controller.onAppBackgrounded()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            // An unpaired configuration-change stop: the stop was held back as a rotation, but the relaunched
            // instance was destroyed without ever starting (for example it finished in onCreate), so no start will
            // consume the suppression. The background is reported now, once, and the next start is a foreground.
            if (startedCount == 0 && suppressNextForeground && !activity.isChangingConfigurations) {
                suppressNextForeground = false
                controller.onAppBackgrounded()
            }
        }
    }

    private fun launchResolve() {
        scope.launch {
            try {
                controller.resolve()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("App-lock resolve failed: class=%s", DiagnosticThrowableSummary.className(e))
            }
        }
    }
}
