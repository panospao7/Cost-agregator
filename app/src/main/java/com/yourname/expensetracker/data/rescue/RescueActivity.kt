package com.yourname.expensetracker.data.rescue

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.util.SystemTimeProvider
import java.io.File

/**
 * Simple Activity that lets the user manually trigger the financial rescue.
 *
 * When [RescueConfig.ENABLE_FINANCIAL_RESCUE] is true, this activity can
 * be launched directly (e.g. via the app launcher or ADB) to perform a
 * one-time database recovery without going through the normal startup flow.
 */
class RescueActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var rescueButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rescue)

        statusText = findViewById(R.id.rescue_status)
        rescueButton = findViewById(R.id.rescue_button)

        rescueButton.setOnClickListener {
            runRescue()
        }

        // Check if rescue already done
        if (File(filesDir, "rescue_completed.txt").exists()) {
            statusText.text = "Rescue already completed.\nDatabase is fresh."
            rescueButton.isEnabled = false
        } else {
            statusText.text = "Ready to rescue.\nTap the button to start."
        }
    }

    private fun runRescue() {
        rescueButton.isEnabled = false
        statusText.text = "Running rescue..."

        val coordinator = FinancialRescueCoordinator(this, SystemTimeProvider())
        Thread {
            val result = coordinator.runRescueIfNeeded()
            runOnUiThread {
                statusText.text = buildString {
                    append("Result: ")
                    when (result) {
                        is RescueResult.SUCCESS -> {
                            append("SUCCESS\nAll financial data recovered.")
                        }
                        is RescueResult.SKIPPED -> {
                            append("SKIPPED\nRescue is disabled in config.")
                        }
                        is RescueResult.ALREADY_DONE -> {
                            append("ALREADY DONE\nRescue was previously completed.")
                        }
                        is RescueResult.NO_DB -> {
                            append("NO DATABASE\nNothing to rescue.")
                        }
                        is RescueResult.FAILURE -> {
                            append("FAILURE\n${result.error.message}")
                            rescueButton.isEnabled = true // allow retry
                        }
                    }
                }
            }
        }.start()
    }
}
