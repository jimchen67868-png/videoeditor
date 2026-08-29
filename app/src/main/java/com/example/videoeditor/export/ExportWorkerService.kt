package com.example.videoeditor.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import com.example.videoeditor.model.Project
import java.io.File

/**
 * Runs [TimelineExporter] as a foreground service so export survives the user
 * backgrounding the app. Wire this up from EditorViewModel via
 * ContextCompat.startForegroundService(context, intent).
 *
 * NOTE: this is a minimal stub -- passing the full Project through an Intent
 * isn't ideal for large projects; a production app should look it up from a
 * Room-backed repository by projectId instead.
 */
@UnstableApi
class ExportWorkerService : Service() {

    companion object {
        const val CHANNEL_ID = "export_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_OUTPUT_PATH = "output_path"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification(0))

        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT_PATH)
            ?: "${filesDir}/export_${System.currentTimeMillis()}.mp4"

        // In a real app, fetch the Project from a repository using an ID extra
        // rather than reconstructing it here. Left as a call site placeholder:
        val project: Project? = ExportRequestHolder.pendingProject

        if (project == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val exporter = TimelineExporter(this)
        exporter.export(
            project = project,
            outputFile = File(outputPath),
            onProgress = { progress ->
                updateNotification(progress)
            },
            onComplete = { success, file, error ->
                notifyFinished(success, file, error)
                stopSelf()
            }
        )

        return START_NOT_STICKY
    }

    private fun buildNotification(progress: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exporting video")
            .setContentText("$progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
            .setOngoing(true)
            .build()

    private fun updateNotification(progress: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(progress))
    }

    private fun notifyFinished(success: Boolean, file: File?, error: Throwable?) {
        val manager = getSystemService(NotificationManager::class.java)
        val text = if (success) "Export complete: ${file?.name}" else "Export failed: ${error?.message}"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Video export")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Export", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}

/** Simple in-memory handoff for the stub above; replace with a real repository. */
object ExportRequestHolder {
    var pendingProject: Project? = null
}
