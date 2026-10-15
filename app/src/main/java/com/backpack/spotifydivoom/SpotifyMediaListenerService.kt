package com.backpack.spotifydivoom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import java.io.ByteArrayOutputStream

class SpotifyMediaListenerService : NotificationListenerService() {
    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var lastTrackTitle: String? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            handleMediaUpdate(activeController)
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            if (state?.state == PlaybackState.STATE_PLAYING) {
                handleMediaUpdate(activeController)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        attachSessionListener()
    }

    private fun attachSessionListener() {
        val component = ComponentName(this, SpotifyMediaListenerService::class.java)
        sessionManager?.addOnActiveSessionsChangedListener({ controllers ->
            findAndAttachSpotify(controllers)
        }, component)
        findAndAttachSpotify(sessionManager?.getActiveSessions(component))
    }

    private fun findAndAttachSpotify(controllers: List<MediaController>?) {
        controllers?.firstOrNull { it.packageName.contains("spotify") }?.let { controller ->
            activeController?.unregisterCallback(callback)
            activeController = controller
            controller.registerCallback(callback)
            handleMediaUpdate(controller)
        }
    }

    private fun handleMediaUpdate(controller: MediaController?) {
        val metadata = controller?.metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        if (title == lastTrackTitle) return
        lastTrackTitle = title

        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        bitmap?.let { sendBitmapToService(it) }
    }

    private fun sendBitmapToService(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val byteArray = stream.toByteArray()
        val intent = Intent(this, DivoomSyncService::class.java).apply {
            action = DivoomSyncService.ACTION_UPDATE_ART
            putExtra(DivoomSyncService.EXTRA_IMAGE_BYTES, byteArray)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        activeController?.unregisterCallback(callback)
        super.onDestroy()
    }
}
