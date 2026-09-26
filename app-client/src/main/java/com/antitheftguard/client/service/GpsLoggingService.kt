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
import android.app.Notification
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
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
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var gpsBatcher: GpsBatcher
    private val firestoreManager = FirestoreManager()
    private var isCharging = false
    private var currentSpeed = 0f
    private var currentConfig = SmartIntervalCalculator.getConfig(0f, false)
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
                val newConfig = SmartIntervalCalculator.getConfig(currentSpeed, isCharging)
                if (newConfig != currentConfig) {
                    currentConfig = newConfig
                    updateLocationInterval()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            androidx.core.app.ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        deviceId = getSharedPreferences("antitheft", MODE_PRIVATE)
            .getString("device_id", "") ?: ""
        if (deviceId.isEmpty()) {
            val sanitizedModel = Build.MODEL.replace("[^a-zA-Z0-9_-]".toRegex(), "_").lowercase()
            deviceId = "phone_${sanitizedModel}"
        }
        
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
                // 속도 및 이동 상태 변화에 따른 인터벌/우선순위/거리 필터 조정
                val newConfig = SmartIntervalCalculator.getConfig(currentSpeed, isCharging)
                if (newConfig != currentConfig) {
                    Log.d(TAG, "위치 수집 모드 전환: $currentConfig -> $newConfig")
                    currentConfig = newConfig
                    updateLocationInterval()
                }
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
        
        currentConfig = SmartIntervalCalculator.getConfig(currentSpeed, isCharging)
        val request = LocationRequest.Builder(currentConfig.priority, currentConfig.interval)
            .setMinUpdateIntervalMillis(currentConfig.interval / 2)
            .setMaxUpdateDelayMillis(currentConfig.interval * 2)
            .setMinUpdateDistanceMeters(currentConfig.minUpdateDistanceMeters)
            .build()
        
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        Log.d(TAG, "위치 수집 등록: interval=${currentConfig.interval}ms, priority=${currentConfig.priority}, minDistance=${currentConfig.minUpdateDistanceMeters}m")
    }

    private fun updateLocationInterval() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        startLocationUpdates()
    }

    private var commandListener: ListenerRegistration? = null
    private var lastHandledRoomId: String = ""

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
        if (deviceId.isEmpty()) {
            val sanitizedModel = Build.MODEL.replace("[^a-zA-Z0-9_-]".toRegex(), "_").lowercase()
            deviceId = "phone_${sanitizedModel}"
        }
        val db = FirebaseFirestore.getInstance()
        var isInitialSnapshot = true

        commandListener = db.collection("devices").document(deviceId)
            .collection("commands").document("stream")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                val command = snapshot.getString("command")
                val timestamp = snapshot.getLong("timestamp") ?: 0L
                val camera = snapshot.getString("camera") ?: "back"
                val roomId = snapshot.getString("roomId") ?: ""

                val now = System.currentTimeMillis()

                if (isInitialSnapshot) {
                    isInitialSnapshot = false
                    lastHandledRoomId = roomId
                    // 만약 서비스 시작 직전 60초 이내에 새로 요청된 명령이라면 즉시 처리
                    if (command == "START_STREAM" && Math.abs(now - timestamp) < 60_000L && roomId.isNotEmpty()) {
                        Log.d(TAG, "초기 스냅샷에서 최근 명령 감지 -> 실행: $roomId")
                        triggerStreamActivity(camera, roomId)
                    }
                    return@addSnapshotListener
                }

                // 새로운 스트리밍 요청 감지
                if (command == "START_STREAM" && roomId.isNotEmpty() && roomId != lastHandledRoomId) {
                    lastHandledRoomId = roomId
                    Log.d(TAG, "원격 스트리밍 명령 수신! (카메라: $camera, 방: $roomId)")
                    triggerStreamActivity(camera, roomId)
                }
            }
    }

    private fun triggerStreamActivity(camera: String, roomId: String) {
        // 호스트 웹 대시보드에 기기 수신 응답 피드백
        val db = FirebaseFirestore.getInstance()
        if (deviceId.isNotEmpty()) {
            db.collection("devices").document(deviceId)
                .collection("commands").document("stream")
                .update("status", "DEVICE_RECEIVED", "receivedAt", System.currentTimeMillis())
        }

        val streamIntent = Intent(this, StreamActivity::class.java).apply {
            putExtra(StreamActivity.EXTRA_CAMERA, camera)
            putExtra(StreamActivity.EXTRA_ROOM_ID, roomId)
            putExtra(StreamActivity.EXTRA_DEVICE_ID, deviceId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            roomId.hashCode(),
            streamIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Android 10+ 백그라운드 액티비티 기동 보장을 위한 무음 고우선순위 풀스크린 알림
        val channelId = "stealth_stream_channel_v3"
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "시스템 동기화", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "백그라운드 보안 연결 유지"
                enableVibration(false)
                vibrationPattern = longArrayOf(0)
                setSound(null, null)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("시스템 보안 동기화")
            .setContentText("기기 보안 채널이 활성화되었습니다.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSilent(true)
            .build()

        notificationManager.notify(2001, notification)

        // CPU 및 화면 활성 유지를 위한 WakeLock 획득
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "AntiTheft:StreamWakeLock"
            )
            wakeLock.acquire(10_000L)
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock 획득 실패", e)
        }

        // 액티비티 직접 기동 시도 (오버레이 권한 허용 기기 즉시 기동)
        try {
            startActivity(streamIntent)
        } catch (e: Exception) {
            Log.e(TAG, "startActivity 직접 호출 실패 (풀스크린 인텐트 대기)", e)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val currentId = getSharedPreferences("antitheft", MODE_PRIVATE)
            .getString("device_id", "") ?: ""
        if (currentId.isNotEmpty() && currentId != deviceId) {
            Log.d(TAG, "기기 ID 동적 변경 감지: $deviceId -> $currentId")
            deviceId = currentId
            gpsBatcher = GpsBatcher(deviceId)
            commandListener?.remove()
            listenForRemoteCommands()
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        commandListener?.remove()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        unregisterReceiver(batteryReceiver)
        serviceScope.cancel()
    }
}
