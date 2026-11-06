package com.yourname.expensetracker.ui.screens.captureskip

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P-01-3: [AppLabelResolver] shows the OS label when it can read one and otherwise the package name
 * itself; it never throws. Synthetic package names only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLabelResolverTest {

    private fun installedApp(context: Context, packageName: String, label: CharSequence?) {
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            nonLocalizedLabel = label
        }
        val packageInfo = PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = appInfo
        }
        shadowOf(context.packageManager).installPackage(packageInfo)
    }

    @Test
    fun `label is the application label when the package is readable`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installedApp(context, "com.synthetic.bank", "Synthetic Bank")

        assertEquals("Synthetic Bank", AppLabelResolver(context).label("com.synthetic.bank"))
    }

    @Test
    fun `label is trimmed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installedApp(context, "com.synthetic.padded", "  Padded Name  ")

        assertEquals("Padded Name", AppLabelResolver(context).label("com.synthetic.padded"))
    }

    @Test
    fun `an unknown package falls back to the package name on NameNotFoundException`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("com.synthetic.missing", AppLabelResolver(context).label("com.synthetic.missing"))
    }

    @Test
    fun `a blank label falls back to the package name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installedApp(context, "com.synthetic.blank", "   ")

        assertEquals("com.synthetic.blank", AppLabelResolver(context).label("com.synthetic.blank"))
    }

    @Test
    fun `a SecurityException from package visibility filtering falls back to the package name`() {
        val pm = mockk<PackageManager>()
        val context = mockk<Context>()
        every { context.packageManager } returns pm
        every { pm.getApplicationInfo("com.synthetic.hidden", 0) } throws SecurityException("filtered")

        assertEquals("com.synthetic.hidden", AppLabelResolver(context).label("com.synthetic.hidden"))
    }

    @Test
    fun `the result is cached per package so the package manager is asked once`() {
        val pm = mockk<PackageManager>()
        val context = mockk<Context>()
        val appInfo = mockk<ApplicationInfo>()
        every { context.packageManager } returns pm
        every { pm.getApplicationInfo("com.synthetic.cached", 0) } returns appInfo
        every { pm.getApplicationLabel(appInfo) } returns "Cached App"
        val resolver = AppLabelResolver(context)

        assertEquals("Cached App", resolver.label("com.synthetic.cached"))
        assertEquals("Cached App", resolver.label("com.synthetic.cached"))

        verify(exactly = 1) { pm.getApplicationInfo("com.synthetic.cached", 0) }
    }
}
