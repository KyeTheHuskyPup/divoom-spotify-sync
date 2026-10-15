package com.backpack.spotifydivoom

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.backpack.spotifydivoom.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val pairedDevices = mutableListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        loadPairedDevices()

        binding.btnStart.setOnClickListener {
            if (!isNotificationAccessGranted()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                Toast.makeText(this, "Enable Notification Access for this app", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val selectedIndex = binding.spinnerDevices.selectedItemPosition
            if (selectedIndex >= 0 && selectedIndex < pairedDevices.size) {
                val mac = pairedDevices[selectedIndex].second
                val serviceIntent = Intent(this, DivoomSyncService::class.java).apply {
                    action = DivoomSyncService.ACTION_START
                    putExtra(DivoomSyncService.EXTRA_MAC, mac)
                }
                startForegroundService(serviceIntent)
                Toast.makeText(this, "Sync Started!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStop.setOnClickListener {
            val serviceIntent = Intent(this, DivoomSyncService::class.java).apply {
                action = DivoomSyncService.ACTION_STOP
            }
            startService(serviceIntent)
            Toast.makeText(this, "Sync Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val devices = adapter.bondedDevices
        pairedDevices.clear()
        val names = mutableListOf<String>()
        for (device in devices) {
            val name = device.name ?: "Unknown"
            pairedDevices.add(Pair(name, device.address))
            names.add("$name (${device.address})")
        }
        val arrayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerDevices.adapter = arrayAdapter
    }

    private fun isNotificationAccessGranted(): Boolean {
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val component = ComponentName(this, SpotifyMediaListenerService::class.java).flattenToString()
        return listeners != null && listeners.contains(component)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }
}
