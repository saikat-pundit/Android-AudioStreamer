package com.example.audiostreamer

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    
    private lateinit var ipAddressInput: EditText
    private lateinit var portInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var prefs: SharedPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize views
        ipAddressInput = findViewById(R.id.ipAddressInput)
        portInput = findViewById(R.id.portInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)
        
        prefs = getSharedPreferences("AudioStreamer", MODE_PRIVATE)
        
        // Load saved settings
        ipAddressInput.setText(prefs.getString("ip_address", "192.168.1.100"))
        portInput.setText(prefs.getString("port", "8080"))
        
        // Set click listeners
        startButton.setOnClickListener { startStreaming() }
        stopButton.setOnClickListener { stopStreaming() }
        
        // Check if service is running
        updateStatus(false)
    }
    
    private fun startStreaming() {
        val ipAddress = ipAddressInput.text.toString()
        val port = portInput.text.toString().toIntOrNull() ?: 8080
        
        if (ipAddress.isEmpty()) {
            Toast.makeText(this, "Please enter IP address", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Save settings
        prefs.edit().apply {
            putString("ip_address", ipAddress)
            putString("port", port.toString())
            apply()
        }
        
        // Request permissions first
        PermissionManager(this).requestAllPermissions()
        
        // Start service
        val intent = Intent(this, AudioStreamService::class.java).apply {
            putExtra("IP_ADDRESS", ipAddress)
            putExtra("PORT", port)
            action = "START_STREAMING"
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        updateStatus(true)
        Toast.makeText(this, "Streaming started to $ipAddress:$port", Toast.LENGTH_LONG).show()
    }
    
    private fun stopStreaming() {
        val intent = Intent(this, AudioStreamService::class.java).apply {
            action = "STOP_STREAMING"
        }
        startService(intent)
        
        updateStatus(false)
        Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateStatus(isStreaming: Boolean) {
        if (isStreaming) {
            statusText.text = "Status: Streaming Active"
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            startButton.isEnabled = false
            stopButton.isEnabled = true
        } else {
            statusText.text = "Status: Not Streaming"
            statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }
    
    class PermissionManager(private val activity: MainActivity) {
        
        fun requestAllPermissions() {
            requestRecordAudio()
            requestBatteryOptimization()
            requestNotificationPermission()
        }
        
        private fun requestRecordAudio() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(
                        arrayOf(android.Manifest.permission.RECORD_AUDIO),
                        1001
                    )
                }
            }
        }
        
        private fun requestBatteryOptimization() {
            val pm = activity.getSystemService(PowerManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                }
            }
        }
        
        private fun requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        1002
                    )
                }
            }
        }
    }
}
