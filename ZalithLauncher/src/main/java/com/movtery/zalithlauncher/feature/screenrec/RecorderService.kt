package com.movtery.zalithlauncher.feature.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.movtery.zalithlauncher.R

class RecorderService : Service() {
    companion object {
        const val ACTION_START = "com.movtery.zalithlauncher.screenrec.START"
        const val ACTION_STOP = "com.movtery.zalithlauncher.screenrec.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.movtery.zalithlauncher.screenrec.TOGGLE_PAUSE"
        private const val EXTRA_CODE = "result_code"
        private const val EXTRA_DATA = "result_data"
        private const val CHANNEL_ID = "screen_recorder"
        private const val NOTIFICATION_ID = 7121

        @JvmStatic
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, RecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        @JvmStatic
        fun stop(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val listener = ScreenRecorder.StateListener { state ->
        if (state == RecState.RECORDING || state == RecState.PAUSED) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.recorder_notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        ScreenRecorder.addListener(listener)
    }

    override fun onDestroy() {
        ScreenRecorder.removeListener(listener)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_TOGGLE_PAUSE -> {
                if (ScreenRecorder.state == RecState.PAUSED) ScreenRecorder.resume() else ScreenRecorder.pause()
            }
            else -> if (ScreenRecorder.state == RecState.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        enterForeground(buildNotification(RecState.RECORDING))
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            fail()
            return
        }
        val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        if (projection == null || !ScreenRecorder.start(this, projection)) {
            fail()
            return
        }
        Toast.makeText(this, R.string.recorder_started, Toast.LENGTH_SHORT).show()
    }

    private fun handleStop() {
        ScreenRecorder.stop { file ->
            val message = if (file != null) R.string.recorder_saved else R.string.recorder_error
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail() {
        Toast.makeText(this, R.string.recorder_error, Toast.LENGTH_SHORT).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun enterForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun actionIntent(action: String, code: Int): PendingIntent {
        val intent = Intent(this, RecorderService::class.java).setAction(action)
        return PendingIntent.getService(this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(state: RecState): Notification {
        val paused = state == RecState.PAUSED
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.recorder_notification_title))
            .setContentText(getString(if (paused) R.string.recorder_paused else R.string.recorder_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                getString(if (paused) R.string.recorder_resume else R.string.recorder_pause),
                actionIntent(ACTION_TOGGLE_PAUSE, 1)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.recorder_stop),
                actionIntent(ACTION_STOP, 2)
            )
            .build()
    }
}
