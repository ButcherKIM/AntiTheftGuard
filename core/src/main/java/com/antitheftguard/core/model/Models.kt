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
    val deviceId: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val points: List<GpsPoint> = emptyList()
)

/** 일별 위치 기록 통합 롤업 모델 (장기 보관 및 읽기 횟수 99.9% 최적화용) */
@Serializable
data class DailyHistory(
    val deviceId: String = "",
    val date: String = "", // "YYYY-MM-DD"
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val pointCount: Int = 0,
    val points: List<GpsPoint> = emptyList()
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

/** 크래시(예외) 로그 모델 */
@Serializable
data class CrashLog(
    val deviceId: String = "",
    val timestamp: Long = 0L,
    val exceptionType: String = "",
    val message: String = "",
    val stackTrace: String = "",
    val threadName: String = "",
    val appVersion: String = "",
    val osVersion: String = "",
    val deviceModel: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "deviceId" to deviceId,
        "timestamp" to timestamp,
        "exceptionType" to exceptionType,
        "message" to message,
        "stackTrace" to stackTrace,
        "threadName" to threadName,
        "appVersion" to appVersion,
        "osVersion" to osVersion,
        "deviceModel" to deviceModel
    )
}

/** OS 프로세스 종료 사유 모델 (Android 11+ ApplicationExitInfo 기반) */
@Serializable
data class ExitLog(
    val deviceId: String = "",
    val timestamp: Long = 0L,
    val reasonCode: Int = 0,
    val reasonDescription: String = "",
    val importance: Int = 0,
    val description: String = "",
    val pssKb: Long = 0L,
    val rssKb: Long = 0L,
    val appVersion: String = "",
    val osVersion: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "deviceId" to deviceId,
        "timestamp" to timestamp,
        "reasonCode" to reasonCode,
        "reasonDescription" to reasonDescription,
        "importance" to importance,
        "description" to description,
        "pssKb" to pssKb,
        "rssKb" to rssKb,
        "appVersion" to appVersion,
        "osVersion" to osVersion
    )
}
