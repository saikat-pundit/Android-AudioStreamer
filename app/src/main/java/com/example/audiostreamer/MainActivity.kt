package com.example.audiostreamer

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    
    // UI Elements
    private lateinit var tabLayout: TabLayout
    private lateinit var streamingContainer: LinearLayout
    private lateinit var ftpContainer: LinearLayout
    private lateinit var webcamContainer: LinearLayout
    
    private lateinit var toggleFtpButton: Button
    private lateinit var ftpStatusText: TextView
    private lateinit var ipAddressInput: EditText
    private lateinit var portInput: EditText
    private lateinit var toggleStreamButton: Button
    private lateinit var statusText: TextView
    
    private lateinit var toggleWebcamButton: Button
    private lateinit var webcamStatusText: TextView
    private lateinit var viewFinder: PreviewView
    
    // State & Managers
    private var isFtpRunning = false
    private var isWebcamRunning = false
    private lateinit var prefs: SharedPreferences
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val mjpegServer = MjpegServer(8081)
    private lateinit var cameraExecutor: ExecutorService
    
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
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Bind UI Elements
        tabLayout = findViewById(R.id.tabLayout)
        streamingContainer = findViewById(R.id.streamingContainer)
        ftpContainer = findViewById(R.id.ftpContainer)
        webcamContainer = findViewById(R.id.webcamContainer)
        
        ipAddressInput = findViewById(R.id.ipAddressInput)
        portInput = findViewById(R.id.portInput)
        toggleStreamButton = findViewById(R.id.toggleStreamButton)
        statusText = findViewById(R.id.statusText)
        
        toggleFtpButton = findViewById(R.id.toggleFtpButton)
        ftpStatusText = findViewById(R.id.ftpStatusText)
        
        toggleWebcamButton = findViewById(R.id.toggleWebcamButton)
        webcamStatusText = findViewById(R.id.webcamStatusText)
        viewFinder = findViewById(R.id.viewFinder)
        
        prefs = getSharedPreferences("AudioStreamer", MODE_PRIVATE)
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        
        ipAddressInput.setText(prefs.getString("ip_address", "192.168.1.100"))
        portInput.setText(prefs.getString("port", "8080"))

        // Create Tabs Programmatically
        if (tabLayout.tabCount == 0) {
            tabLayout.addTab(tabLayout.newTab().setText("Streamer"))
            tabLayout.addTab(tabLayout.newTab().setText("FTP"))
            tabLayout.addTab(tabLayout.newTab().setText("Webcam"))
        }
        
        // Setup Tab Navigation
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                streamingContainer.visibility = View.GONE
                ftpContainer.visibility = View.GONE
                webcamContainer.visibility = View.GONE
                
                when (tab?.position) {
                    0 -> streamingContainer.visibility = View.VISIBLE
                    1 -> ftpContainer.visibility = View.VISIBLE
                    2 -> webcamContainer.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        
        // Audio Streamer Button
        toggleStreamButton.setOnClickListener {
            if (toggleStreamButton.text.toString() == "START STREAMING") {
                startStreamingProcess()
            } else {
                stopStreaming()
            }
        }
        
        // FTP Button
        toggleFtpButton.setOnClickListener {
            if (!isFtpRunning) {
                PermissionManager(this).requestAllPermissions()
                val intent = Intent(this, FtpService::class.java).apply { action = "START_FTP" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                isFtpRunning = true
                updateFtpStatus()
            } else {
                val intent = Intent(this, FtpService::class.java).apply { action = "STOP_FTP" }
                startService(intent)
                isFtpRunning = false
                updateFtpStatus()
            }
        }
        
        // Webcam Button
        toggleWebcamButton.setOnClickListener {
            if (!isWebcamRunning) {
                PermissionManager(this).requestAllPermissions()
                if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    startCamera()
                    mjpegServer.start()
                    isWebcamRunning = true
                    
                    val ip = getLocalIpAddress()
                    webcamStatusText.text = "Webcam: http://$ip:8081"
                    webcamStatusText.setTextColor(getColor(android.R.color.holo_blue_light))
                    toggleWebcamButton.text = "STOP WEBCAM SERVER"
                    toggleWebcamButton.backgroundTintList = ColorStateList.valueOf(getColor(android.R.color.holo_red_dark))
                } else {
                    Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                }
            } else {
                mjpegServer.stop()
                
                // Unbind camera
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                }, ContextCompat.getMainExecutor(this))
                
                isWebcamRunning = false
                webcamStatusText.text = "Webcam: Stopped"
                webcamStatusText.setTextColor(getColor(android.R.color.holo_red_dark))
                toggleWebcamButton.text = "START WEBCAM SERVER"
                toggleWebcamButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
            }
        }
        
        updateStatus(false)
        updateFtpStatus()
    }
    
    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val bitmap = imageProxy.toBitmap()
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                        mjpegServer.currentJpeg = stream.toByteArray()
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch(exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun updateFtpStatus() {
        if (isFtpRunning) {
            val deviceIp = getLocalIpAddress()
            ftpStatusText.text = "FTP: ftp://$deviceIp:2121"
            ftpStatusText.setTextColor(getColor(android.R.color.holo_blue_light))
            toggleFtpButton.text = "STOP FTP SERVER"
            toggleFtpButton.backgroundTintList = ColorStateList.valueOf(getColor(android.R.color.holo_red_dark))
        } else {
            ftpStatusText.text = "FTP: Stopped"
            ftpStatusText.setTextColor(getColor(android.R.color.holo_red_dark))
            toggleFtpButton.text = "START FTP SERVER"
            toggleFtpButton.backgroundTintList = ColorStateList.valueOf(getColor(android.R.color.holo_blue_dark))
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "Unknown_IP"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Unknown_IP"
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
            toggleStreamButton.backgroundTintList = ColorStateList.valueOf(getColor(android.R.color.holo_red_dark))
        } else {
            statusText.text = "Status: Not Streaming"
            statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            toggleStreamButton.text = "START STREAMING"
            toggleStreamButton.backgroundTintList = ColorStateList.valueOf(getColor(android.R.color.holo_green_dark))
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mjpegServer.stop()
        cameraExecutor.shutdown()
    }
    
    class PermissionManager(private val activity: MainActivity) {
        fun requestAllPermissions() {
            requestRecordAudio()
            requestCamera()
            requestBatteryOptimization()
            requestNotificationPermission()
            requestStoragePermission()
        }
        
        private fun requestRecordAudio() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
                }
            }
        }

        private fun requestCamera() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (activity.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1003)
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
        
        private fun requestStoragePermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.addCategory("android.intent.category.DEFAULT")
                        intent.data = Uri.parse("package:${activity.packageName}")
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        activity.startActivity(intent)
                    }
                }
            }
        }
    }
}

