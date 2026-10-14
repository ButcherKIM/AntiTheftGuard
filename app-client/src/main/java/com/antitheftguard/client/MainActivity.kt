package com.antitheftguard.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.*
import com.antitheftguard.client.databinding.ActivityMainBinding
import com.antitheftguard.client.service.GpsLoggingService
import com.antitheftguard.client.util.AutoStartHelper
import com.antitheftguard.client.worker.DailyRollupWorker
import com.antitheftguard.core.firebase.FirestoreManager
import com.antitheftguard.core.model.DeviceInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 클라이언트 앱의 메인 액티비티입니다 (미니멀 스텔스 UI 적용).
 * 순백색 화면에 중앙 토글 원, 은폐형 상단 버튼, 하단 GPS 발신 시각만을 제공합니다.
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
            checkBackgroundLocationPermission()
            checkOverlayPermission()
        }
    }

    private var activityCommandListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var activityLastHandledRoomId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initDeviceId()
        setupListeners()
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
        updateLastGpsSentUi(0L)

        // 이전에 추적을 켜두었는데 서비스가 종료되었던 상태라면 재개
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
            binding.tvLastGpsSent.text = timeFormat.format(Date(effectiveTime))
        } else {
            binding.tvLastGpsSent.text = ""
        }
    }

    private fun updateServiceStatusUi() {
        val shouldRun = getSharedPreferences("antitheft", Context.MODE_PRIVATE)
            .getBoolean("is_service_running", false)
        val isRunning = GpsLoggingService.isRunning || shouldRun

        if (isRunning) {
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(0xFF10B981.toInt()) // Green
        } else {
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(0xFFEF4444.toInt()) // Red
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
        // 기기 ID 저장 버튼 (소리 없는 저장 및 리플)
        binding.btnSaveDeviceId.setOnClickListener {
            val newId = binding.etDeviceId.text.toString().trim()
            if (newId.isNotEmpty()) {
                getSharedPreferences("antitheft", Context.MODE_PRIVATE)
                    .edit().putString("device_id", newId).apply()
                registerDeviceToFirestore(newId)

                // 서비스가 실행 중이라면 새 ID로 즉시 갱신
                if (GpsLoggingService.isRunning) {
                    val serviceIntent = Intent(this, GpsLoggingService::class.java)
                    stopService(serviceIntent)
                    ContextCompat.startForegroundService(this, serviceIntent)
                }
            }
        }

        // 중앙 상태 원 터치 시: 추적 시작 / 중지 토글 (초록 <-> 빨강)
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
            val currentInputId = binding.etDeviceId.text.toString().trim()
            if (currentInputId.isNotEmpty()) {
                prefs.edit().putString("device_id", currentInputId).apply()
                registerDeviceToFirestore(currentInputId)
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                requestRequiredPermissions()
                return
            }

            prefs.edit().putBoolean("is_service_running", true).apply()
            GpsLoggingService.isRunning = true

            serviceIntent.action = GpsLoggingService.ACTION_SEND_IMMEDIATE_GPS
            ContextCompat.startForegroundService(this, serviceIntent)
            updateServiceStatusUi()
        } else {
            stopService(serviceIntent)
            prefs.edit().putBoolean("is_service_running", false).apply()
            GpsLoggingService.isRunning = false
            updateServiceStatusUi()
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
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (_: Exception) {
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
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (e2: Exception) {
                    AutoStartHelper.getAutoStartIntent(this)?.let { startActivity(it) }
                }
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

        // 6시간 주기 정기 워커 등록 (하루 4회 롤업, 네트워크 연결 시 동작)
        val periodicWork = PeriodicWorkRequestBuilder<DailyRollupWorker>(
            6, TimeUnit.HOURS
        ).setConstraints(constraints).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "DailyRollupPeriodicWork",
            ExistingPeriodicWorkPolicy.UPDATE,
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
