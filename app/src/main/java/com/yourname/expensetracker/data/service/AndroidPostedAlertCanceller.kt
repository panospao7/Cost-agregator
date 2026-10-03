package com.yourname.expensetracker.data.service

import android.app.NotificationManager
import android.content.Context
import com.yourname.expensetracker.domain.service.PostedAlertCanceller
import com.yourname.expensetracker.domain.util.NotificationId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidPostedAlertCanceller @Inject constructor(
    @ApplicationContext context: Context
) : PostedAlertCanceller {

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun cancel(notificationId: NotificationId) {
        try {
            notificationManager.cancel(notificationId.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Timber.w(
                "Posted alert cancellation failed class=%s",
                e::class.java.simpleName
            )
        }
    }
}
