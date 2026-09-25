package com.antitheftguard.client.fcm

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.antitheftguard.client.service.StreamingService
import android.util.Log

class TriggerReceiver : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        
        val command = message.data["command"]
        if (command == "START_STREAM") {
            Log.d("TriggerReceiver", "START_STREAM 명령 수신. 스트리밍 서비스를 시작합니다.")
            val intent = Intent(this, StreamingService::class.java)
            startForegroundService(intent)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // TODO: FirestoreManager를 통해 새로운 FCM 토큰 업데이트
    }
}
