package com.antitheftguard.client.location

import com.google.android.gms.location.Priority

data class LocationConfig(
    val interval: Long,
    val priority: Int,
    val minUpdateDistanceMeters: Float
)

object SmartIntervalCalculator {
    const val INTERVAL_MOVING = 5_000L        // 5초
    const val INTERVAL_STATIONARY = 180_000L  // 180초 (정지 시 극저전력 스탠바이)
    const val SPEED_THRESHOLD = 1.5f          // m/s (~5.4 km/h)

    /** 이동 여부를 판단합니다. */
    fun isMoving(
        speed: Float,
        isCharging: Boolean,
        isMotionActive: Boolean = true,
        isStationaryConfirmed: Boolean = false
    ): Boolean {
        // 정지 확정 상태이거나 모션 센서가 정지(false)인 경우 무조건 정지 판정 (실내 GPS drift 튐 차단)
        if (isStationaryConfirmed || !isMotionActive) return false
        // 충전 중이거나 측정 속도가 기준치를 초과하면 이동으로 판정
        if (isCharging || speed > SPEED_THRESHOLD) return true
        // 그 외의 경우 하드웨어 센서 허브의 모션 상태를 따름
        return isMotionActive
    }

    /** 속도와 충전 상태를 기반으로 GPS 측정 주기를 계산합니다. (하위 호환용) */
    fun calculate(
        speed: Float,
        isCharging: Boolean,
        isMotionActive: Boolean = true,
        isStationaryConfirmed: Boolean = false
    ): Long {
        return if (isMoving(speed, isCharging, isMotionActive, isStationaryConfirmed)) INTERVAL_MOVING else INTERVAL_STATIONARY
    }

    /**
     * 배터리와 정밀도의 균형을 위한 스마트 위치 설정 객체를 생성합니다.
     * - 이동 중: PRIORITY_HIGH_ACCURACY (5초 주기, 위성 GPS 풀가동, 거리 필터 0m)
     * - 정지 중: PRIORITY_HIGH_ACCURACY (180초 극저전력 주기, 기지국 220m 튐 원천 방지, 거리 필터 15m)
     */
    fun getConfig(
        speed: Float,
        isCharging: Boolean,
        isMotionActive: Boolean = true,
        isStationaryConfirmed: Boolean = false
    ): LocationConfig {
        return if (isMoving(speed, isCharging, isMotionActive, isStationaryConfirmed)) {
            LocationConfig(
                interval = INTERVAL_MOVING,
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                minUpdateDistanceMeters = 0f
            )
        } else {
            LocationConfig(
                interval = INTERVAL_STATIONARY,
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                minUpdateDistanceMeters = 15f
            )
        }
    }
}
