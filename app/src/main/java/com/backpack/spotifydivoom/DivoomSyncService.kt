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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class DivoomSyncService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var targetMac: String? = null
    private var crsCode: String = "NCL"
    private var syncJob: Job? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_TEST_NCL = "ACTION_TEST_NCL"
        const val EXTRA_MAC = "EXTRA_MAC"
        const val EXTRA_CRS = "EXTRA_CRS"
        private const val CHANNEL_ID = "divoom_rail_sync"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(101, buildNotification("Rail Sync Active (NCL)"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                targetMac = intent.getStringExtra(EXTRA_MAC)
                crsCode = intent.getStringExtra(EXTRA_CRS) ?: "NCL"
                targetMac?.let { mac ->
                    connectBluetooth(mac) {
                        startDeparturePolling()
                    }
                }
            }
            ACTION_TEST_NCL -> {
                scope.launch {
                    fetchAndPushDepartures()
                }
            }
            ACTION_STOP -> {
                stopPolling()
                disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startDeparturePolling() {
        stopPolling()
        syncJob = scope.launch {
            while (isActive) {
                fetchAndPushDepartures()
                delay(60000) // Refresh every 60 seconds
            }
        }
    }

    private fun stopPolling() {
        syncJob?.cancel()
        syncJob = null
    }

    private suspend fun fetchAndPushDepartures() {
        try {
            val tickerText = fetchLiveDepartures(crsCode)
            if (tickerText.isNotBlank()) {
                sendMarquee(tickerText)
            }
        } catch (e: Exception) {
            showToast("Fetch Error: ${e.message}")
        }
    }

    private fun fetchLiveDepartures(crs: String): String {
        return try {
            val url = URL("https://huxley2.azurewebsites.net/departures/$crs?expand=false")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val services = json.optJSONArray("trainServices")
                val stationName = json.optString("locationName", crs)

                if (services != null && services.length() > 0) {
                    val sb = StringBuilder("=== $stationName ===  ")
                    for (i in 0 until minOf(4, services.length())) {
                        val svc = services.getJSONObject(i)
                        val std = svc.optString("std", "--:--")
                        val etd = svc.optString("etd", "On time")
                        val destArray = svc.optJSONArray("destination")
                        val destName = destArray?.getJSONObject(0)?.optString("locationName", "Unknown") ?: "Unknown"
                        val platform = svc.optString("platform", "")
                        val platStr = if (platform.isNotEmpty()) " [P$platform]" else ""

                        sb.append("$std $destName$platStr: $etd   |   ")
                    }
                    sb.toString()
                } else {
                    "$stationName: No departures found."
                }
            } else {
                "National Rail: Error ${conn.responseCode}"
            }
        } catch (e: Exception) {
            "National Rail API unreachable"
        }
    }

    private fun sendMarquee(text: String) {
        try {
            if (socket == null || socket?.isConnected != true) {
                targetMac?.let { connectBluetooth(it) {} }
                Thread.sleep(400)
            }

            outputStream?.write(DivoomPacketEncoder.setBrightness(100))
            outputStream?.flush()
            Thread.sleep(50)

            outputStream?.write(DivoomPacketEncoder.switchChannel(0x02))
            outputStream?.flush()
            Thread.sleep(50)

            val marqueePkt = DivoomPacketEncoder.encodeMarqueeText(text)
            outputStream?.write(marqueePkt)
            outputStream?.flush()

            showToast("Departures sent to Backpack!")
        } catch (e: Exception) {
            showToast("Push failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetooth(mac: String, onConnected: (() -> Unit)? = null) {
        scope.launch {
            try {
                disconnect()
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@launch
                val device: BluetoothDevice = adapter.getRemoteDevice(mac)
                adapter.cancelDiscovery()

                try {
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    socket?.connect()
                    outputStream = socket?.outputStream
                    showToast("Connected to Backpack!")
                    onConnected?.invoke()
                } catch (e: Exception) {
                    try {
                        socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                        socket?.connect()
                        outputStream = socket?.outputStream
                        showToast("Connected via fallback!")
                        onConnected?.invoke()
                    } catch (ex: Exception) {
                        showToast("Bluetooth Connection Failed: ${ex.message}")
                    }
                }
            } catch (e: Exception) {
                showToast("Bluetooth Error: ${e.message}")
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
                "Backpack Rail Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Divoom National Rail")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopPolling()
        disconnect()
        job.cancel()
        super.onDestroy()
    }
}
