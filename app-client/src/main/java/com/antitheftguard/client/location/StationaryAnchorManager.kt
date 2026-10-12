package com.antitheftguard.client.location

import com.antitheftguard.core.model.GpsPoint
import kotlin.math.*

/**
 * 정지 상태 감지 및 실내/정지 시 GPS Drift 튐 방지를 위한 앵커 관리자.
 * 
 * 1. 이중 정지 확정 (하드웨어 모션 센서 STILL + 최근 GPS 속도 안정)
 * 2. 앵커 구역(반경 50m) 내 GPS 픽스 가중 누적 수렴 (Recursive Weighted Centroid)
 * 3. 앵커 내 지터(Jitter)를 단일 수렴 좌표로 클램핑하여 지도상 거미줄(스파게티) 궤적 원천 차단
 * 4. 100m 이상 실제 변위 또는 모션 활성화 시 탈출(Breakout)하여 이동 모드 복귀
 */
class StationaryAnchorManager(
    val anchorRadiusMeters: Float = 50f,
    val breakoutDistanceMeters: Float = 100f
) {
    data class Anchor(
        var latitude: Double,
        var longitude: Double,
        var accuracy: Float,
        var totalWeight: Double,
        var sampleCount: Int,
        var firstTimestamp: Long,
        var lastTimestamp: Long
    )

    var currentAnchor: Anchor? = null
        private set

    var isStationaryConfirmed: Boolean = false
        private set

    // 최근 GPS 속도 추적 (최근 3개)
    private val recentSpeeds = mutableListOf<Float>()

    private var consecutiveBreakoutCount = 0

    fun updateMotionState(isMotionActive: Boolean) {
        if (isMotionActive) {
            // 하드웨어 모션 센서가 움직임(걸음, 차량 등)을 알리면 정지 확정 해제
            isStationaryConfirmed = false
            currentAnchor = null
            consecutiveBreakoutCount = 0
        }
    }

    /**
     * 수신된 GPS 포인트를 검증하여 정지 여부를 판단하고,
     * 정지 중인 경우 수렴된 앵커 좌표로 보정된 GpsPoint를 반환합니다.
     */
    fun processPoint(
        point: GpsPoint,
        isMotionActive: Boolean
    ): ProcessResult {
        recentSpeeds.add(point.speed)
        if (recentSpeeds.size > 3) recentSpeeds.removeAt(0)

        val areRecentSpeedsLow = recentSpeeds.isNotEmpty() && recentSpeeds.all { it < 1.2f }

        // 1. 이중 정지 확정: 모션 센서 STILL(false) + 최근 GPS 속도 모두 안정 + 유효한 오차(<= 50m)
        if (!isMotionActive && areRecentSpeedsLow && !isStationaryConfirmed) {
            if (point.accuracy <= 50f) {
                isStationaryConfirmed = true
                consecutiveBreakoutCount = 0
                currentAnchor = Anchor(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    accuracy = point.accuracy.coerceAtLeast(5f),
                    totalWeight = calculateWeight(point.accuracy),
                    sampleCount = 1,
                    firstTimestamp = point.timestamp,
                    lastTimestamp = point.timestamp
                )
                return ProcessResult(
                    refinedPoint = point.copy(speed = 0f),
                    isStationary = true,
                    isBreakout = false
                )
            }
        }

        // 2. 이미 정지 상태인 경우
        if (isStationaryConfirmed && currentAnchor != null) {
            val anchor = currentAnchor!!
            val distance = distanceBetweenMeters(
                anchor.latitude, anchor.longitude,
                point.latitude, point.longitude
            )

            // 신뢰할 수 없는 거친 오차(> 50m, 예: 기지국 200m)는 탈출이나 앵커 갱신에 사용하지 않고 기존 앵커로 강제 고정
            if (point.accuracy > 50f) {
                val clampedPoint = point.copy(
                    latitude = anchor.latitude,
                    longitude = anchor.longitude,
                    accuracy = anchor.accuracy,
                    speed = 0f
                )
                return ProcessResult(
                    refinedPoint = clampedPoint,
                    isStationary = true,
                    isBreakout = false
                )
            }

            // A. 탈출(Breakout) 판정:
            // - 모션 센서가 활성화(isMotionActive)되어 있고 거리가 벗어났거나,
            // - 또는 거리가 100m 이상 멀어진 상태가 연속 2회 이상 고정밀도(accuracy <= 35m)로 유지될 때만 탈출
            val isCandidateBreakout = (isMotionActive && distance > anchorRadiusMeters) ||
                    (distance > breakoutDistanceMeters && point.accuracy <= 35f)

            if (isCandidateBreakout) {
                consecutiveBreakoutCount++
                val canBreakout = (isMotionActive && point.speed > 1.2f) || (consecutiveBreakoutCount >= 2)
                if (canBreakout) {
                    isStationaryConfirmed = false
                    currentAnchor = null
                    consecutiveBreakoutCount = 0
                    return ProcessResult(
                        refinedPoint = point,
                        isStationary = false,
                        isBreakout = true
                    )
                } else {
                    // 단발성 거리 튐(1회차)은 노이즈로 보고 기존 앵커 좌표 유지
                    val clampedPoint = point.copy(
                        latitude = anchor.latitude,
                        longitude = anchor.longitude,
                        accuracy = anchor.accuracy,
                        speed = 0f
                    )
                    return ProcessResult(
                        refinedPoint = clampedPoint,
                        isStationary = true,
                        isBreakout = false
                    )
                }
            } else {
                consecutiveBreakoutCount = 0
            }

            // B. 앵커 구역 내 유지: 오차 반경에 따른 가중 누적 평균(Centroid Refinement) 계산
            // 오차가 양호한 포인트(<= 40m) 및 앵커 반경 내 포인트만 수렴 계산에 반영
            if (distance <= anchorRadiusMeters && point.accuracy in 1f..40f) {
                val weight = calculateWeight(point.accuracy)
                val newTotalWeight = anchor.totalWeight + weight
                anchor.latitude = (anchor.latitude * anchor.totalWeight + point.latitude * weight) / newTotalWeight
                anchor.longitude = (anchor.longitude * anchor.totalWeight + point.longitude * weight) / newTotalWeight
                anchor.totalWeight = newTotalWeight
                anchor.sampleCount++
                // 샘플이 누적될수록 추정 오차 반경 축소 (최소 3m)
                anchor.accuracy = (1.0 / sqrt(newTotalWeight)).toFloat().coerceIn(3f, point.accuracy)
            }
            anchor.lastTimestamp = point.timestamp

            // 보정된 포인트: 수렴된 앵커 좌표로 고정, 속도 0, 시간은 현재 픽스 시간
            val clampedPoint = point.copy(
                latitude = anchor.latitude,
                longitude = anchor.longitude,
                accuracy = anchor.accuracy,
                speed = 0f
            )

            return ProcessResult(
                refinedPoint = clampedPoint,
                isStationary = true,
                isBreakout = false
            )
        }

        // 3. 이동 중인 경우: 원본 그대로 통과
        return ProcessResult(
            refinedPoint = point,
            isStationary = false,
            isBreakout = false
        )
    }

    private fun calculateWeight(accuracy: Float): Double {
        val safeAcc = accuracy.coerceIn(5f, 60f).toDouble()
        return 1.0 / (safeAcc * safeAcc)
    }

    companion object {
        /** Haversine 공식을 사용한 두 위경도 좌표 간 구면 거리 계산 (단위: 미터) */
        fun distanceBetweenMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return (6371000 * c).toFloat()
        }
    }

    data class ProcessResult(
        val refinedPoint: GpsPoint,
        val isStationary: Boolean,
        val isBreakout: Boolean
    )
}
