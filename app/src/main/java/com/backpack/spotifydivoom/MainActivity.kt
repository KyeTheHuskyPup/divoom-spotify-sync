package com.backpack.spotifydivoom

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerDevices: Spinner
    private lateinit var etStation: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnTest: Button
    private val pairedList = mutableListOf<BluetoothDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerDevices = findViewById(R.id.spinnerDevices)
        etStation = findViewById(R.id.etStation)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnTest = findViewById(R.id.btnTest)

        checkPermissions()
        loadPairedDevices()

        btnStart.setOnClickListener {
            if (pairedList.isNotEmpty()) {
                val selected = pairedList[spinnerDevices.selectedItemPosition]
                val crs = etStation.text.toString().trim().uppercase().ifEmpty { "NCL" }
                val intent = Intent(this, DivoomSyncService::class.java).apply {
                    action = DivoomSyncService.ACTION_START
                    putExtra(DivoomSyncService.EXTRA_MAC, selected.address)
                    putExtra(DivoomSyncService.EXTRA_CRS, crs)
                }
                startService(intent)
                Toast.makeText(this, "Starting Live Board for $crs...", Toast.LENGTH_SHORT).show()
            }
        }

        btnTest.setOnClickListener {
            val intent = Intent(this, DivoomSyncService::class.java).apply {
                action = DivoomSyncService.ACTION_TEST_NCL
            }
            startService(intent)
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, DivoomSyncService::class.java).apply {
                action = DivoomSyncService.ACTION_STOP
            }
            startService(intent)
            Toast.makeText(this, "Sync Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        pairedList.clear()
        val names = mutableListOf<String>()

        val bonded = adapter.bondedDevices
        for (dev in bonded) {
            pairedList.add(dev)
            names.add("${dev.name ?: "Unknown"} (${dev.address})")
        }

        val arrayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinnerDevices.adapter = arrayAdapter
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        }
    }
}
