package com.antitheftguard.client.ui

import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antitheftguard.client.databinding.ActivityStreamBinding
import com.antitheftguard.core.webrtc.CameraHelper
import com.antitheftguard.core.webrtc.PeerConnectionListener
import com.antitheftguard.core.webrtc.PeerConnectionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.IceCandidate
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

/**
 * 원격 카메라 스트리밍 송출 전용 액티비티.
 * 잠금화면 위에서도 화면을 켜고 전면/후면 카메라를 480p로 WebRTC P2P 스트리밍합니다.
 */
class StreamActivity : AppCompatActivity(), PeerConnectionListener {
    companion object {
        private const val TAG = "StreamActivity"
        const val EXTRA_CAMERA = "extra_camera"
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }

    private lateinit var binding: ActivityStreamBinding
    private lateinit var peerConnectionManager: PeerConnectionManager
    private val db = FirebaseFirestore.getInstance()

    private var roomId: String = ""
    private var deviceId: String = ""
    private var cameraType: String = "back"
    private var answerListener: ListenerRegistration? = null
    private var candidateListener: ListenerRegistration? = null
    private var countDownTimer: CountDownTimer? = null
    private var isStreamingActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 화면 켜기 및 잠금 화면 위 표시 (Android 11+ 지원)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraType = intent.getStringExtra(EXTRA_CAMERA) ?: "back"
        roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: "room_${System.currentTimeMillis()}"
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: getSharedPreferences("antitheft", MODE_PRIVATE).getString("device_id", "") ?: ""

        val isFront = cameraType == "front"
        binding.tvCameraType.text = if (isFront) "🤳 전면 카메라 전송 중" else "📷 후면 카메라 전송 중"

        setupListeners()
        startCountDown()
        startWebRtcStreaming(isFront)
    }

    private fun setupListeners() {
        binding.btnStopStream.setOnClickListener {
            stopStreamingAndFinish()
        }
    }

    private fun startCountDown() {
        countDownTimer = object : CountDownTimer(180_000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSeconds = millisUntilFinished / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                binding.tvTimer.text = String.format("%02d:%02d", minutes, seconds)
            }

            override fun onFinish() {
                Toast.makeText(this@StreamActivity, "스트리밍 3분이 종료되었습니다.", Toast.LENGTH_SHORT).show()
                stopStreamingAndFinish()
            }
        }.start()
    }

    private fun startWebRtcStreaming(isFront: Boolean) {
        try {
            peerConnectionManager = PeerConnectionManager(this, this)
            peerConnectionManager.initialize()
            peerConnectionManager.createPeerConnection()

            // 비디오 캡처러 생성 및 트랙 추가
            val capturer = CameraHelper.createCameraCapturer(this, isFront)
            if (capturer != null) {
                val videoTrack = peerConnectionManager.createVideoTrack(capturer)
                if (videoTrack != null) {
                    peerConnectionManager.addTrack(videoTrack)
                    Log.d(TAG, "비디오 트랙 추가 완료")
                }
            } else {
                Log.e(TAG, "카메라를 초기화할 수 없습니다.")
            }

            // 오디오 트랙 추가
            val audioTrack = peerConnectionManager.createAudioTrack()
            if (audioTrack != null) {
                peerConnectionManager.addTrack(audioTrack)
                Log.d(TAG, "오디오 트랙 추가 완료")
            }

            isStreamingActive = true

            // SDP Offer 생성
            peerConnectionManager.createOffer { sdp ->
                Log.d(TAG, "Offer SDP 생성 성공")
                if (deviceId.isNotEmpty()) {
                    val roomRef = db.collection("devices").document(deviceId)
                        .collection("signaling").document(roomId)

                    roomRef.set(mapOf(
                        "offer" to sdp.description,
                        "camera" to cameraType,
                        "createdAt" to System.currentTimeMillis()
                    )).addOnSuccessListener {
                        Log.d(TAG, "Offer 등록 완료: $roomId")
                    }

                    // 호스트의 Answer 대기
                    listenForAnswer(roomRef)
                    // 호스트의 ICE Candidate 대기
                    listenForRemoteIceCandidates(roomRef)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC 시작 실패", e)
            Toast.makeText(this, "스트리밍 초기화 오류: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun listenForAnswer(roomRef: com.google.firebase.firestore.DocumentReference) {
        answerListener = roomRef.addSnapshotListener { snapshot, e ->
            if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

            val answerSdp = snapshot.getString("answer")
            if (!answerSdp.isNullOrEmpty()) {
                Log.d(TAG, "호스트로부터 Answer 수신 완료!")
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                peerConnectionManager.setRemoteDescription(sdp)
            }
        }
    }

    private fun listenForRemoteIceCandidates(roomRef: com.google.firebase.firestore.DocumentReference) {
        candidateListener = roomRef.collection("candidates").addSnapshotListener { snapshot, e ->
            if (e != null || snapshot == null) return@addSnapshotListener

            for (change in snapshot.documentChanges) {
                if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                    val data = change.document.data
                    val sender = data["sender"] as? String
                    // 호스트(웹)가 보낸 후보군만 수신
                    if (sender == "host") {
                        val sdp = data["candidate"] as? String ?: continue
                        val sdpMid = data["sdpMid"] as? String ?: ""
                        val sdpMLineIndex = (data["sdpMLineIndex"] as? Long)?.toInt() ?: 0
                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                        peerConnectionManager.addIceCandidate(candidate)
                    }
                }
            }
        }
    }

    override fun onIceCandidateGenerated(candidate: IceCandidate) {
        if (deviceId.isEmpty() || roomId.isEmpty()) return
        val candData = mapOf(
            "sender" to "client",
            "candidate" to candidate.sdp,
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "timestamp" to System.currentTimeMillis()
        )
        db.collection("devices").document(deviceId)
            .collection("signaling").document(roomId)
            .collection("candidates").add(candData)
    }

    override fun onTrackReceived(track: MediaStreamTrack) {}
    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
        Log.d(TAG, "PeerConnection 상태 변경: $state")
    }
    override fun onDataChannelMessage(message: String) {}

    private fun stopStreamingAndFinish() {
        if (!isStreamingActive) return
        isStreamingActive = false
        countDownTimer?.cancel()
        answerListener?.remove()
        candidateListener?.remove()
        try {
            peerConnectionManager.close()
        } catch (e: Exception) {
            Log.e(TAG, "close error", e)
        }
        finish()
    }

    override fun onDestroy() {
        stopStreamingAndFinish()
        super.onDestroy()
    }
}
