package com.antitheftguard.client.location

object SmartIntervalCalculator {
    const val INTERVAL_MOVING = 5_000L      // 5초
    const val INTERVAL_STATIONARY = 30_000L // 30초
    const val SPEED_THRESHOLD = 2.0f        // m/s

    /** 속도와 충전 상태를 기반으로 GPS 측정 주기를 계산합니다. */
    fun calculate(speed: Float, isCharging: Boolean): Long {
        return when {
            isCharging -> INTERVAL_MOVING
            speed > SPEED_THRESHOLD -> INTERVAL_MOVING
            else -> INTERVAL_STATIONARY
        }
    }
}
