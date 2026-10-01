package com.antitheftguard.client.motion

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * Google Play Services의 Activity Transition API를 활용하여,
 * 하드웨어 센서 허브 수준에서 초저전력 모션 상태(정지 vs 이동)를 감지 및 등록하는 관리자.
 */
object ActivityTransitionManager {
    private const val TAG = "ActivityTransitionMgr"
    private const val REQUEST_CODE_TRANSITION = 1002

    private fun getPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE_TRANSITION, intent, flags)
    }

    private fun createTransitionRequest(): ActivityTransitionRequest {
        val transitions = mutableListOf<ActivityTransition>()

        // STILL (정지 상태 진입 및 이탈)
        transitions.add(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        )
        transitions.add(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )

        // 이동 상태 진입 (차량 탑승, 걷기, 달리기, 자전거)
        val movingActivities = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.WALKING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.RUNNING
        )

        for (activity in movingActivities) {
            transitions.add(
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            )
        }

        return ActivityTransitionRequest(transitions)
    }

    @SuppressLint("MissingPermission")
    fun startTracking(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "ACTIVITY_RECOGNITION 권한이 없어 모션 감지 등록을 건너뜁니다.")
                return
            }
        }

        val client = ActivityRecognition.getClient(context)
        val request = createTransitionRequest()
        val pendingIntent = getPendingIntent(context)

        client.requestActivityTransitionUpdates(request, pendingIntent)
            .addOnSuccessListener {
                Log.d(TAG, "초저전력 Activity Transition 등록 성공 (하드웨어 센서 허브 연동)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Activity Transition 등록 실패: ${e.message}")
            }
    }

    @SuppressLint("MissingPermission")
    fun stopTracking(context: Context) {
        try {
            val client = ActivityRecognition.getClient(context)
            val pendingIntent = getPendingIntent(context)
            client.removeActivityTransitionUpdates(pendingIntent)
                .addOnSuccessListener {
                    Log.d(TAG, "Activity Transition 감지 해제 완료")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Activity Transition 감지 해제 실패: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "stopTracking 중 예외 발생", e)
        }
    }
}
