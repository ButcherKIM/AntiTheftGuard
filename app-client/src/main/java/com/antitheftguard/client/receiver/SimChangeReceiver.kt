package com.antitheftguard.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class SimChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.intent.action.SIM_STATE_CHANGED") {
            Log.d("SimChangeReceiver", "SIM 상태 변경 감지됨")
            // TODO: Firestore에 경고 데이터 업로드
        }
    }
}
