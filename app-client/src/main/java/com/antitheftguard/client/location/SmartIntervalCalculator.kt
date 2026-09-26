package com.antitheftguard.client.location

import com.google.android.gms.location.Priority

data class LocationConfig(
    val interval: Long,
    val priority: Int,
    val minUpdateDistanceMeters: Float
)

object SmartIntervalCalculator {
    const val INTERVAL_MOVING = 5_000L      // 5초
    const val INTERVAL_STATIONARY = 60_000L // 60초 (1분)
    const val SPEED_THRESHOLD = 1.5f        // m/s (~5.4 km/h)

    /** 이동 여부를 판단합니다. */
    fun isMoving(speed: Float, isCharging: Boolean): Boolean {
        return isCharging || speed > SPEED_THRESHOLD
    }

    /** 속도와 충전 상태를 기반으로 GPS 측정 주기를 계산합니다. (하위 호환용) */
    fun calculate(speed: Float, isCharging: Boolean): Long {
        return if (isMoving(speed, isCharging)) INTERVAL_MOVING else INTERVAL_STATIONARY
    }

    /**
     * 배터리 극대화를 위한 스마트 위치 설정 객체를 생성합니다.
     * - 이동 중: PRIORITY_HIGH_ACCURACY (5초, 위성 GPS 풀가동, 거리 필터 0m)
     * - 정지 중: PRIORITY_BALANCED_POWER_ACCURACY (60초, Wi-Fi/기지국 기반 저전력, 거리 필터 15m)
     */
    fun getConfig(speed: Float, isCharging: Boolean): LocationConfig {
        return if (isMoving(speed, isCharging)) {
            LocationConfig(
                interval = INTERVAL_MOVING,
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                minUpdateDistanceMeters = 0f
            )
        } else {
            LocationConfig(
                interval = INTERVAL_STATIONARY,
                priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                minUpdateDistanceMeters = 15f
            )
        }
    }
}
