package com.antitheftguard.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 카메라 및 마이크 백그라운드 사용 권한을 안드로이드 OS(API 11~14+)에 보장하기 위한 전용 포그라운드 서비스.
 * StreamActivity가 실행되는 동안 시스템에 FOREGROUND_SERVICE_TYPE_CAMERA 및
 * FOREGROUND_SERVICE_TYPE_MICROPHONE 권한을 등록하여 OS에 의한 카메라 하드웨어 강제 연결 종료(3~5초 타임아웃)를 원천 차단합니다.
 */
class StreamingService : Service() {
    companion object {
        private const val TAG = "StreamingService"
        const val NOTIFICATION_ID = 2002
        const val CHANNEL_ID = "stealth_stream_channel_v3"
        const val EXTRA_ROOM_ID = "extra_room_id"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithType()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithType()
        return START_NOT_STICKY
    }

    private fun startForegroundWithType() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("시스템 보안 모니터링")
            .setContentText("실시간 보안 스트리밍 세션이 활성화되었습니다.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "시스템 동기화",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "백그라운드 보안 연결 유지"
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
