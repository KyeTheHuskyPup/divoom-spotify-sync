package com.backpack.spotifydivoom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class SpotifyMediaListenerService : NotificationListenerService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var lastTrackId: String? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            metadata?.let { processMetadata(it) }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        registerMediaControllers()
    }

    private fun registerMediaControllers() {
        try {
            val sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, SpotifyMediaListenerService::class.java)
            val controllers = sessionManager.getActiveSessions(componentName)

            for (controller in controllers) {
                if (controller.packageName == "com.spotify.music") {
                    controller.registerCallback(callback)
                    controller.metadata?.let { processMetadata(it) }
                    break
                }
            }
        } catch (_: Exception) {}
    }

    private fun processMetadata(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val trackKey = "$title-$artist"

        if (trackKey == lastTrackId) return
        lastTrackId = trackKey

        showToast("Playing: $title")

        scope.launch {
            var bitmap: Bitmap? = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

            if (bitmap == null) {
                val artUriString = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)

                if (!artUriString.isNullOrEmpty()) {
                    bitmap = fetchBitmapFromUri(artUriString)
                }
            }

            if (bitmap != null) {
                sendBitmapToService(bitmap)
            } else {
                showToast("Could not extract art for $title")
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
                conn.inputStream.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } else {
                val uri = Uri.parse(uriString)
                contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
        } catch (e: Exception) {
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
