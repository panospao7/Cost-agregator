package com.yourname.expensetracker.data.database

import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockk
import java.io.FileNotFoundException

/** Supplies exported schemas from test resources without adding them to a release APK. */
fun migrationTestInstrumentation(): Instrumentation {
    val actual = InstrumentationRegistry.getInstrumentation()
    val schemaAssets = mockk<AssetManager>()
    every { schemaAssets.open(any<String>()) } answers {
        val schemaName = firstArg<String>()
        AppDatabase::class.java.classLoader!!.getResourceAsStream(schemaName)
            ?: throw FileNotFoundException("TEST_SCHEMA_MISSING")
    }
    val schemaContext = object : ContextWrapper(actual.context) {
        override fun getAssets(): AssetManager = schemaAssets
    }
    return mockk {
        every { context } returns schemaContext
        every { targetContext } returns actual.targetContext
    }
}
