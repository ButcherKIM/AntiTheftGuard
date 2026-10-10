package com.antitheftguard.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 카메라 및 마이크 백그라운드 사용 권한을 안드로이드 OS(API 11~14+)에 보장하기 위한 전용 포그라운드 서비스.
 * StreamActivity가 실행되는 동안 시스템에 FOREGROUND_SERVICE_TYPE_CAMERA 및
 * FOREGROUND_SERVICE_TYPE_MICROPHONE 권한을 등록하고 CPU WakeLock을 유지하여
 * 30초 절전 타임아웃 및 OS에 의한 카메라 강제 연결 해제를 원천 차단합니다.
 */
class StreamingService : Service() {
    companion object {
        private const val TAG = "StreamingService"
        const val NOTIFICATION_ID = 2002
        const val CHANNEL_ID = GpsLoggingService.CHANNEL_ID
        const val EXTRA_ROOM_ID = "extra_room_id"
    }

    private var cpuWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        createNotificationChannel()
        startForegroundWithType()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            cpuWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AntiTheft:StreamingCpuWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(185_000L) // 3분 스트리밍 + 5초 여유
            }
            Log.d(TAG, "StreamingService CPU WakeLock 획득 완료 (185초)")
        } catch (e: Exception) {
            Log.e(TAG, "cpuWakeLock 획득 실패", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock()
        startForegroundWithType()
        return START_NOT_STICKY
    }

    private fun startForegroundWithType() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("시스템 보안 보호")
            .setContentText("백그라운드 보안 연결 유지 중")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOngoing(true)
            .setSilent(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "StreamingService 포그라운드(카메라/마이크) 등록 완료")
        } catch (e: Exception) {
            Log.e(TAG, "StreamingService startForeground 실패", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "StreamingService 종료")
        try {
            cpuWakeLock?.let {
                if (it.isHeld) it.release()
            }
            cpuWakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "cpuWakeLock 해제 오류", e)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground error", e)
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            try {
                // 이전 구버전 스트리밍 채널들 영구 삭제 정리
                nm.deleteNotificationChannel("stealth_stream_channel_v3")
                nm.deleteNotificationChannel("stealth_stream_channel_v2")
            } catch (_: Exception) {}
        }
    }
}
