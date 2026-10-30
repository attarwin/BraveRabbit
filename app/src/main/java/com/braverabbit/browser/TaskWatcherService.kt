package com.braverabbit.browser

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Runs the privacy clean when the user swipes Brave Rabbit away from the recent-apps list. */
class TaskWatcherService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Prefs.autoClean) {
            PrivacyCleaner.clean(this) { stopSelf() }
        } else {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }
}
