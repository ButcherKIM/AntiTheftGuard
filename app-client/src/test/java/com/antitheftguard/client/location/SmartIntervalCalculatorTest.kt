package com.antitheftguard.client.location

import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartIntervalCalculatorTest {
    @Test
    fun `충전 중일 때는 속도와 모션 상태와 무관하게 고정밀 빠른 주기를 반환한다`() {
        assertEquals(5000L, SmartIntervalCalculator.calculate(1.0f, true, isMotionActive = false))
        assertEquals(5000L, SmartIntervalCalculator.calculate(10.0f, true, isMotionActive = true))
        val config = SmartIntervalCalculator.getConfig(1.0f, true, isMotionActive = false)
        assertEquals(5000L, config.interval)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, config.priority)
        assertEquals(0f, config.minUpdateDistanceMeters)
    }

    @Test
    fun `충전 중이 아니고 정지 상태(isMotionActive=false)이며 속도가 느릴 때는 초절전 주기와 기지국 모드를 반환한다`() {
        assertEquals(120000L, SmartIntervalCalculator.calculate(1.0f, false, isMotionActive = false))
        val config = SmartIntervalCalculator.getConfig(1.0f, false, isMotionActive = false)
        assertEquals(120000L, config.interval)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, config.priority)
        assertEquals(30f, config.minUpdateDistanceMeters)
    }

    @Test
    fun `충전 중이 아니고 정지 상태여도 속도가 임계치를 초과하면 안전망으로 고정밀 빠른 주기를 반환한다`() {
        assertEquals(5000L, SmartIntervalCalculator.calculate(3.0f, false, isMotionActive = false))
        val config = SmartIntervalCalculator.getConfig(3.0f, false, isMotionActive = false)
        assertEquals(5000L, config.interval)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, config.priority)
        assertEquals(0f, config.minUpdateDistanceMeters)
    }

    @Test
    fun `충전 중이 아니고 속도가 0이어도 센서가 이동 중(isMotionActive=true)이면 신호대기 등으로 판정하여 고정밀 주기를 유지한다`() {
        assertEquals(5000L, SmartIntervalCalculator.calculate(0.0f, false, isMotionActive = true))
        val config = SmartIntervalCalculator.getConfig(0.0f, false, isMotionActive = true)
        assertEquals(5000L, config.interval)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, config.priority)
        assertEquals(0f, config.minUpdateDistanceMeters)
    }

    @Test
    fun `기본 매개변수는 호환성을 위해 이동 상태(isMotionActive=true)로 동작한다`() {
        assertEquals(5000L, SmartIntervalCalculator.calculate(0.5f, false))
        assertEquals(5000L, SmartIntervalCalculator.calculate(5.0f, false))
    }
}
