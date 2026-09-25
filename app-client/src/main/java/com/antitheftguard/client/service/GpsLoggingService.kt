package com.antitheftguard.client.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.antitheftguard.client.db.AppDatabase
import com.antitheftguard.client.db.GpsPointEntity
import com.antitheftguard.client.location.GpsBatcher
import com.antitheftguard.client.location.SmartIntervalCalculator
import com.antitheftguard.core.firebase.FirestoreManager
import com.antitheftguard.core.model.GpsPoint
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class GpsLoggingService : Service() {
    companion object {
        private const val TAG = "GpsLoggingService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "gps_tracking"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var gpsBatcher: GpsBatcher
    private val firestoreManager = FirestoreManager()
    private var isCharging = false
    private var currentSpeed = 0f
    private var deviceId: String = ""

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val wasCharging = isCharging
            isCharging = when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> isCharging
            }
            if (wasCharging != isCharging) {
                updateLocationInterval()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        deviceId = getSharedPreferences("antitheft", MODE_PRIVATE)
            .getString("device_id", "") ?: ""
        
        // 초기 충전 상태 확인
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.let { it != 0 } ?: false
        
        gpsBatcher = GpsBatcher(deviceId)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupLocationCallback()
        startLocationUpdates()
        collectBatches()
        registerBatteryReceiver()
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    currentSpeed = location.speed
                    val batteryLevel = getBatteryLevel()
                    val point = GpsPoint(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        speed = location.speed,
                        timestamp = location.time,
                        batteryLevel = batteryLevel
                    )
                    gpsBatcher.addPoint(point)
                    
                    // Room에도 개별 저장 (오프라인 캐시)
                    serviceScope.launch {
                        val entity = GpsPointEntity(
                            lat = point.latitude,
                            lng = point.longitude,
                            accuracy = point.accuracy,
                            speed = point.speed,
                            timestamp = point.timestamp,
                            batteryLevel = point.batteryLevel
                        )
                        AppDatabase.getInstance(this@GpsLoggingService).gpsPointDao().insert(entity)
                    }
                }
                // 속도 변화에 따른 인터벌 조정
                updateLocationInterval()
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "위치 권한이 없습니다")
            stopSelf()
            return
        }
        
        val interval = SmartIntervalCalculator.calculate(currentSpeed, isCharging)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval / 2)
            .setMaxUpdateDelayMillis(interval * 2)
            .build()
        
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun updateLocationInterval() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        startLocationUpdates()
    }

    private fun collectBatches() {
        serviceScope.launch {
            gpsBatcher.batchFlow.collectLatest { batch ->
                // Firestore에 배치 업로드
                firestoreManager.saveGpsBatch(deviceId, batch)
                    .onSuccess {
                        Log.d(TAG, "배치 업로드 성공: ${batch.points.size}개 포인트")
                        // 동기화된 포인트 마킹은 Room DAO에서 처리
                    }
                    .onFailure { e ->
                        Log.e(TAG, "배치 업로드 실패, 오프라인 캐시에 보관", e)
                    }
            }
        }
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("AntiTheft Guard")
        .setContentText("위치 정보를 안전하게 추적 중입니다.")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "GPS 추적", NotificationManager.IMPORTANCE_LOW)
        channel.description = "도난 방지를 위한 GPS 위치 추적"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        unregisterReceiver(batteryReceiver)
        serviceScope.cancel()
    }
}
