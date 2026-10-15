package com.backpack.spotifydivoom

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SpotifyMediaListenerService : NotificationListenerService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var lastTrackKey: String? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            metadata?.let { inspectAndExtract(it, null) }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        registerMediaControllers()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.packageName == "com.spotify.music") {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
            val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

            // Extract bitmap directly from notification extras
            var notifBitmap: Bitmap? = null

            // 1. Try Large Icon
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val icon = extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG, Icon::class.java)
                    ?: extras.getParcelable(Notification.EXTRA_LARGE_ICON, Icon::class.java)
                icon?.let {
                    try {
                        val drawable = it.loadDrawable(this)
                        if (drawable is android.graphics.drawable.BitmapDrawable) {
                            notifBitmap = drawable.bitmap
                        }
                    } catch (_: Exception) {}
                }
            }

            if (notifBitmap == null) {
                @Suppress("DEPRECATION")
                notifBitmap = extras.getParcelable(Notification.EXTRA_PICTURE)
                    ?: extras.getParcelable(Notification.EXTRA_LARGE_ICON)
            }

            inspectAndExtract(null, notifBitmap, title, artist)
        }
    }

    private fun registerMediaControllers() {
        try {
            val sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, SpotifyMediaListenerService::class.java)
            val controllers = sessionManager.getActiveSessions(componentName)

            for (controller in controllers) {
                if (controller.packageName == "com.spotify.music") {
                    controller.registerCallback(callback)
                    controller.metadata?.let { inspectAndExtract(it, null) }
                    break
                }
            }
        } catch (_: Exception) {}
    }

    private fun inspectAndExtract(
        metadata: MediaMetadata?,
        directBitmap: Bitmap?,
        fallbackTitle: String = "",
        fallbackArtist: String = ""
    ) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: fallbackTitle
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: fallbackArtist

        if (title.isBlank()) return
        val trackKey = "$title - $artist"
        if (trackKey == lastTrackKey) return
        lastTrackKey = trackKey

        showToast("Now Playing: $title")

        scope.launch {
            // Priority 1: Direct Bitmap from Notification Icon
            var bitmap: Bitmap? = directBitmap

            // Priority 2: Direct Bitmap from MediaSession
            if (bitmap == null && metadata != null) {
                bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            }

            // Priority 3: URI from MediaSession
            if (bitmap == null && metadata != null) {
                val uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                if (!uriStr.isNullOrEmpty()) {
                    bitmap = fetchBitmapFromUri(uriStr)
                }
            }

            // Priority 4: Universal open metadata fallback (No API key needed)
            if (bitmap == null) {
                bitmap = fetchFallbackArt(title, artist)
            }

            if (bitmap != null) {
                sendBitmapToService(bitmap)
            } else {
                showToast("Could not resolve art for: $title")
            }
        }
    }

    private suspend fun fetchBitmapFromUri(uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (uriString.startsWith("http")) {
                val url = URL(uriString)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } else {
                val uri = Uri.parse(uriString)
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchFallbackArt(title: String, artist: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("$title $artist", "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$query&media=music&entity=song&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonObj = JSONObject(jsonStr)
            val results = jsonObj.optJSONArray("results")
            if (results != null && results.length() > 0) {
                val artworkUrl = results.getJSONObject(0).optString("artworkUrl100")
                if (artworkUrl.isNotEmpty()) {
                    val artConn = URL(artworkUrl).openConnection() as HttpURLConnection
                    artConn.connectTimeout = 5000
                    return@withContext artConn.inputStream.use { BitmapFactory.decodeStream(it) }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun sendBitmapToService(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val byteArray = stream.toByteArray()

        val intent = Intent(this, DivoomSyncService::class.java).apply {
            action = DivoomSyncService.ACTION_UPDATE_ART
            putExtra(DivoomSyncService.EXTRA_IMAGE_BYTES, byteArray)
        }
        startService(intent)
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }
}
