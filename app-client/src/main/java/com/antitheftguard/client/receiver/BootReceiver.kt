package com.antitheftguard.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.antitheftguard.client.service.GpsLoggingService

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "시스템 이벤트 수신: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_USER_PRESENT) {
            val prefs = context.getSharedPreferences("antitheft", Context.MODE_PRIVATE)
            val shouldRun = prefs.getBoolean("is_service_running", false)
            if (!shouldRun) {
                Log.d(TAG, "서비스 실행 설정이 꺼져 있음. 자동 시작 생략.")
                return
            }

            if (GpsLoggingService.isRunning) {
                Log.d(TAG, "GpsLoggingService가 이미 실행 중임.")
                return
            }

            try {
                Log.d(TAG, "이전 추적 상태 복원을 위해 GpsLoggingService 시작 요청 ($action)")
                val serviceIntent = Intent(context, GpsLoggingService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                Log.w(TAG, "FGS 백그라운드 시작 제한 발생 ($action): ${e.message}")
            }
        }
    }
}
