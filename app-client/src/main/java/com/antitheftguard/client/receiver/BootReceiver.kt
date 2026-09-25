package com.antitheftguard.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antitheftguard.client.service.GpsLoggingService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "디바이스 부팅 완료, GpsLoggingService를 시작합니다.")
            val serviceIntent = Intent(context, GpsLoggingService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
