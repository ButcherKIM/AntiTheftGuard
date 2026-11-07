package com.antitheftguard.client.logging

import android.content.Context
import android.os.Build
import com.antitheftguard.core.model.CrashLog
import com.antitheftguard.core.model.ExitLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashLogHelper {

    fun formatStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    fun buildCrashLog(
        deviceId: String,
        throwable: Throwable,
        thread: Thread,
        appVersion: String
    ): CrashLog {
        return CrashLog(
            deviceId = deviceId,
            timestamp = System.currentTimeMillis(),
            exceptionType = throwable.javaClass.name,
            message = throwable.message ?: "No message",
            stackTrace = formatStackTrace(throwable),
            threadName = thread.name,
            appVersion = appVersion,
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    fun saveCrashLogLocally(context: Context, crashLog: CrashLog): Boolean {
        return try {
            val file = File(context.filesDir, "crash_latest.txt")
            val content = buildString {
                appendLine("=== AntiTheftGuard Crash Report ===")
                appendLine("Time: ${java.util.Date(crashLog.timestamp)}")
                appendLine("Device: ${crashLog.deviceId} (${crashLog.deviceModel})")
                appendLine("AppVersion: ${crashLog.appVersion}, OS: ${crashLog.osVersion}")
                appendLine("Thread: ${crashLog.threadName}")
                appendLine("Exception: ${crashLog.exceptionType}")
                appendLine("Message: ${crashLog.message}")
                appendLine("--- Stack Trace ---")
                appendLine(crashLog.stackTrace)
            }
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getExitReasonDescription(reasonCode: Int): String {
        return when (reasonCode) {
            1 -> "REASON_EXIT_SELF (정상 자체 종료)"
            2 -> "REASON_SIGNALED (시스템 시그널 종료)"
            3 -> "REASON_LOW_MEMORY (시스템 메모리 부족 LMK 강제 종료)"
            4 -> "REASON_CRASH (Java/Kotlin 비정상 예외 크래시)"
            5 -> "REASON_CRASH_NATIVE (C/C++ 네이티브 크래시)"
            6 -> "REASON_ANR (Application Not Responding 무응답 종료)"
            7 -> "REASON_INITIALIZATION_FAILURE (앱 초기화 실패)"
            8 -> "REASON_PERMISSION_CHANGE (런타임 권한 변경으로 인한 종료)"
            9 -> "REASON_EXCESSIVE_RESOURCE_USAGE (CPU/배터리/메모리 과다 사용 제한)"
            10 -> "REASON_USER_REQUESTED (사용자 강제 종료 / 최근 앱 스와이프)"
            11 -> "REASON_USER_STOPPED (사용자 앱 중지)"
            12 -> "REASON_DEPENDENCY_DIED (의존 서비스 사망)"
            13 -> "REASON_OTHER (제조사 배터리 절전/시스템 강제 종료)"
            else -> "REASON_UNKNOWN ($reasonCode)"
        }
    }

    fun isAbnormalExit(reasonCode: Int): Boolean {
        // 정상적인 사용자 직접 종료(10, 11) 및 정상 자체 종료(1)를 제외한 모든 비정상/강제 종료
        return reasonCode !in listOf(1, 10, 11)
    }
}
