package com.example.audiostreamer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class HeartbeatService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var wakeLock: PowerManager.WakeLock
    
    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AudioStreamer:Heartbeat"
        )
        
        startHeartbeat()
        scheduleWorkManager()
    }
    
    private fun startHeartbeat() {
        serviceScope.launch {
            while (isActive) {
                if (!wakeLock.isHeld) {
                    wakeLock.acquire(10000)
                }
                
                // Check and restart services
                restartServices()
                
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                
                delay(60000) // 1 minute
            }
        }
    }
    
    private fun restartServices() {
        val keepAliveIntent = Intent(this, KeepAliveService::class.java)
        startService(keepAliveIntent)
    }
    
    private fun scheduleWorkManager() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(false)
            .build()
        
        val workRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(constraints)
         .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
         .build()
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "heartbeat_work",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        
        // Restart
        val intent = Intent(this, HeartbeatService::class.java)
        startService(intent)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    class HeartbeatWorker(context: Context, params: WorkerParameters) : 
        Worker(context, params) {
        
        override fun doWork(): Result {
            return try {
                val intent = Intent(applicationContext, HeartbeatService::class.java)
                applicationContext.startService(intent)
                Result.success()
            } catch (e: Exception) {
                Result.retry()
            }
        }
    }
}
