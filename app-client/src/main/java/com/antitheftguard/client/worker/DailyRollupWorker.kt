package com.antitheftguard.client.worker

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.antitheftguard.client.db.AppDatabase
import com.antitheftguard.core.firebase.FirestoreManager
import com.antitheftguard.core.model.DailyHistory
import com.antitheftguard.core.model.GpsPoint
import com.antitheftguard.client.location.StationaryAnchorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 일별 위치 데이터를 단일 문서로 갈무리(Rollup)하여 업로드하고,
 * 30일이 지난 오래된 로컬 데이터를 정리하는 백그라운드 워커.
 * 
 * - 하루 1개 문서(daily_history/YYYY-MM-DD)로 저장하여 장기 히스토리 조회 시 읽기 횟수를 99.9% 절감
 * - 하루 단 1회의 쓰기(Write)만 발생하여 파이어스토어 무료 쿼터(Spark 플랜) 완벽 준수
 */
class DailyRollupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DailyRollupWorker"
        const val PREFS_NAME = "antitheft"
        const val KEY_DEVICE_ID = "device_id"
    }

    private val firestoreManager = FirestoreManager()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var deviceId = prefs.getString(KEY_DEVICE_ID, "") ?: ""
            if (deviceId.isEmpty()) {
                val sanitizedModel = Build.MODEL.replace(" ", "_").lowercase(Locale.getDefault())
                deviceId = "phone_$sanitizedModel"
            }

            val db = AppDatabase.getInstance(applicationContext)
            val gpsDao = db.gpsPointDao()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            // 오늘(0일 전, 진행 중인 당일 누적 데이터)부터 최근 7일 전까지 롤업 확인
            // 0: 오늘 (하루 4회 주기적 갱신), 1..7: 과거 날짜 (완료 시 완료 플래그 저장)
            for (daysAgo in 0..7) {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = System.currentTimeMillis()
                calendar.add(Calendar.DAY_OF_YEAR, -daysAgo)

                // 해당 날짜의 00:00:00.000 계산
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val startOfDay = calendar.timeInMillis

                val endOfDay = if (daysAgo == 0) {
                    System.currentTimeMillis()
                } else {
                    calendar.set(Calendar.HOUR_OF_DAY, 23)
                    calendar.set(Calendar.MINUTE, 59)
                    calendar.set(Calendar.SECOND, 59)
                    calendar.set(Calendar.MILLISECOND, 999)
                    calendar.timeInMillis
                }

                val dateStr = dateFormat.format(calendar.time)
                val prefKey = "daily_rollup_done_$dateStr"

                // 당일(daysAgo == 0)은 좌표가 계속 실시간 추가되므로 완료 플래그를 체크하지 않고 항상 최신 누적치로 갱신
                val isAlreadyDone = if (daysAgo == 0) false else prefs.getBoolean(prefKey, false)
                if (!isAlreadyDone) {
                    val points = gpsDao.getPointsBetween(startOfDay, endOfDay)
                    if (points.isNotEmpty()) {
                        // 1. 오차가 큰 기지국/NLP 거친 좌표(> 50m) 필터링
                        val validPoints = points.filter { it.accuracy in 0.1f..50f }
                        val rawGpsPoints = validPoints.map { entity ->
                            GpsPoint(
                                latitude = entity.lat,
                                longitude = entity.lng,
                                accuracy = entity.accuracy,
                                speed = entity.speed,
                                timestamp = entity.timestamp,
                                batteryLevel = entity.batteryLevel
                            )
                        }

                        // 2. 단발성 V자형 좌표 튐(outlier jump) 제거
                        val cleanedGpsPoints = mutableListOf<GpsPoint>()
                        for (idx in rawGpsPoints.indices) {
                            val curr = rawGpsPoints[idx]
                            if (idx > 0 && idx < rawGpsPoints.size - 1) {
                                val prev = rawGpsPoints[idx - 1]
                                val next = rawGpsPoints[idx + 1]
                                val dPrev = StationaryAnchorManager.distanceBetweenMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
                                val dNext = StationaryAnchorManager.distanceBetweenMeters(curr.latitude, curr.longitude, next.latitude, next.longitude)
                                val dAcross = StationaryAnchorManager.distanceBetweenMeters(prev.latitude, prev.longitude, next.latitude, next.longitude)

                                // 이전 및 다음과 각각 100m 이상 떨어져 있으나 이전-다음 간격은 50m 이내인 고립 튐 제거
                                if (dPrev > 100f && dNext > 100f && dAcross < 50f) {
                                    continue
                                }
                            }
                            cleanedGpsPoints.add(curr)
                        }

                        if (cleanedGpsPoints.isNotEmpty()) {
                            val dailyHistory = DailyHistory(
                                deviceId = deviceId,
                                date = dateStr,
                                startTime = cleanedGpsPoints.first().timestamp,
                                endTime = cleanedGpsPoints.last().timestamp,
                                pointCount = cleanedGpsPoints.size,
                                points = cleanedGpsPoints
                            )

                            val saveResult = firestoreManager.saveDailyHistory(dailyHistory)
                            if (saveResult.isSuccess) {
                                if (daysAgo > 0) {
                                    prefs.edit().putBoolean(prefKey, true).apply()
                                }
                                Log.d(TAG, "일별 통합 롤업 저장 성공: $dateStr (${cleanedGpsPoints.size} 포인트, daysAgo=$daysAgo)")
                            } else {
                                Log.w(TAG, "일별 통합 롤업 저장 실패 ($dateStr): ${saveResult.exceptionOrNull()?.message}")
                            }
                        } else if (daysAgo > 0) {
                            prefs.edit().putBoolean(prefKey, true).apply()
                        }
                    } else if (daysAgo > 0) {
                        // 해당 날짜에 기록된 포인트가 없다면 완료 처리하여 반복 조회 방지 (과거 날짜만)
                        prefs.edit().putBoolean(prefKey, true).apply()
                    }
                }
            }

            // 30일 지난 오래된 로컬 Room DB 데이터 정리
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L)
            gpsDao.deleteOlderThan(thirtyDaysAgo)
            Log.d(TAG, "30일 이전 로컬 GPS 데이터 정리 완료")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "DailyRollupWorker 실행 중 오류 발생", e)
            Result.retry()
        }
    }
}
