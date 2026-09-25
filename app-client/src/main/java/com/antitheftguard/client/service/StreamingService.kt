package com.antitheftguard.client.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.antitheftguard.core.webrtc.PeerConnectionManager
import com.antitheftguard.core.webrtc.PeerConnectionListener
import org.webrtc.*
import kotlinx.coroutines.*

class StreamingService : Service(), PeerConnectionListener {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var peerConnectionManager: PeerConnectionManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(2, NotificationCompat.Builder(this, "stream_channel")
            .setContentTitle("스트리밍 중")
            .setContentText("마스터 기기로 영상을 전송 중입니다.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build())

        peerConnectionManager = PeerConnectionManager(this, this)
        peerConnectionManager.initialize()
        peerConnectionManager.createPeerConnection()
        
        // 3분(180,000ms) 타임아웃
        serviceScope.launch {
            delay(3 * 60 * 1000L)
            stopSelf()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        peerConnectionManager.close()
        serviceScope.cancel()
    }
    
    override fun onIceCandidateGenerated(candidate: IceCandidate) {}
    override fun onTrackReceived(track: MediaStreamTrack) {}
    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {}
    override fun onDataChannelMessage(message: String) {}
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel("stream_channel", "스트리밍 서비스", NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
