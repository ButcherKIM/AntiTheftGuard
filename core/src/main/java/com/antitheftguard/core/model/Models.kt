package com.antitheftguard.core.model

import kotlinx.serialization.Serializable

/** GPS 좌표 모델 */
@Serializable
data class GpsPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val timestamp: Long,
    val batteryLevel: Int
)

/** 위치 기록 배치 모델 */
@Serializable
data class GpsBatch(
    val deviceId: String,
    val startTime: Long,
    val endTime: Long,
    val points: List<GpsPoint>
)

/** 기기 정보 모델 */
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val ownerId: String,
    val deviceName: String,
    val fcmToken: String,
    val lastSeen: Long,
    val batteryLevel: Int
)

/** WebRTC 시그널링 데이터 모델 */
@Serializable
data class SignalingData(
    val type: String,
    val sdp: String
)

/** WebRTC ICE Candidate 모델 */
@Serializable
data class IceCandidateData(
    val candidate: String,
    val sdpMid: String,
    val sdpMLineIndex: Int
)

/** 페어링 토큰 모델 */
@Serializable
data class PairingToken(
    val token: String,
    val userId: String,
    val createdAt: Long,
    val expiresAt: Long
)

/** 스트림 명령어 열거형 */
enum class StreamCommand {
    START_STREAM,
    STOP_STREAM
}
