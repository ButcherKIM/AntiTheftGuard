package com.antitheftguard.client

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.antitheftguard.client.logging.CrashLogHelper
import com.antitheftguard.core.firebase.FirestoreManager
import com.antitheftguard.core.model.ExitLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AntiTheftApp : Application() {

    companion object {
        private const val TAG = "AntiTheftApp"
        private const val PREFS_NAME = "antitheft"
        private const val KEY_LAST_EXIT_TIMESTAMP = "last_reported_exit_timestamp"
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firestoreManager = FirestoreManager()

    override fun onCreate() {
        super.onCreate()
        initCrashHandler()
        checkHistoricalExitReasons()
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            "${pInfo.versionName} (${pInfo.versionCode})"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun loadDeviceId(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("device_id", "") ?: ""
    }

    private fun initCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val deviceId = loadDeviceId()
                val crashLog = CrashLogHelper.buildCrashLog(
                    deviceId = if (deviceId.isNotEmpty()) deviceId else "unregistered_device",
                    throwable = throwable,
                    thread = thread,
                    appVersion = getAppVersion()
                )

                // 1) 로컬 백업 파일에 즉시 쓰기
                CrashLogHelper.saveCrashLogLocally(this, crashLog)
                Log.e(TAG, "💥 [CRASH] 미처리 예외 발생: ${crashLog.exceptionType} - ${crashLog.message}")

                // 2) Firestore에 동기 전송 (최대 3초 대기 후 종료)
                if (deviceId.isNotEmpty()) {
                    firestoreManager.saveCrashLogSync(deviceId, crashLog, timeoutMs = 3000L)
                }
            } catch (loggingError: Throwable) {
                Log.e(TAG, "크래시 로깅 중 부가 에러 발생", loggingError)
            } finally {
                // 원래 시스템 기본 종료 절차 수행
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun checkHistoricalExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        appScope.launch {
            try {
                val deviceId = loadDeviceId()
                if (deviceId.isEmpty()) return@launch

                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val lastReported = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)

                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@launch
                val exitReasons = activityManager.getHistoricalProcessExitReasons(packageName, 0, 5)

                var maxTimestamp = lastReported
                for (info in exitReasons) {
                    val ts = info.timestamp
                    if (ts <= lastReported) continue

                    if (ts > maxTimestamp) maxTimestamp = ts

                    val reasonCode = info.reason
                    // 비정상/강제 종료만 필터링하여 보고 (사용자가 앱을 직접 끈 경우는 제외)
                    if (CrashLogHelper.isAbnormalExit(reasonCode)) {
                        val exitLog = ExitLog(
                            deviceId = deviceId,
                            timestamp = ts,
                            reasonCode = reasonCode,
                            reasonDescription = CrashLogHelper.getExitReasonDescription(reasonCode),
                            importance = info.importance,
                            description = info.description ?: "",
                            pssKb = info.pss / 1024,
                            rssKb = info.rss / 1024,
                            appVersion = getAppVersion(),
                            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                        )

                        Log.w(TAG, "🚨 [EXIT_INFO] 직전 비정상 프로세스 종료 감지: ${exitLog.reasonDescription}")
                        firestoreManager.saveExitLog(deviceId, exitLog)
                    }
                }

                if (maxTimestamp > lastReported) {
                    prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, maxTimestamp).apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "종료 이력(ApplicationExitInfo) 조회 중 에러", e)
            }
        }
    }
}
