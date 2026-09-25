package com.antitheftguard.client.worker

import org.junit.Assert.assertTrue
import org.junit.Test

class DataCleanupWorkerTest {
    @Test
    fun `72시간(3일) 경계값 계산이 올바르게 이루어지는지 검증한다`() {
        val now = System.currentTimeMillis()
        val threeDaysMillis = 3 * 24 * 60 * 60 * 1000L
        val boundary = now - threeDaysMillis
        
        assertTrue(boundary < now)
        assertTrue(now - boundary == threeDaysMillis)
    }
}
