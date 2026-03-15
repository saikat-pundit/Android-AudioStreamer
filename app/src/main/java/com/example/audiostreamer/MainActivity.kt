package com.example.audiostreamer

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    
    private lateinit var ipAddressInput: EditText
    private lateinit var portInput: EditText
    private lateinit var toggleStreamButton: Button
    private lateinit var statusText: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var mediaProjectionManager: MediaProjectionManager
    
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startAudioService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Permission denied to capture audio", Toast.LENGTH_SHORT).show()
            updateStatus(false)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        ipAddressInput = findViewById(R.id.ipAddressInput)
        portInput = findViewById(R.id.portInput)
        toggleStreamButton = findViewById(R.id.toggleStreamButton)
        statusText = findViewById(R.id.statusText)
        
        prefs = getSharedPreferences("AudioStreamer", MODE_PRIVATE)
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        
        ipAddressInput.setText(prefs.getString("ip_address", "192.168.1.100"))
        portInput.setText(prefs.getString("port", "8080"))
        
        toggleStreamButton.setOnClickListener {
            if (toggleStreamButton.text.toString() == "START STREAMING") {
                startStreamingProcess()
            } else {
                stopStreaming()
            }
        }
        
        updateStatus(false)
    }
    
    private fun startStreamingProcess() {
        val ipAddress = ipAddressInput.text.toString()
        if (ipAddress.isEmpty()) {
            Toast.makeText(this, "Please enter IP address", Toast.LENGTH_SHORT).show()
            return
        }
        
        prefs.edit().apply {
            putString("ip_address", ipAddress)
            putString("port", portInput.text.toString())
            apply()
        }
        
        PermissionManager(this).requestAllPermissions()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            screenCaptureLauncher.launch(intent)
        } else {
            startAudioService(Activity.RESULT_OK, Intent())
        }
    }
    
    private fun startAudioService(resultCode: Int, data: Intent) {
        val ipAddress = ipAddressInput.text.toString()
        val port = portInput.text.toString().toIntOrNull() ?: 8080
        
        val intent = Intent(this, AudioStreamService::class.java).apply {
            action = "START_STREAMING"
            putExtra("IP_ADDRESS", ipAddress)
            putExtra("PORT", port)
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
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
    }
    
    private fun updateStatus(isStreaming: Boolean) {
        if (isStreaming) {
            statusText.text = "Status: Streaming Active"
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            toggleStreamButton.text = "STOP STREAMING"
            toggleStreamButton.backgroundTintList = getColorStateList(android.R.color.holo_red_dark)
        } else {
            statusText.text = "Status: Not Streaming"
            statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            toggleStreamButton.text = "START STREAMING"
            toggleStreamButton.backgroundTintList = getColorStateList(android.R.color.holo_green_dark)
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
                if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
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
                if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
                }
            }
        }
    }
}
