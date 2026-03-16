package com.example.audiostreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.util.ArrayList

class FtpService : Service() {

    private val NOTIFICATION_ID = 2001
    private val CHANNEL_ID = "ftp_service_channel"
    private var ftpServer: FtpServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FTP Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs the FTP Server"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Streamer FTP")
            .setContentText("FTP server running on port 2121")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_FTP" -> {
                startForeground(NOTIFICATION_ID, createNotification())
                startFtpServer()
            }
            "STOP_FTP" -> {
                stopFtpServer()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startFtpServer() {
        if (ftpServer != null && !ftpServer!!.isStopped) return

        try {
            val serverFactory = FtpServerFactory()
            val factory = ListenerFactory()
            factory.port = 2121
            serverFactory.addListener("default", factory.createListener())

            val userManager = serverFactory.userManager
            val user = BaseUser()
            user.name = "anonymous"
            user.homeDirectory = Environment.getExternalStorageDirectory().absolutePath
            
            val auths = ArrayList<org.apache.ftpserver.ftplet.Authority>()
            auths.add(WritePermission())
            user.authorities = auths
            userManager.save(user)

            ftpServer = serverFactory.createServer()
            ftpServer?.start()
            Log.d("FtpService", "FTP Server started on port 2121")
        } catch (e: Exception) {
            Log.e("FtpService", "Failed to start FTP server", e)
        }
    }

    private fun stopFtpServer() {
        try {
            ftpServer?.stop()
            ftpServer = null
        } catch (e: Exception) {
            Log.e("FtpService", "Error stopping FTP server", e)
        }
    }

    override fun onDestroy() {
        stopFtpServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
