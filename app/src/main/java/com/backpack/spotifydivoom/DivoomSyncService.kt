package com.backpack.spotifydivoom

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.UUID

class DivoomSyncService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var targetMac: String? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_ART = "ACTION_UPDATE_ART"
        const val ACTION_TEST_RED = "ACTION_TEST_RED"
        const val EXTRA_MAC = "EXTRA_MAC"
        const val EXTRA_IMAGE_BYTES = "EXTRA_IMAGE_BYTES"
        private const val CHANNEL_ID = "divoom_spotify_sync"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(101, buildNotification("Sync Active"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                targetMac = intent.getStringExtra(EXTRA_MAC)
                targetMac?.let { connectBluetooth(it) }
            }
            ACTION_TEST_RED -> {
                sendTestPattern()
            }
            ACTION_UPDATE_ART -> {
                val bytes = intent.getByteArrayExtra(EXTRA_IMAGE_BYTES)
                if (bytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    pushBitmapToBackpack(bmp)
                }
            }
            ACTION_STOP -> {
                disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetooth(mac: String) {
        scope.launch {
            try {
                disconnect()
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@launch
                val device: BluetoothDevice = adapter.getRemoteDevice(mac)
                adapter.cancelDiscovery()

                try {
                    socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    socket?.connect()
                    outputStream = socket?.outputStream
                    showToast("Connected to Backpack!")
                } catch (e: Exception) {
                    try {
                        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        socket = method.invoke(device, 1) as BluetoothSocket
                        socket?.connect()
                        outputStream = socket?.outputStream
                        showToast("Connected via fallback port!")
                    } catch (ex: Exception) {
                        showToast("Bluetooth Connection Failed: ${ex.message}")
                        return@launch
                    }
                }

                // Initial display mode wake
                try {
                    outputStream?.write(DivoomPacketEncoder.setBrightness(100))
                    outputStream?.flush()
                } catch (_: Exception) {}

            } catch (e: Exception) {
                showToast("Bluetooth Error: ${e.message}")
            }
        }
    }

    private fun sendTestPattern() {
        scope.launch {
            try {
                if (socket == null || socket?.isConnected != true) {
                    targetMac?.let { connectBluetooth(it) }
                    delay(300)
                }

                showToast("Testing display commands...")

                // 1. Cycle channel to Custom/DIY mode
                outputStream?.write(DivoomPacketEncoder.switchChannel(0x02))
                outputStream?.flush()
                delay(60)

                // 2. Set max brightness
                outputStream?.write(DivoomPacketEncoder.setBrightness(100))
                outputStream?.flush()
                delay(60)

                // 3. Write solid red frame
                val redFrame = DivoomPacketEncoder.encodeSolidColor(255, 0, 0)
                outputStream?.write(redFrame)
                outputStream?.flush()

                showToast("Red frame sent!")
            } catch (e: Exception) {
                showToast("Test failed: ${e.message}")
            }
        }
    }

    private fun pushBitmapToBackpack(bmp: android.graphics.Bitmap) {
        scope.launch {
            try {
                if (socket == null || socket?.isConnected != true) {
                    targetMac?.let { connectBluetooth(it) }
                    delay(300)
                }

                // Switch off clock mode
                outputStream?.write(DivoomPacketEncoder.switchChannel(0x02))
                outputStream?.flush()
                delay(50)

                // Push frame
                val frame = DivoomPacketEncoder.encodeImage(bmp)
                outputStream?.write(frame)
                outputStream?.flush()

                showToast("Art pushed to backpack!")
            } catch (e: Exception) {
                showToast("Push failed: ${e.message}")
            }
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun disconnect() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        outputStream = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Backpack Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Divoom Pixel Backpack")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        disconnect()
        job.cancel()
        super.onDestroy()
    }
}
