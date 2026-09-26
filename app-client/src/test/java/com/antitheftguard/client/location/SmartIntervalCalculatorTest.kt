package com.antitheftguard.client.location

import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartIntervalCalculatorTest {
    @Test
    fun `충전 중일 때는 속도와 무관하게 빠른 주기를 반환한다`() {
        assertEquals(5000L, SmartIntervalCalculator.calculate(1.0f, true))
        assertEquals(5000L, SmartIntervalCalculator.calculate(10.0f, true))
        val config = SmartIntervalCalculator.getConfig(1.0f, true)
        assertEquals(5000L, config.interval)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, config.priority)
        assertEquals(0f, config.minUpdateDistanceMeters)
    }

    @Test
    fun `충전 중이 아니고 속도가 느릴 때는 절전 주기 및 균형 모드를 반환한다`() {
        assertEquals(60000L, SmartIntervalCalculator.calculate(1.0f, false))
        val config = SmartIntervalCalculator.getConfig(1.0f, false)
        assertEquals(60000L, config.interval)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, config.priority)
        assertEquals(15f, config.minUpdateDistanceMeters)
    }

    @Test
    fun `충전 중이 아니고 속도가 빠를 때는 고정밀 빠른 주기를 반환한다`() {
        assertEquals(5000L, SmartIntervalCalculator.calculate(3.0f, false))
        val config = SmartIntervalCalculator.getConfig(3.0f, false)
        assertEquals(5000L, config.interval)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, config.priority)
        assertEquals(0f, config.minUpdateDistanceMeters)
    }
}
