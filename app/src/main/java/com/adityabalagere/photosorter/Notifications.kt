package com.adityabalagere.photosorter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Overall progress of the running job - shown in the app and in the notification. */
data class JobProgress(
    val title: String,
    val done: Long = 0,
    val total: Long = 0,
    val detail: String = "",
) {
    /** 0..1, or null when the total is unknown (indeterminate). */
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

object Notifications {
    const val CH_PROGRESS = "progress"
    const val CH_RESULTS = "results"
    const val ID_PROGRESS = 1
    const val ID_RESULT = 2

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows progress while photos are scanned or filed in the background"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULTS, "Results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a scan or filing run has finished"
            }
        )
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The ongoing notification of [WorkService]: title, detail line, progress bar, Stop button. */
    fun progress(ctx: Context, job: JobProgress?): Notification {
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, WorkService::class.java).setAction(WorkService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = job?.title ?: "PhotoSorter is working"
        val f = job?.fraction
        val counter = if (job != null && job.total > 0 && job.total < 1_000_000) "${job.done}/${job.total}" else ""
        return NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(job?.detail?.ifEmpty { null } ?: counter)
            .setSubText(if (f != null) "${(f * 100).toInt()}%" else null)
            .setProgress(1000, ((f ?: 0f) * 1000).toInt(), f == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Stop", stop)
            .build()
    }

    /** One-off "finished" notification (only posted while the app is in the background). */
    fun showResult(ctx: Context, title: String, text: String) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_RESULTS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID_RESULT, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancelResult(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_RESULT)
}
