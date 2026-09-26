package com.antitheftguard.core.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*

object WebRtcConfig {
    val ICE_SERVERS = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        // TURN 서버는 환경에 맞게 추가
        // PeerConnection.IceServer.builder("turn:your-turn-server.com:3478")
        //     .setUsername("username").setPassword("password").createIceServer()
    )
}

interface PeerConnectionListener {
    fun onIceCandidateGenerated(candidate: IceCandidate)
    fun onTrackReceived(track: MediaStreamTrack)
    fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
    fun onDataChannelMessage(message: String)
}

class PeerConnectionManager(
    private val context: Context,
    private val listener: PeerConnectionListener
) {
    companion object {
        private const val TAG = "PeerConnectionManager"
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null

    fun initialize() {
        eglBase = EglBase.create()
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun getEglBase(): EglBase? = eglBase

    fun createPeerConnection() {
        val config = PeerConnection.RTCConfiguration(WebRtcConfig.ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onIceCandidateGenerated(candidate)
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                transceiver.receiver?.track()?.let { listener.onTrackReceived(it) }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                listener.onConnectionStateChanged(state)
                Log.d(TAG, "연결 상태 변경: $state")
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
    }

    fun addTrack(track: MediaStreamTrack, streamIds: List<String> = listOf("stream0")) {
        peerConnection?.addTrack(track, streamIds)
    }

    fun createOffer(callback: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                callback(sdp)
            }
            override fun onCreateFailure(error: String?) { Log.e(TAG, "Offer 생성 실패: $error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun createAnswer(callback: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                callback(sdp)
            }
            override fun onCreateFailure(error: String?) { Log.e(TAG, "Answer 생성 실패: $error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun createAudioTrack(): AudioTrack? {
        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        return peerConnectionFactory?.createAudioTrack("audio0", audioSource)
    }

    private var activeCapturer: VideoCapturer? = null

    fun createVideoTrack(capturer: VideoCapturer): VideoTrack? {
        activeCapturer = capturer
        val videoSource = peerConnectionFactory?.createVideoSource(capturer.isScreencast)
        capturer.initialize(
            SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext),
            context,
            videoSource!!.capturerObserver
        )
        capturer.startCapture(640, 480, 15) // 저화질 480p, 15fps
        return peerConnectionFactory?.createVideoTrack("video0", videoSource)
    }

    private val isSwitchingCamera = java.util.concurrent.atomic.AtomicBoolean(false)

    fun switchCamera(callback: ((Boolean, Boolean) -> Unit)? = null) {
        val capturer = activeCapturer as? CameraVideoCapturer ?: run {
            Log.e(TAG, "현재 활성화된 카메라 캡처러가 없습니다.")
            callback?.invoke(false, false)
            return
        }

        if (!isSwitchingCamera.compareAndSet(false, true)) {
            Log.w(TAG, "카메라 전환이 이미 진행 중입니다. 요청 무시.")
            callback?.invoke(false, false)
            return
        }

        try {
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    isSwitchingCamera.set(false)
                    Log.d(TAG, "카메라 토글 전환 성공 (전면 카메라 여부: $isFrontCamera)")
                    callback?.invoke(true, isFrontCamera)
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    isSwitchingCamera.set(false)
                    Log.e(TAG, "카메라 토글 실패: $errorDescription")
                    callback?.invoke(false, false)
                }
            })
        } catch (e: Throwable) {
            isSwitchingCamera.set(false)
            Log.e(TAG, "switchCamera 호출 중 예외 발생", e)
            callback?.invoke(false, false)
        }
    }

    fun stopCapture() {
        try {
            activeCapturer?.stopCapture()
            activeCapturer?.dispose()
            activeCapturer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capturer", e)
        }
    }

    fun close() {
        stopCapture()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnectionFactory?.dispose()
        eglBase?.release()
        peerConnection = null
        peerConnectionFactory = null
        eglBase = null
    }

    private class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Log.e("SDP", "실패: $error") }
        override fun onSetFailure(error: String?) { Log.e("SDP", "실패: $error") }
    }
}

object CameraHelper {
    private const val TAG = "CameraHelper"

    fun createCameraCapturer(context: Context, preferFront: Boolean): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        val eventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(errorDescription: String?) {
                Log.e(TAG, "WebRTC Camera error: $errorDescription")
            }
            override fun onCameraDisconnected() {
                Log.w(TAG, "WebRTC Camera disconnected")
            }
            override fun onCameraFreezed(errorDescription: String?) {
                Log.w(TAG, "WebRTC Camera freezed: $errorDescription")
            }
            override fun onCameraOpening(cameraName: String?) {
                Log.d(TAG, "WebRTC Camera opening: $cameraName")
            }
            override fun onFirstFrameAvailable() {
                Log.d(TAG, "WebRTC First frame available")
            }
            override fun onCameraClosed() {
                Log.d(TAG, "WebRTC Camera closed")
            }
        }

        // 1. 요청된 방향(전면 또는 후면) 우선 탐색
        for (name in deviceNames) {
            if (preferFront && enumerator.isFrontFacing(name)) {
                Log.d(TAG, "전면 카메라 선택: $name")
                return enumerator.createCapturer(name, eventsHandler)
            } else if (!preferFront && enumerator.isBackFacing(name)) {
                Log.d(TAG, "후면 카메라 선택: $name")
                return enumerator.createCapturer(name, eventsHandler)
            }
        }

        // 2. 일치하는 카메라가 없을 경우 사용 가능한 첫 번째 카메라로 폴백
        for (name in deviceNames) {
            val capturer = enumerator.createCapturer(name, eventsHandler)
            if (capturer != null) {
                Log.d(TAG, "폴백 카메라 선택: $name")
                return capturer
            }
        }

        Log.e(TAG, "사용 가능한 카메라를 찾을 수 없습니다.")
        return null
    }
}
