
package com.simmy.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MessageNotificationService : NotificationListenerService() {

    companion object {
        @Volatile
        var latestMessage: String? = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        val notification = sbn?.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence("android.title")?.toString()
        val message = extras.getCharSequence("android.text")?.toString()

        if (!message.isNullOrBlank()) {
            latestMessage = if (!title.isNullOrBlank()) {
                "$title says: $message"
            } else {
                message
            }
        }
    }
}
