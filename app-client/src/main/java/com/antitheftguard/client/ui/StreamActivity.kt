package com.antitheftguard.client.ui

import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.antitheftguard.client.R
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
 * 알림화면이나 팝업 UI 없이 투명 모드로 실행되며, 호스트 웹의 요청에 따라
 * 실시간으로 전면/후면 카메라를 즉시 전환(switchCamera)할 수 있습니다.
 */
class StreamActivity : AppCompatActivity(), PeerConnectionListener {
    companion object {
        private const val TAG = "StreamActivity"
        const val EXTRA_CAMERA = "extra_camera"
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }

    private lateinit var peerConnectionManager: PeerConnectionManager
    private val db = FirebaseFirestore.getInstance()

    private var roomId: String = ""
    private var deviceId: String = ""
    private var currentCameraType: String = "back"
    private var answerListener: ListenerRegistration? = null
    private var candidateListener: ListenerRegistration? = null
    private var commandListener: ListenerRegistration? = null
    private var countDownTimer: CountDownTimer? = null
    private var isStreamingActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream)

        // 풀스크린 인텐트 알림 즉시 취소 (사용자 화면/상태바에 알림 흔적 제거)
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.cancel(2001)
        } catch (e: Exception) {
            Log.w(TAG, "Notification cancel error", e)
        }

        // 완전 투명 및 터치 관통 설정 (알림화면 및 UI 노출 방지)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // 화면 켜기 및 잠금 화면 위 동작 (Android 11+ 지원)
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

        currentCameraType = intent.getStringExtra(EXTRA_CAMERA) ?: "back"
        roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: "room_${System.currentTimeMillis()}"
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: getSharedPreferences("antitheft", MODE_PRIVATE).getString("device_id", "") ?: ""

        // 웹 대시보드에 StreamActivity 구동 확인 통보
        if (deviceId.isNotEmpty()) {
            db.collection("devices").document(deviceId)
                .collection("commands").document("stream")
                .update("status", "STREAM_ACTIVITY_STARTED")
        }

        val isFront = currentCameraType == "front"

        startCountDown()
        startWebRtcStreaming(isFront)
        listenForStreamCommands()
    }

    private fun listenForStreamCommands() {
        if (deviceId.isEmpty()) return
        commandListener = db.collection("devices").document(deviceId)
            .collection("commands").document("stream")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                val cmd = snapshot.getString("command")
                if (cmd == "STOP_STREAM") {
                    Log.d(TAG, "웹으로부터 STOP_STREAM 명령 수신 -> 스트리밍 종료")
                    stopStreamingAndFinish()
                    return@addSnapshotListener
                }

                val requestedCamera = snapshot.getString("camera")
                if (!requestedCamera.isNullOrEmpty() && requestedCamera != currentCameraType) {
                    Log.d(TAG, "실시간 카메라 변경 요청 감지: 현재 $currentCameraType -> 요청 $requestedCamera")
                    val targetIsFront = requestedCamera == "front"
                    peerConnectionManager.switchCamera { success, isFront ->
                        if (success) {
                            if (isFront != targetIsFront) {
                                // 다중 렌즈 기기에서 원하는 방향이 아닐 경우(예: 망원/초광각 렌즈 순환) 한 번 더 순환 전환
                                peerConnectionManager.switchCamera { success2, isFront2 ->
                                    handleSwitchResult(success2, isFront2)
                                }
                            } else {
                                handleSwitchResult(true, isFront)
                            }
                        } else {
                            handleSwitchResult(false, isFront)
                        }
                    }
                }
            }
    }

    private fun handleSwitchResult(success: Boolean, isFront: Boolean) {
        if (success) {
            currentCameraType = if (isFront) "front" else "back"
            Log.d(TAG, "카메라 전환 성공 완료 -> 현재: $currentCameraType")
            if (deviceId.isNotEmpty()) {
                db.collection("devices").document(deviceId)
                    .collection("commands").document("stream")
                    .update(
                        mapOf(
                            "status" to "CAMERA_SWITCHED",
                            "camera" to currentCameraType,
                            "switchedAt" to System.currentTimeMillis()
                        )
                    )
            }
        } else {
            Log.e(TAG, "카메라 전환 실패 -> 현재 카메라 유지: $currentCameraType")
            if (deviceId.isNotEmpty()) {
                db.collection("devices").document(deviceId)
                    .collection("commands").document("stream")
                    .update(
                        mapOf(
                            "status" to "CAMERA_SWITCH_FAILED",
                            "camera" to currentCameraType
                        )
                    )
            }
        }
    }

    private fun startCountDown() {
        countDownTimer = object : CountDownTimer(180_000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {}

            override fun onFinish() {
                Log.d(TAG, "스트리밍 3분 만료 종료")
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
                    Log.d(TAG, "비디오 트랙 추가 완료 ($currentCameraType)")
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
                        "camera" to currentCameraType,
                        "createdAt" to System.currentTimeMillis()
                    )).addOnSuccessListener {
                        Log.d(TAG, "Offer 등록 완료: $roomId")
                        db.collection("devices").document(deviceId)
                            .collection("commands").document("stream")
                            .update("status", "OFFER_SENT")
                    }

                    // 호스트의 Answer 대기
                    listenForAnswer(roomRef)
                    // 호스트의 ICE Candidate 대기
                    listenForRemoteIceCandidates(roomRef)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC 시작 실패", e)
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
        commandListener?.remove()
        if (deviceId.isNotEmpty()) {
            db.collection("devices").document(deviceId)
                .collection("commands").document("stream")
                .update("status", "STREAM_FINISHED")
        }
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
