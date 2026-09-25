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
import android.app.PendingIntent
import android.os.Build
import com.antitheftguard.client.ui.StreamActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
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
        listenForRemoteCommands()
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

    private var commandListener: ListenerRegistration? = null
    private var lastHandledTimestamp: Long = 0L

    private fun collectBatches() {
        serviceScope.launch {
            gpsBatcher.batchFlow.collectLatest { batch ->
                // Firestore에 배치 업로드
                firestoreManager.saveGpsBatch(deviceId, batch)
                    .onSuccess {
                        Log.d(TAG, "배치 업로드 성공: ${batch.points.size}개 포인트")
                        // 기기 메타데이터 doc의 lastSeen 및 batteryLevel 도 함께 업데이트
                        val lastPoint = batch.points.lastOrNull()
                        val currentBattery = lastPoint?.batteryLevel ?: getBatteryLevel()
                        val lastTime = lastPoint?.timestamp ?: batch.endTime
                        firestoreManager.updateDeviceStatus(deviceId, lastTime, currentBattery)
                    }
                    .onFailure { e ->
                        Log.e(TAG, "배치 업로드 실패, 오프라인 캐시에 보관", e)
                    }
            }
        }
    }

    private fun listenForRemoteCommands() {
        if (deviceId.isEmpty()) return
        val db = FirebaseFirestore.getInstance()
        commandListener = db.collection("devices").document(deviceId)
            .collection("commands").document("stream")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                val command = snapshot.getString("command")
                val timestamp = snapshot.getLong("timestamp") ?: 0L
                val camera = snapshot.getString("camera") ?: "back"
                val roomId = snapshot.getString("roomId") ?: "room_${System.currentTimeMillis()}"

                // "START_STREAM" 명령이고, 최근 60초 이내에 발행되었으며, 이전에 처리한 적 없는 새로운 명령인지 확인
                val now = System.currentTimeMillis()
                if (command == "START_STREAM" && timestamp > lastHandledTimestamp && (now - timestamp) < 60_000L) {
                    lastHandledTimestamp = timestamp
                    Log.d(TAG, "원격 스트리밍 명령 수신! (카메라: $camera, 방: $roomId)")
                    triggerStreamActivity(camera, roomId)
                }
            }
    }

    private fun triggerStreamActivity(camera: String, roomId: String) {
        val streamIntent = Intent(this, StreamActivity::class.java).apply {
            putExtra(StreamActivity.EXTRA_CAMERA, camera)
            putExtra(StreamActivity.EXTRA_ROOM_ID, roomId)
            putExtra(StreamActivity.EXTRA_DEVICE_ID, deviceId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            streamIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 고우선순위 헤드업 알림 발송 (잠금화면 깨우기 FullScreenIntent 포함)
        val channelId = "remote_stream_alert"
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "원격 스트리밍 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "원격 카메라 스트리밍 요청 알림"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("🚨 원격 카메라 스트리밍 시작")
            .setContentText("호스트 웹 요청에 따라 ${if (camera == "front") "전면" else "후면"} 카메라 전송을 시작합니다.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2001, notification)

        // 액티비티 직접 기동 시도
        try {
            startActivity(streamIntent)
        } catch (e: Exception) {
            Log.e(TAG, "startActivity 직접 호출 실패, 헤드업 알림으로 사용자 진입 대기", e)
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
        commandListener?.remove()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        unregisterReceiver(batteryReceiver)
        serviceScope.cancel()
    }
}
