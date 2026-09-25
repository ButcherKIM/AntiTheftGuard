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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initDeviceId()
        setupListeners()
        updateBatteryInfo()
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updateBatteryInfo()
        updatePermissionStatus()
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
            Toast.makeText(this, "기기 ID가 '${newId}'(으)로 저장되었습니다.", Toast.LENGTH_SHORT).show()
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
    }

    private fun toggleGpsService() {
        val isServiceRunning = getSharedPreferences("antitheft", Context.MODE_PRIVATE)
            .getBoolean("is_service_running", false)

        val serviceIntent = Intent(this, GpsLoggingService::class.java)

        if (!isServiceRunning) {
            // 시작
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "먼저 위치 권한을 허용해 주세요!", Toast.LENGTH_SHORT).show()
                requestRequiredPermissions()
                return
            }

            ContextCompat.startForegroundService(this, serviceIntent)
            getSharedPreferences("antitheft", Context.MODE_PRIVATE)
                .edit().putBoolean("is_service_running", true).apply()

            binding.btnToggleService.text = "🔴 GPS 추적 서비스 중지"
            binding.btnToggleService.setBackgroundColor(0xFFEF4444.toInt())
            binding.tvDeviceStatus.text = "GPS 추적 상태: 동작 중 (실시간 기록)"
            binding.tvDeviceStatus.setTextColor(0xFF34D399.toInt())
            Toast.makeText(this, "도난 방지 추적이 시작되었습니다.", Toast.LENGTH_SHORT).show()
        } else {
            // 중지
            stopService(serviceIntent)
            getSharedPreferences("antitheft", Context.MODE_PRIVATE)
                .edit().putBoolean("is_service_running", false).apply()

            binding.btnToggleService.text = "🚨 GPS 도난 방지 추적 시작"
            binding.btnToggleService.setBackgroundColor(0xFF10B981.toInt())
            binding.tvDeviceStatus.text = "GPS 추적 상태: 중지됨"
            binding.tvDeviceStatus.setTextColor(0xFF94A3B8.toInt())
            Toast.makeText(this, "추적 서비스가 중지되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

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

        if (fineLocation && camera && mic) {
            binding.tvPermissionsStatus.text = "필수 권한: 모두 허용됨 ✅"
            binding.tvPermissionsStatus.setTextColor(0xFF34D399.toInt())
        } else {
            binding.tvPermissionsStatus.text = "필수 권한: 확인 및 승인 필요 ⚠️"
            binding.tvPermissionsStatus.setTextColor(0xFFF59E0B.toInt())
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
}