// Embedded MJPEG Server Class
class MjpegServer(private val port: Int) {
    @Volatile var isRunning = false
    @Volatile var currentJpeg: ByteArray? = null
    private var serverThread: Thread? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        serverThread = Thread {
            val serverSocket = ServerSocket(port)
            while (isRunning) {
                try {
                    val client = serverSocket.accept()
                    handleClient(client)
                } catch (e: Exception) {
                    Log.e("MjpegServer", "Accept error", e)
                }
            }
            serverSocket.close()
        }
        serverThread?.start()
    }

    fun stop() {
        isRunning = false
        serverThread?.interrupt()
    }

    private fun handleClient(socket: Socket) {
        Thread {
            try {
                val out: OutputStream = socket.getOutputStream()
                out.write(("HTTP/1.0 200 OK\r\n" +
                        "Connection: close\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Content-type: multipart/x-mixed-replace; boundary=--BoundaryString\r\n\r\n").toByteArray())

                while (isRunning && socket.isConnected) {
                    currentJpeg?.let { jpeg ->
                        out.write(("--BoundaryString\r\n" +
                                "Content-type: image/jpeg\r\n" +
                                "Content-Length: ${jpeg.size}\r\n\r\n").toByteArray())
                        out.write(jpeg)
                        out.write("\r\n\r\n".toByteArray())
                        out.flush()
                    }
                    Thread.sleep(33) // ~30 fps
                }
            } catch (e: Exception) {
                // Ignore disconnects
            } finally {
                socket.close()
            }
        }.start()
    }
}
