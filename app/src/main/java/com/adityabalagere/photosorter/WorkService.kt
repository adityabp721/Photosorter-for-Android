package com.adityabalagere.photosorter

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and CPU, via a partial wake lock) alive while
 * [AppModel] runs a job, and mirrors the job's progress in an ongoing notification.
 * The work itself lives in AppModel's scope; this service only stops itself when nothing
 * is running any more.
 */
class WorkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = appModel
        if (intent?.action == ACTION_STOP) {
            model.stopAll()
            if (ticker == null) stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_PROGRESS, Notifications.progress(this, model.job), serviceType(model),
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException - the job still runs while the app is open
            Log.w(TAG, "could not enter foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhotoSorter:work")
                .apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L)
                }
        }
        if (ticker == null) {
            ticker = scope.launch {
                val nm = NotificationManagerCompat.from(this@WorkService)
                var last: JobProgress? = null
                while (true) {
                    delay(1000)
                    if (!model.anyBusy) break
                    val j = model.job
                    if (j != last && Notifications.canPost(this@WorkService)) {
                        last = j
                        try {
                            nm.notify(Notifications.ID_PROGRESS, Notifications.progress(this@WorkService, j))
                        } catch (_: SecurityException) {
                        }
                    }
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Downloading the model is a data transfer (dataSync); sorting, trip scans and duplicate scans
     * process photos on the device (mediaProcessing, Android 15+).
     */
    private fun serviceType(model: AppModel): Int = when {
        Build.VERSION.SDK_INT < 29 -> 0
        model.vlmBusy || Build.VERSION.SDK_INT < 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    }

    /** Android 15+ time limit for dataSync / mediaProcessing services (6 h per day): stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        appModel.stopAll()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.adityabalagere.photosorter.STOP"
        private const val TAG = "WorkService"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, WorkService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start service", e)
            }
        }
    }
}
