package com.antitheftguard.client.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antitheftguard.client.service.GpsLoggingService
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * OS 하드웨어 센서 허브에서 전달된 Activity Transition 이벤트를 수신하여
 * GpsLoggingService의 모션 상태를 갱신하는 리시버.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActivityTransitionRcvr"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return

        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (event in result.transitionEvents) {
            val isEnteringStill = (event.activityType == DetectedActivity.STILL && 
                                   event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
            val isExitingStill = (event.activityType == DetectedActivity.STILL && 
                                  event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT)
            val isEnteringMoving = (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER &&
                                    (event.activityType == DetectedActivity.IN_VEHICLE ||
                                     event.activityType == DetectedActivity.WALKING ||
                                     event.activityType == DetectedActivity.RUNNING ||
                                     event.activityType == DetectedActivity.ON_BICYCLE))

            if (isEnteringStill) {
                Log.d(TAG, "모션 이벤트: 정지(STILL) 진입 감지 -> 저전력 스탠바이 전환")
                GpsLoggingService.onMotionStateChanged(context, isMoving = false)
            } else if (isExitingStill || isEnteringMoving) {
                Log.d(TAG, "모션 이벤트: 이동(Type=${event.activityType}) 감지 -> 초정밀 5초 GPS 가동")
                GpsLoggingService.onMotionStateChanged(context, isMoving = true)
            }
        }
    }
}
