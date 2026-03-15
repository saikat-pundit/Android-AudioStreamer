package com.example.audiostreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.OutputStream
import java.net.Socket

class AudioStreamService : Service() {
    
    private val CHANNEL_ID = "AudioStreamChannel"
    private val NOTIFICATION_ID = 1001
    
    private var audioRecord: AudioRecord? = null
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isStreaming = false
    private var streamingThread: Thread? = null
    
    // Audio configuration
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, 
        CHANNEL_CONFIG, 
        AUDIO_FORMAT
    ) * 4
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_STREAMING" -> {
                val ipAddress = intent.getStringExtra("IP_ADDRESS") ?: "192.168.1.100"
                val port = intent.getIntExtra("PORT", 8080)
                startStreaming(ipAddress, port)
            }
            "STOP_STREAMING" -> {
                stopStreaming()
            }
        }
        return START_NOT_STICKY
    }
    
    private fun startStreaming(ipAddress: String, port: Int) {
        if (isStreaming) return
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())
        
        isStreaming = true
        
        streamingThread = Thread {
            try {
                Log.d("AudioStream", "Connecting to $ipAddress:$port")
                
                // Connect to Ubuntu
                socket = Socket(ipAddress, port)
                outputStream = socket?.getOutputStream()
                
                Log.d("AudioStream", "Connected successfully")
                
                // Initialize AudioRecord
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_CALL, // Try to capture all audio
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE
                )
                
                // If VOICE_CALL doesn't work, try REMOTE_SUBMIX
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.REMOTE_SUBMIX,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        BUFFER_SIZE
                    )
                }
                
                audioRecord?.startRecording()
                
                val buffer = ByteArray(BUFFER_SIZE)
                
                while (isStreaming && socket?.isConnected == true) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        try {
                            outputStream?.write(buffer, 0, bytesRead)
                            outputStream?.flush()
                        } catch (e: Exception) {
                            Log.e("AudioStream", "Error sending data", e)
                            break
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e("AudioStream", "Streaming error", e)
            } finally {
                cleanup()
            }
        }
        
        streamingThread?.start()
    }
    
    private fun stopStreaming() {
        isStreaming = false
        cleanup()
        stopForeground(true)
        stopSelf()
    }
    
    private fun cleanup() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
        outputStream = null
        socket = null
    }
    
    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        
        return builder
            .setContentTitle("Audio Streamer")
            .setContentText("Streaming audio to desktop...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Stream Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
