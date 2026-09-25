package com.antitheftguard.client.location

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartIntervalCalculatorTest {
    @Test
    fun `충전 중일 때는 속도와 무관하게 빠른 주기를 반환한다`() {
        assertEquals(5000L, SmartIntervalCalculator.calculate(1.0f, true))
        assertEquals(5000L, SmartIntervalCalculator.calculate(10.0f, true))
    }

    @Test
    fun `충전 중이 아니고 속도가 느릴 때는 느린 주기를 반환한다`() {
        assertEquals(30000L, SmartIntervalCalculator.calculate(1.0f, false))
    }

    @Test
    fun `충전 중이 아니고 속도가 빠를 때는 빠른 주기를 반환한다`() {
        assertEquals(5000L, SmartIntervalCalculator.calculate(3.0f, false))
    }
}
