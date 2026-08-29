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
 * backgrounding the app. Broadcasts ACTION_EXPORT_RESULT when done so the
 * Activity can show the result even though it's a separate component.
 *
 * NOTE: ExportRequestHolder is a static in-memory handoff -- fine for this
 * scaffold, not fine for production (the service can be killed/restarted by
 * the system independently of the Activity). Replace with a Room-backed
 * lookup by project ID for anything real.
 */
@UnstableApi
class ExportWorkerService : Service() {

    companion object {
        const val CHANNEL_ID = "export_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val ACTION_EXPORT_RESULT = "com.example.videoeditor.EXPORT_RESULT"
        const val EXTRA_RESULT_SUCCESS = "result_success"
        const val EXTRA_RESULT_MESSAGE = "result_message"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification(0))

        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT_PATH)
            ?: "${filesDir}/export_${System.currentTimeMillis()}.mp4"

        val project: Project? = ExportRequestHolder.pendingProject

        if (project == null) {
            broadcastResult(false, "No project data found (app may have been killed)")
            stopSelf()
            return START_NOT_STICKY
        }

        val exporter = TimelineExporter(this)
        exporter.export(
            project = project,
            outputFile = File(outputPath),
            onProgress = { progress -> updateNotification(progress) },
            onComplete = { success, file, error ->
                notifyFinished(success, file, error)
                broadcastResult(success, if (success) (file?.name ?: outputPath) else (error?.message ?: "unknown error"))
                stopSelf()
            }
        )

        return START_NOT_STICKY
    }

    private fun broadcastResult(success: Boolean, message: String) {
        val intent = Intent(ACTION_EXPORT_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_RESULT_SUCCESS, success)
            putExtra(EXTRA_RESULT_MESSAGE, message)
        }
        sendBroadcast(intent)
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
