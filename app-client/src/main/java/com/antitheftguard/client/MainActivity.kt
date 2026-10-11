package com.antitheftguard.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antitheftguard.client.databinding.ActivityMainBinding
import com.antitheftguard.client.service.GpsLoggingService
import com.antitheftguard.client.util.AutoStartHelper
import com.antitheftguard.core.firebase.FirestoreManager
import com.antitheftguard.core.model.DeviceInfo
import com.google.android.material.button.MaterialButton
import android.app.NotificationManager
import android.content.res.ColorStateList
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.*
import com.antitheftguard.client.worker.DailyRollupWorker
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 클라이언트 앱의 메인 액티비티입니다.
 * 기기 ID 설정, 실시간 백그라운드 추적 시작/중지, 배터리 최적화 예외, 권한 관리를 제공합니다.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val firestoreManager = FirestoreManager()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "모든 권한이 허용되었습니다.", Toast.LENGTH_SHORT).show()
            checkBackgroundLocationPermission()
        } else {
            Toast.makeText(this, "일부 권한이 거부되었습니다. 원활한 동작을 위해 권한을 허용해 주세요.", Toast.LENGTH_LONG).show()
        }
        updatePermissionStatus()
    }

    private var activityCommandListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var activityLastHandledRoomId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initDeviceId()
        setupListeners()
        updateBatteryInfo()
        updatePermissionStatus()
        updateServiceStatusUi()
        updateLastGpsSentUi(0L)
        observeServiceStatus()
        setupWorkers()
    }

    override fun onStart() {
        super.onStart()
        listenForCommandsInForeground()
    }

    override fun onResume() {
        super.onResume()
        updateBatteryInfo()
        updatePermissionStatus()
        updateLastGpsSentUi(0L)
        
        // 이전에 추적을 켜두었는데 서비스가 종료되었던 상태라면 자동 재개
        val shouldRun = getSharedPreferences("antitheft", Context.MODE_PRIVATE)
            .getBoolean("is_service_running", false)
        if (shouldRun && !GpsLoggingService.isRunning) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                ContextCompat.startForegroundService(this, Intent(this, GpsLoggingService::class.java))
            }
        } else if (GpsLoggingService.isRunning) {
            com.antitheftguard.client.motion.ActivityTransitionManager.startTracking(this)
        }
        updateServiceStatusUi()
    }

    override fun onStop() {
        super.onStop()
        activityCommandListener?.remove()
        activityCommandListener = null
    }

    private fun observeServiceStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    GpsLoggingService.isRunningFlow.collect {
                        updateServiceStatusUi()
                    }
                }
                launch {
                    GpsLoggingService.lastGpsSentTimeFlow.collect { time ->
                        updateLastGpsSentUi(time)
                    }
                }
            }
        }
    }

    private fun updateLastGpsSentUi(timestamp: Long) {
        val effectiveTime = if (timestamp > 0) {
            timestamp
        } else {
            getSharedPreferences("antitheft", Context.MODE_PRIVATE)
                .getLong(GpsLoggingService.PREFS_KEY_LAST_GPS_SENT, 0L)
        }

        if (effectiveTime > 0) {
            val formatted = timeFormat.format(Date(effectiveTime))
            binding.tvLastGpsSent.text = "마지막 GPS 발신: $formatted"
            binding.tvLastGpsSent.setTextColor(0xFF38BDF8.toInt())
        } else {
            binding.tvLastGpsSent.text = "마지막 GPS 발신: 기록 없음 (미발신)"
            binding.tvLastGpsSent.setTextColor(0xFF94A3B8.toInt())
        }
    }

    private fun updateServiceStatusUi() {
        val shouldRun = getSharedPreferences("antitheft", Context.MODE_PRIVATE)
            .getBoolean("is_service_running", false)
        val isRunning = GpsLoggingService.isRunning || shouldRun

        if (isRunning) {
            binding.btnToggleService.text = "🔴 GPS 추적 서비스 중지"
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(0xFFEF4444.toInt())
            binding.tvDeviceStatus.text = "GPS 추적 상태: 동작 중 (실시간 기록 & 원격 카메라 대기)"
            binding.tvDeviceStatus.setTextColor(0xFF34D399.toInt())
        } else {
            binding.btnToggleService.text = "🚨 GPS 도난 방지 추적 시작"
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(0xFF10B981.toInt())
            binding.tvDeviceStatus.text = "GPS 추적 상태: 중지됨 (버튼을 눌러 시작하세요)"
            binding.tvDeviceStatus.setTextColor(0xFF94A3B8.toInt())
        }
    }

    private fun listenForCommandsInForeground() {
        val prefs = getSharedPreferences("antitheft", Context.MODE_PRIVATE)
        val devId = prefs.getString("device_id", "") ?: return
        if (devId.isEmpty()) return

        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        var isInitial = true
        activityCommandListener = db.collection("devices").document(devId)
            .collection("commands").document("stream")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                val command = snapshot.getString("command")
                val roomId = snapshot.getString("roomId") ?: ""
                val camera = snapshot.getString("camera") ?: "back"
                val timestamp = snapshot.getLong("timestamp") ?: 0L

                val now = System.currentTimeMillis()
                if (isInitial) {
                    isInitial = false
                    activityLastHandledRoomId = roomId
                    if (command == "START_STREAM" && Math.abs(now - timestamp) < 30_000L && roomId.isNotEmpty()) {
                        launchStreamDirectly(camera, roomId, devId)
                    }
                    return@addSnapshotListener
                }

                if (command == "START_STREAM" && roomId.isNotEmpty() && roomId != activityLastHandledRoomId) {
                    activityLastHandledRoomId = roomId
                    launchStreamDirectly(camera, roomId, devId)
                }
            }
    }

    private fun launchStreamDirectly(camera: String, roomId: String, devId: String) {
        val streamIntent = Intent(this, com.antitheftguard.client.ui.StreamActivity::class.java).apply {
            putExtra(com.antitheftguard.client.ui.StreamActivity.EXTRA_CAMERA, camera)
            putExtra(com.antitheftguard.client.ui.StreamActivity.EXTRA_ROOM_ID, roomId)
            putExtra(com.antitheftguard.client.ui.StreamActivity.EXTRA_DEVICE_ID, devId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(streamIntent)
    }

    private fun initDeviceId() {
        val prefs = getSharedPreferences("antitheft", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", "") ?: ""

        if (deviceId.isEmpty()) {
            val sanitizedModel = Build.MODEL.replace("[^a-zA-Z0-9_-]".toRegex(), "_").lowercase()
            deviceId = "phone_${sanitizedModel}"
            prefs.edit().putString("device_id", deviceId).apply()
        }

        binding.etDeviceId.setText(deviceId)
        registerDeviceToFirestore(deviceId)
    }

    private fun setupListeners() {
        // 기기 ID 저장 버튼
        binding.btnSaveDeviceId.setOnClickListener {
            val newId = binding.etDeviceId.text.toString().trim()
            if (newId.isEmpty()) {
                Toast.makeText(this, "기기 ID를 입력해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            getSharedPreferences("antitheft", Context.MODE_PRIVATE)
                .edit().putString("device_id", newId).apply()
            registerDeviceToFirestore(newId)

            // 만약 서비스가 실행 중이라면 새 기기 ID로 즉시 갱신/재시작
            if (GpsLoggingService.isRunning) {
                val serviceIntent = Intent(this, GpsLoggingService::class.java)
                stopService(serviceIntent)
                ContextCompat.startForegroundService(this, serviceIntent)
            }
            Toast.makeText(this, "기기 ID가 '${newId}'(으)로 저장 및 동기화되었습니다.", Toast.LENGTH_SHORT).show()
        }

        // GPS 도난 방지 추적 시작 / 중지 버튼
        binding.btnToggleService.setOnClickListener {
            toggleGpsService()
        }

        // 필수 권한 요청 버튼
        binding.btnRequestPermissions.setOnClickListener {
            requestRequiredPermissions()
        }

        // 배터리 최적화 예외 등록 버튼
        binding.btnBatteryOptimization.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        // 다른 앱 위에 표시 (원격 카메라 오버레이) 권한
        binding.btnOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "이미 원격 카메라(다른 앱 위에 표시) 권한이 허용되어 있습니다! 👍", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "권한이 이미 허용되어 있습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // 상단 지속 알림 끄기 설정 바로가기
        binding.btnHideNotification.setOnClickListener {
            openNotificationSettings()
        }
    }

    private fun toggleGpsService() {
        val prefs = getSharedPreferences("antitheft", Context.MODE_PRIVATE)
        val shouldRun = prefs.getBoolean("is_service_running", false)
        val isRunning = GpsLoggingService.isRunning || shouldRun
        val serviceIntent = Intent(this, GpsLoggingService::class.java)

        if (!isRunning) {
            // 시작 전에 현재 입력창의 기기 ID를 먼저 저장
            val currentInputId = binding.etDeviceId.text.toString().trim()
            if (currentInputId.isNotEmpty()) {
                prefs.edit().putString("device_id", currentInputId).apply()
                registerDeviceToFirestore(currentInputId)
            }
            // 시작
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "먼저 위치 권한을 허용해 주세요!", Toast.LENGTH_SHORT).show()
                requestRequiredPermissions()
                return
            }

            prefs.edit().putBoolean("is_service_running", true).apply()
            GpsLoggingService.isRunning = true
            binding.tvLastGpsSent.text = "마지막 GPS 발신: 즉시 발신 요청 중... 📡"
            binding.tvLastGpsSent.setTextColor(0xFF38BDF8.toInt())

            serviceIntent.action = GpsLoggingService.ACTION_SEND_IMMEDIATE_GPS
            ContextCompat.startForegroundService(this, serviceIntent)
            updateServiceStatusUi()
            Toast.makeText(this, "도난 방지 추적이 시작되었습니다 (즉시 위치 발신).", Toast.LENGTH_SHORT).show()
        } else {
            // 중지
            stopService(serviceIntent)
            prefs.edit().putBoolean("is_service_running", false).apply()
            GpsLoggingService.isRunning = false
            updateServiceStatusUi()
            Toast.makeText(this, "추적 서비스가 중지되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, GpsLoggingService.CHANNEL_ID)
                }
                startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            Toast.makeText(this, "'백그라운드 보안 서비스' 알림을 끄시면 상단바에서 완전히 사라집니다.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "안정적인 백그라운드 추적을 위해 위치 권한을 '항상 허용'으로 설정해 주세요.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                // 직접 다이얼로그 띄우기 (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // 특정 제조사 또는 권한 문제 시 일반 설정 화면으로 이동
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (e2: Exception) {
                    AutoStartHelper.getAutoStartIntent(this)?.let { startActivity(it) }
                }
            }
        } else {
            Toast.makeText(this, "이미 배터리 최적화 예외로 등록되어 있습니다! 👍", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBatteryInfo() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        binding.tvBatteryStatus.text = "배터리 잔량: ${batteryLevel}%"
    }

    private fun updatePermissionStatus() {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val activityRecognition = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else true
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        if (fineLocation && camera && mic && notif && overlay && activityRecognition) {
            binding.tvPermissionsStatus.text = "모든 필수 권한: 완벽하게 허용됨 ✅"
            binding.tvPermissionsStatus.setTextColor(0xFF34D399.toInt())
        } else {
            val missing = mutableListOf<String>()
            if (!fineLocation) missing.add("위치")
            if (!activityRecognition) missing.add("신체 활동 감지")
            if (!camera) missing.add("카메라")
            if (!mic) missing.add("마이크")
            if (!notif) missing.add("알림")
            if (!overlay) missing.add("다른 앱 위에 표시")

            binding.tvPermissionsStatus.text = "권한 필요 (${missing.joinToString(", ")}) ⚠️"
            binding.tvPermissionsStatus.setTextColor(0xFFF59E0B.toInt())
        }

        // Overlay 버튼 상태 시각화
        if (overlay) {
            binding.btnOverlayPermission.text = "✅ 다른 앱 위에 표시 권한 허용됨"
            binding.btnOverlayPermission.setTextColor(0xFF34D399.toInt())
            (binding.btnOverlayPermission as? MaterialButton)?.strokeColor = ColorStateList.valueOf(0xFF34D399.toInt())
        } else {
            binding.btnOverlayPermission.text = "⚠️ 다른 앱 위에 표시 허용하기 (원격 카메라 필수)"
            binding.btnOverlayPermission.setTextColor(0xFFF59E0B.toInt())
            (binding.btnOverlayPermission as? MaterialButton)?.strokeColor = ColorStateList.valueOf(0xFFF59E0B.toInt())
        }

        // 상단 지속 알림 채널 차단 여부 체크 (스텔스 모드 상태 표시)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            try {
                nm.deleteNotificationChannel("stealth_stream_channel_v3")
                nm.deleteNotificationChannel("stealth_stream_channel_v2")
                nm.deleteNotificationChannel("gps_tracking")
            } catch (_: Exception) {}

            val channel = nm.getNotificationChannel(GpsLoggingService.CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                binding.btnHideNotification.text = "✅ 상단 지속 알림 꺼짐 (스텔스 모드 활성)"
                binding.btnHideNotification.setTextColor(0xFF34D399.toInt())
                (binding.btnHideNotification as? MaterialButton)?.strokeColor = ColorStateList.valueOf(0xFF34D399.toInt())
            } else {
                binding.btnHideNotification.text = "🔕 상단 지속 알림 끄기 (스텔스 모드 설정)"
                binding.btnHideNotification.setTextColor(0xFFF8FAFC.toInt())
                (binding.btnHideNotification as? MaterialButton)?.strokeColor = ColorStateList.valueOf(0xFF64748B.toInt())
            }
        }
    }

    private fun registerDeviceToFirestore(deviceId: String) {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val device = DeviceInfo(
            deviceId = deviceId,
            ownerId = "web_master",
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            fcmToken = "",
            lastSeen = System.currentTimeMillis(),
            batteryLevel = battery
        )

        scope.launch(Dispatchers.IO) {
            firestoreManager.registerDevice(device)
        }
    }

    private fun setupWorkers() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 12시간 주기 정기 워커 등록 (네트워크 연결 시 동작)
        val periodicWork = PeriodicWorkRequestBuilder<DailyRollupWorker>(
            12, TimeUnit.HOURS
        ).setConstraints(constraints).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "DailyRollupPeriodicWork",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )

        // 앱 실행 시 즉시 미완료된 최근 일별 데이터(어제 등) 갈무리 1회 수행
        val oneTimeWork = OneTimeWorkRequestBuilder<DailyRollupWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "ImmediateDailyRollupWork",
            ExistingWorkPolicy.REPLACE,
            oneTimeWork
        )
    }
}
