package com.yourname.expensetracker.util

import dagger.hilt.android.testing.HiltAndroidRule
import org.junit.Rule
import org.robolectric.annotation.Config

/**
 * Base class for Hilt + Robolectric tests.
 * Uses HiltTestApplication from robolectric.properties (global config)
 * or override with @Config(application = HiltTestApplication::class) on subclass.
 *
 * Usage:
 * ```
 * @HiltAndroidTest
 * @Config(application = HiltTestApplication::class)
 * class NotificationCaptureServiceTest : HiltTestUtils() {
 *     @get:Rule(order = 0)
 *     override val hiltRule = HiltAndroidRule(this)
 *
 *     @Inject
 *     lateinit var service: NotificationCaptureService
 *
 *     @Before
 *     fun setup() {
 *         hiltRule.inject()
 *     }
 * }
 * ```
 */
abstract class HiltTestUtils {

    @get:Rule(order = 0)
    open val hiltRule: HiltAndroidRule = HiltAndroidRule(this)
}
