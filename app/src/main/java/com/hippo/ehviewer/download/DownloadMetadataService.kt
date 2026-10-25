package com.hippo.ehviewer.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hippo.app.CheckBoxDialogBuilder
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.sync.GalleryDetailTagsSyncTask
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.download.DownloadsScene
import com.hippo.scene.StageActivity
import java.util.ArrayDeque
import java.util.concurrent.Executors

/** A user-started metadata queue that outlives the download screen. */
class DownloadMetadataService : Service() {
    private data class Gallery(val gid: Long, val token: String)

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val queue = ArrayDeque<Gallery>()
    private lateinit var notifications: NotificationManager
    private lateinit var downloads: DownloadManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var request: EhRequest? = null
    @Volatile private var running = false
    private var onlyMissing = true
    private var total = 0
    private var processed = 0
    private var saved = 0
    private var failed = 0

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, getString(R.string.download_metadata_fetch),
                NotificationManager.IMPORTANCE_LOW
            ))
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish(R.string.download_metadata_stopped)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) {
            if (running) Toast.makeText(this, R.string.download_metadata_running, Toast.LENGTH_SHORT).show()
            else stopSelf()
            return START_NOT_STICKY
        }

        running = true
        isRunning = true
        onlyMissing = intent.getBooleanExtra(EXTRA_ONLY_MISSING, true)
        val notification = notification(getString(R.string.download_metadata_preparing))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:metadata").apply {
            setReferenceCounted(false)
            acquire()
        }
        downloads = EhApplication.getDownloadManager(this)
        // Snapshot all labels. Local imports and entries without a gallery token cannot be fetched.
        val galleries = downloads.allDownloadInfoList.mapNotNull { info ->
            val token = info.token
            if (info.gid <= 0 || token.isNullOrBlank() || !info.archiveUri.isNullOrBlank()) null
            else Gallery(info.gid, token)
        }
        worker.execute {
            try {
                for (gallery in galleries) {
                    if (!running) return@execute
                    if (!onlyMissing || !EhDB.inGalleryTags(gallery.gid)) queue.add(gallery)
                }
                handler.post {
                    if (!running) return@post
                    total = queue.size
                    advance()
                }
            } catch (e: Exception) {
                failQueue(e)
            }
        }
        return START_NOT_STICKY
    }

    /** Recheck missing-only on the worker, since another fetch may have saved tags meanwhile. */
    private fun advance() {
        if (!running) return
        if (processed >= total) {
            finish(R.string.download_metadata_complete)
            return
        }
        notifications.notify(NOTIFICATION_ID, notification(progressText()))
        val delay = limiter.delayMillis(SystemClock.elapsedRealtime())
        if (delay > 0) {
            handler.postDelayed({ advance() }, delay)
            return
        }
        worker.execute {
            try {
                var skipped = 0
                var gallery: Gallery? = null
                while (running && queue.isNotEmpty()) {
                    val candidate = queue.removeFirst()
                    if (onlyMissing && EhDB.inGalleryTags(candidate.gid)) skipped++
                    else {
                        gallery = candidate
                        break
                    }
                }
                val next = gallery
                handler.post {
                    if (!running) return@post
                    processed += skipped
                    if (next == null) finish(R.string.download_metadata_complete)
                    else fetch(next)
                }
            } catch (e: Exception) {
                failQueue(e)
            }
        }
    }

    private fun fetch(gallery: Gallery) {
        if (!running) return
        // A download removed during the run must not be recreated by a late response.
        if (downloads.getDownloadInfo(gallery.gid) == null) {
            processed++
            advance()
            return
        }
        val pending = EhRequest()
        request = pending
        limiter.onRequestStarted(SystemClock.elapsedRealtime())
        pending.setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
            .setArgs(EhUrl.getGalleryDetailUrl(gallery.gid, gallery.token))
            .setCallback(object : EhClient.Callback<GalleryDetail> {
                override fun onSuccess(result: GalleryDetail) {
                    if (!running || request !== pending) return
                    limiter.onRequestFinished(SystemClock.elapsedRealtime())
                    request = null
                    if (result.gid != gallery.gid) {
                        failed++
                        processed++
                        advance()
                        return
                    }
                    worker.execute {
                        val error = try {
                            GalleryDetailTagsSyncTask(result).save()
                            null
                        } catch (e: Exception) {
                            Log.e(TAG, "Could not save metadata for ${gallery.gid}", e)
                            e
                        }
                        handler.post {
                            if (!running) return@post
                            if (error == null) {
                                try {
                                    downloads.updateGalleryMetadata(result)
                                    saved++
                                } catch (e: Exception) {
                                    Log.e(TAG, "Could not update download ${gallery.gid}", e)
                                    failed++
                                }
                            } else failed++
                            processed++
                            advance()
                        }
                    }
                }

                override fun onFailure(e: Exception) {
                    if (!running || request !== pending) return
                    limiter.onRequestFinished(SystemClock.elapsedRealtime())
                    request = null
                    Log.w(TAG, "Could not fetch metadata for ${gallery.gid}", e)
                    failed++
                    processed++
                    advance()
                }

                override fun onCancel() {
                    if (!running || request !== pending) return
                    limiter.onRequestFinished(SystemClock.elapsedRealtime())
                    request = null
                    finish(R.string.download_metadata_stopped)
                }
            })
        EhApplication.getEhClient(this).execute(pending)
    }

    private fun failQueue(error: Exception) {
        Log.e(TAG, "Metadata queue failed", error)
        handler.post { if (running) finish(R.string.download_metadata_error) }
    }

    private fun progressText() = getString(
        R.string.download_metadata_progress, processed, total, saved, failed
    )

    private fun notification(text: String, active: Boolean = true, title: String? = null): Notification {
        val openDownloads = Intent(this, MainActivity::class.java).apply {
            action = StageActivity.ACTION_START_SCENE
            putExtra(StageActivity.KEY_SCENE_NAME, DownloadsScene::class.java.name)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title ?: getString(R.string.download_metadata_fetch))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(this, NOTIFICATION_ID, openDownloads, INTENT_FLAGS))
            .setOngoing(active)
            .setAutoCancel(!active)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (active) {
            val stop = Intent(this, DownloadMetadataService::class.java).setAction(ACTION_STOP)
            builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(total, processed, total == 0)
                .addAction(R.drawable.v_pause_dark_x24, getString(R.string.download_metadata_stop),
                    PendingIntent.getService(this, NOTIFICATION_ID, stop, INTENT_FLAGS))
        }
        return builder.build()
    }

    private fun finish(title: Int) {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (request != null) limiter.onRequestFinished(SystemClock.elapsedRealtime())
        request?.cancel()
        request = null
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notifications.notify(NOTIFICATION_ID, notification(progressText(), false, getString(title)))
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        if (request != null) limiter.onRequestFinished(SystemClock.elapsedRealtime())
        request?.cancel()
        request = null
        worker.shutdownNow()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DownloadMetadata"
        private const val CHANNEL_ID = "download_metadata"
        private const val NOTIFICATION_ID = 0x4D455441
        private const val ACTION_START = "download_metadata_start"
        private const val ACTION_STOP = "download_metadata_stop"
        private const val EXTRA_ONLY_MISSING = "only_missing"
        private const val INTENT_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // Retained across Stop/Start in this process so restarting cannot bypass the limit.
        private val limiter = MetadataRequestLimiter()
        @Volatile private var isRunning = false

        @JvmStatic
        fun showDialog(context: Context) {
            if (isRunning) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.download_metadata_fetch)
                    .setMessage(R.string.download_metadata_running)
                    .setPositiveButton(R.string.download_metadata_stop) { _, _ ->
                        context.startService(Intent(context, DownloadMetadataService::class.java).setAction(ACTION_STOP))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return
            }
            val builder = CheckBoxDialogBuilder(context,
                context.getString(R.string.download_metadata_summary) + "\n\n" +
                    context.getString(R.string.download_metadata_only_missing_summary),
                context.getString(R.string.download_metadata_only_missing),
                Settings.getDownloadMetadataOnlyMissing())
            builder.setTitle(R.string.download_metadata_fetch)
                .setPositiveButton(R.string.download_metadata_start) { _, _ ->
                    Settings.putDownloadMetadataOnlyMissing(builder.isChecked)
                    val start = Intent(context, DownloadMetadataService::class.java).apply {
                        action = ACTION_START
                        putExtra(EXTRA_ONLY_MISSING, builder.isChecked)
                    }
                    ContextCompat.startForegroundService(context, start)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
