package com.antitheftguard.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BatteryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                Log.d("BatteryReceiver", "전원 연결됨 - GPS 주기를 짧게 조정합니다.")
                // TODO: SmartIntervalCalculator에 상태 반영 및 GpsLoggingService 업데이트
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Log.d("BatteryReceiver", "전원 연결 해제됨 - GPS 주기를 기본으로 조정합니다.")
            }
        }
    }
}
