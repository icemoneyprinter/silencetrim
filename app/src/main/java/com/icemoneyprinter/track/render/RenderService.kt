package com.icemoneyprinter.track.render

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.icemoneyprinter.track.MainActivity
import com.icemoneyprinter.track.R
import com.icemoneyprinter.track.data.JobStore
import com.icemoneyprinter.track.data.PersistedJob
import com.icemoneyprinter.track.model.Timeline
import com.icemoneyprinter.track.pacing.ArcPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the batch render (plan D9): survives screen-off,
 * shows durable progress, and can be cancelled. Job state (timeline + completed arcs)
 * lives in [JobStore], so a process death resumes from the last finished arc.
 */
class RenderService : Service() {

    companion object {
        const val ACTION_START = "com.icemoneyprinter.track.RENDER_START"
        const val ACTION_CANCEL = "com.icemoneyprinter.track.RENDER_CANCEL"
        private const val CHANNEL_ID = "track_render"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            context.startService(Intent(context, RenderService::class.java).setAction(ACTION_START))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, RenderService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!running) {
                    running = true
                    startForegroundCompat("Starting render…")
                    scope.launch { runJob() }
                }
            }
            ACTION_CANCEL -> {
                scope.cancel()
                PipelineRunner(this).cleanup()
                PipelineBus.publish(PipelineStatus.Cancelled)
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runJob() {
        val job: PersistedJob = JobStore.load(this) ?: run {
            failPersistedJob("No render job found — restart from the app")
            return
        }
        val timeline = Timeline(job.slots, job.audioDurationMs)
        val arcs = ArcPlanner.plan(timeline)
        if (arcs.isEmpty()) {
            failPersistedJob("Timeline is empty")
            return
        }
        val runner = PipelineRunner(this)
        runner.run(job, arcs, timeline)
    }

    private fun failPersistedJob(message: String) {
        PipelineBus.publish(PipelineStatus.Failed(message))
        stopForegroundCompat()
        stopSelf()
        running = false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video rendering",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progress of on-device video rendering" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        observeStatus()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RenderService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TRACK")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Cancel", cancelIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun observeStatus() {
        scope.launch {
            PipelineBus.status.collect { status ->
                when (status) {
                    is PipelineStatus.Starting -> updateNotification("Starting render…")
                    is PipelineStatus.RenderingArc -> updateNotification("Rendering ${status.done}/${status.total} arcs")
                    is PipelineStatus.Concatting -> updateNotification("Stitching video…")
                    is PipelineStatus.EncodingAudio -> updateNotification(status.message)
                    is PipelineStatus.Exporting -> updateNotification(status.message)
                    is PipelineStatus.Finished -> {
                        updateNotification("Done — saved to Movies/TRACK")
                        running = false
                        stopForegroundCompat()
                        stopSelf()
                    }
                    is PipelineStatus.Failed -> {
                        updateNotification("Render failed: ${status.message.take(60)}")
                        running = false
                        stopForegroundCompat()
                        stopSelf()
                    }
                    is PipelineStatus.Cancelled, null -> Unit
                }
            }
        }
    }
}
