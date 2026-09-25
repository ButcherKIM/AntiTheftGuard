package com.antitheftguard.client.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.antitheftguard.client.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 오래된 GPS 데이터를 주기적으로 삭제하는 워커 (Firestore 및 Room DB 대상) */
class DataCleanupWorker(
    appContext: Context, 
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val threeDaysAgo = System.currentTimeMillis() - (3 * 24 * 60 * 60 * 1000L)
            
            // Room DB 삭제
            val db = AppDatabase.getInstance(applicationContext)
            db.gpsPointDao().deleteOlderThan(threeDaysAgo)
            
            // TODO: FirestoreManager를 통한 삭제 호출
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
