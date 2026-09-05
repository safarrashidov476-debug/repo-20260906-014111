package com.example.screenrecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RECORDING_STOPPED = "com.example.screenrecorder.RECORDING_STOPPED"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var pfd: android.os.ParcelFileDescriptor? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = getParcelableExtraCompat(intent, EXTRA_RESULT_DATA, Intent::class.java)
                // NOTE: on success Android returns resultCode == Activity.RESULT_OK == -1,
                // so checking "resultCode != -1" would ALWAYS reject a granted permission.
                // The real signal that consent was obtained is a non-null data Intent.
                if (data != null) {
                    try {
                        startForeground(NOTIFICATION_ID, buildNotification())
                    } catch (e: Exception) {
                        // Foreground start can fail on some OEMs/background restrictions.
                        e.printStackTrace()
                        showErrorAndStop("Xizmatni fon rejimida ishga tushirib bo'lmadi")
                        return START_NOT_STICKY
                    }
                    startRecording(resultCode, data)
                } else {
                    showErrorAndStop("Ekranni yozib olish uchun ruxsat olinmadi")
                }
            }
            ACTION_STOP -> {
                stopRecording()
                sendStoppedBroadcast()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun <T : Parcelable> getParcelableExtraCompat(
        intent: Intent,
        name: String,
        clazz: Class<T>
    ): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, clazz)
        } else {
            intent.getParcelableExtra(name)
        }
    }

    private fun sendStoppedBroadcast() {
        val intent = Intent(ACTION_RECORDING_STOPPED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun showErrorAndStop(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        sendStoppedBroadcast()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, pendingFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.stop_recording),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        try {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                showErrorAndStop("Ekranni yozib olish ruxsati olinmadi")
                return
            }

            val metrics = DisplayMetrics()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            if (width <= 0 || height <= 0) {
                showErrorAndStop("Ekran o'lchamini aniqlab bo'lmadi")
                return
            }

            val prepared = setupMediaRecorder(width, height)
            if (!prepared) {
                showErrorAndStop("Video yozishni boshlab bo'lmadi")
                return
            }

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                    sendStoppedBroadcast()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, mainHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecorder",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface,
                null, null
            )

            if (virtualDisplay == null) {
                showErrorAndStop("Virtual displey yaratib bo'lmadi")
                return
            }

            mediaRecorder?.start()
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorAndStop("Yozishni boshlashda xatolik: ${e.message}")
        }
    }

    /** Returns true if MediaRecorder was successfully prepared. */
    private fun setupMediaRecorder(width: Int, height: Int): Boolean {
        return try {
            val fileName = "ScreenRecord_${System.currentTimeMillis()}.mp4"
            val resolver = contentResolver

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecorder")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            val currentUri = outputUri ?: return false

            pfd = resolver.openFileDescriptor(currentUri, "w") ?: run {
                resolver.delete(currentUri, null, null)
                outputUri = null
                return false
            }

            // Video size must have even width/height for the H264 encoder on most devices.
            val safeWidth = if (width % 2 == 0) width else width - 1
            val safeHeight = if (height % 2 == 0) height else height - 1

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(safeWidth, safeHeight)
                setVideoEncodingBitRate(8 * 1024 * 1024)
                setVideoFrameRate(30)
                setOutputFile(pfd!!.fileDescriptor)
                prepare()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanupFailedOutput()
            false
        }
    }

    private fun cleanupFailedOutput() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
        outputUri?.let {
            try {
                contentResolver.delete(it, null, null)
            } catch (_: Exception) {
            }
        }
        outputUri = null
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
            // Recording was too short or already stopped; remove the broken/empty file.
            outputUri?.let {
                try {
                    contentResolver.delete(it, null, null)
                } catch (_: Exception) {
                }
            }
        }
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaRecorder = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        virtualDisplay = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaProjection = null

        try {
            pfd?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        pfd = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputUri?.let {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(it, values, null, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        outputUri = null
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
