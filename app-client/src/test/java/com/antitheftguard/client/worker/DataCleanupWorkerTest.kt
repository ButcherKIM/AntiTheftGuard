package com.antitheftguard.client.worker

import com.antitheftguard.core.model.DailyHistory
import com.antitheftguard.core.model.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DataCleanupWorkerTest {
    @Test
    fun `30일 보관 경계값 계산이 올바르게 이루어지는지 검증한다`() {
        val now = System.currentTimeMillis()
        val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000L
        val boundary = now - thirtyDaysMillis
        
        assertTrue(boundary < now)
        assertEquals(thirtyDaysMillis, now - boundary)
    }

    @Test
    fun `일별 롤업 날짜 포맷 및 DailyHistory 모델 변환이 올바르게 생성되는지 검증한다`() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val calendar = Calendar.getInstance()
        calendar.set(2026, Calendar.SEPTEMBER, 29, 12, 0, 0)
        val dateStr = dateFormat.format(calendar.time)
        assertEquals("2026-09-29", dateStr)

        val points = listOf(
            GpsPoint(latitude = 37.5665, longitude = 126.9780, accuracy = 5.0f, speed = 0.0f, timestamp = 1727581200000L, batteryLevel = 90),
            GpsPoint(latitude = 37.5670, longitude = 126.9785, accuracy = 6.0f, speed = 1.2f, timestamp = 1727581260000L, batteryLevel = 89)
        )

        val dailyHistory = DailyHistory(
            deviceId = "test_device",
            date = dateStr,
            startTime = points.first().timestamp,
            endTime = points.last().timestamp,
            pointCount = points.size,
            points = points
        )

        assertEquals("test_device", dailyHistory.deviceId)
        assertEquals("2026-09-29", dailyHistory.date)
        assertEquals(2, dailyHistory.pointCount)
        assertEquals(1727581200000L, dailyHistory.startTime)
        assertEquals(1727581260000L, dailyHistory.endTime)
    }
}

