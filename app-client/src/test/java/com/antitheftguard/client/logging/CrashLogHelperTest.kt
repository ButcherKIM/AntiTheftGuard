package com.antitheftguard.client.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogHelperTest {

    @Test
    fun `formatStackTrace는 예외 정보와 스택트레이스를 정확히 추출한다`() {
        val testException = IllegalStateException("Test crash message")
        val stackTrace = CrashLogHelper.formatStackTrace(testException)

        assertTrue(stackTrace.contains("IllegalStateException"))
        assertTrue(stackTrace.contains("Test crash message"))
        assertTrue(stackTrace.contains("CrashLogHelperTest"))
    }

    @Test
    fun `getExitReasonDescription은 Android 시스템 종료 코드를 직관적인 한글 설명으로 변환한다`() {
        assertEquals("REASON_EXIT_SELF (정상 자체 종료)", CrashLogHelper.getExitReasonDescription(1))
        assertEquals("REASON_LOW_MEMORY (시스템 메모리 부족 LMK 강제 종료)", CrashLogHelper.getExitReasonDescription(3))
        assertEquals("REASON_CRASH (Java/Kotlin 비정상 예외 크래시)", CrashLogHelper.getExitReasonDescription(4))
        assertEquals("REASON_EXCESSIVE_RESOURCE_USAGE (CPU/배터리/메모리 과다 사용 제한)", CrashLogHelper.getExitReasonDescription(9))
        assertEquals("REASON_USER_REQUESTED (사용자 강제 종료 / 최근 앱 스와이프)", CrashLogHelper.getExitReasonDescription(10))
        assertEquals("REASON_OTHER (제조사 배터리 절전/시스템 강제 종료)", CrashLogHelper.getExitReasonDescription(13))
        assertEquals("REASON_UNKNOWN (999)", CrashLogHelper.getExitReasonDescription(999))
    }

    @Test
    fun `isAbnormalExit은 사용자 정상 종료와 시스템 비정상 종료를 정확히 구분한다`() {
        // 정상 종료인 경우 false
        assertFalse(CrashLogHelper.isAbnormalExit(1))  // REASON_EXIT_SELF
        assertFalse(CrashLogHelper.isAbnormalExit(10)) // REASON_USER_REQUESTED
        assertFalse(CrashLogHelper.isAbnormalExit(11)) // REASON_USER_STOPPED

        // 비정상/강제 종료인 경우 true
        assertTrue(CrashLogHelper.isAbnormalExit(2))  // REASON_SIGNALED
        assertTrue(CrashLogHelper.isAbnormalExit(3))  // REASON_LOW_MEMORY
        assertTrue(CrashLogHelper.isAbnormalExit(4))  // REASON_CRASH
        assertTrue(CrashLogHelper.isAbnormalExit(5))  // REASON_CRASH_NATIVE
        assertTrue(CrashLogHelper.isAbnormalExit(6))  // REASON_ANR
        assertTrue(CrashLogHelper.isAbnormalExit(9))  // REASON_EXCESSIVE_RESOURCE_USAGE
        assertTrue(CrashLogHelper.isAbnormalExit(13)) // REASON_OTHER (배터리 절전 등)
    }
}
