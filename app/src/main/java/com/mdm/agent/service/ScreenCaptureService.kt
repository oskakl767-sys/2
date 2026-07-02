package com.mdm.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/**
 * Service that manages MediaProjection for screen capture.
 * Once started with a MediaProjection intent, it keeps the projection alive
 * and can capture screenshots on demand.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "screen_capture"

        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        /** Called by MDMService to request a screenshot */
        fun requestScreenshot(context: Context, callback: ((File?) -> Unit)) {
            val svc = instance
            if (svc == null) {
                Log.e(TAG, "ScreenCaptureService not running")
                callback(null)
                return
            }
            svc.takeScreenshot(callback)
        }

        /** Check if MediaProjection is active and ready */
        fun isReady(): Boolean = instance?.mediaProjection != null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private val handler = Handler(Looper.getMainLooper())

    // Pending screenshot callbacks
    private val pendingCallbacks = mutableListOf<(File?) -> Unit>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initDisplayMetrics()
        Log.i(TAG, "ScreenCaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            releaseProjection()
            stopSelf()
            return START_NOT_STICKY
        }

        // Get MediaProjection from intent
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val resultCode = intent?.getIntExtra("result_code", 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("result_data", android.content.Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("result_data")
        }

        if (resultCode != 0 && resultData != null) {
            setupProjection(mpm, resultCode, resultData)
        } else {
            Log.e(TAG, "No MediaProjection data in intent")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseProjection()
        instance = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun initDisplayMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun setupProjection(mpm: MediaProjectionManager, resultCode: Int, resultData: Intent) {
        try {
            mediaProjection = mpm.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped")
                    releaseProjection()
                }
            }, handler)

            setupVirtualDisplay()
            Log.i(TAG, "MediaProjection setup successful - ready for screenshots!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup MediaProjection: ${e.message}")
        }
    }

    private fun setupVirtualDisplay() {
        val projection = mediaProjection ?: return

        // Use lower resolution for reliability and speed
        val width = (screenWidth * 0.8).toInt()
        val height = (screenHeight * 0.8).toInt()
        val density = (screenDensity * 0.8).toInt()

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = projection.createVirtualDisplay(
            "MDMScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, handler
        )

        Log.i(TAG, "VirtualDisplay created: ${width}x${height} @ ${density}dpi")
    }

    fun takeScreenshot(callback: (File?) -> Unit) {
        val reader = imageReader
        if (reader == null || mediaProjection == null) {
            Log.e(TAG, "Cannot take screenshot - no ImageReader or MediaProjection")
            callback(null)
            return
        }

        // Set up the listener for the next available image
        reader.setOnImageAvailableListener({ imgReader ->
            try {
                val image: Image? = imgReader.acquireLatestImage()
                if (image == null) {
                    Log.w(TAG, "Null image from ImageReader")
                    handler.post { deliverCallbacks(null) }
                    return@setOnImageAvailableListener
                }

                val planes = image.planes
                if (planes.isEmpty()) {
                    image.close()
                    handler.post { deliverCallbacks(null) }
                    return@setOnImageAvailableListener
                }

                val plane = planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * reader.width

                // Create bitmap
                val bitmap = Bitmap.createBitmap(
                    reader.width + rowPadding / pixelStride,
                    reader.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Crop to actual screen size if needed
                val croppedBitmap = if (bitmap.width > reader.width || bitmap.height > reader.height) {
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, reader.width, reader.height)
                    bitmap.recycle()
                    cropped
                } else {
                    bitmap
                }

                // Save to file
                val outputFile = File(cacheDir, "ss_${System.currentTimeMillis()}.png")
                FileOutputStream(outputFile).use { out ->
                    croppedBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                croppedBitmap.recycle()

                Log.i(TAG, "Screenshot saved: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                handler.post { deliverCallbacks(outputFile) }
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot capture error: ${e.message}")
                handler.post { deliverCallbacks(null) }
            }
        }, handler)
    }

    private fun deliverCallbacks(file: File?) {
        synchronized(pendingCallbacks) {
            for (cb in pendingCallbacks) {
                cb.invoke(file)
            }
            pendingCallbacks.clear()
        }
    }

    private fun releaseProjection() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing projection: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = ""
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setDefaults(0)
                .setPriority(Notification.PRIORITY_MIN)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setShowWhen(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setDefaults(0)
                .setPriority(Notification.PRIORITY_MIN)
                .build()
        }
    }
}
