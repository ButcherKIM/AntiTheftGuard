package com.antitheftguard.master.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.antitheftguard.master.databinding.ActivityStreamViewerBinding
import com.antitheftguard.core.webrtc.PeerConnectionManager
import com.antitheftguard.core.webrtc.PeerConnectionListener
import org.webrtc.*

class StreamViewerActivity : AppCompatActivity(), PeerConnectionListener {
    private lateinit var binding: ActivityStreamViewerBinding
    private lateinit var peerConnectionManager: PeerConnectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerConnectionManager = PeerConnectionManager(this, this)
        peerConnectionManager.initialize()
        
        binding.btnStopStream.setOnClickListener {
            finish()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        peerConnectionManager.close()
    }

    override fun onIceCandidateGenerated(candidate: IceCandidate) {}
    override fun onTrackReceived(track: MediaStreamTrack) {
        // TODO: VideoTrack 수신 시 SurfaceViewRenderer에 렌더링 연결
    }
    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
        runOnUiThread { binding.tvStreamStatus.text = "연결 상태: $state" }
    }
    override fun onDataChannelMessage(message: String) {}
}
