package com.antitheftguard.client.location

import com.antitheftguard.core.model.GpsBatch
import com.antitheftguard.core.model.GpsPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/** GPS 포인트를 수집하여 일정 크기 또는 시간이 되면 배치(Batch)로 변환합니다. */
class GpsBatcher(private val deviceId: String) {
    private val buffer = mutableListOf<GpsPoint>()
    private val _batchFlow = MutableSharedFlow<GpsBatch>(replay = 1, extraBufferCapacity = 10)
    val batchFlow = _batchFlow.asSharedFlow()
    private var batchStartTime = AtomicLong(System.currentTimeMillis())

    @Synchronized
    fun addPoint(point: GpsPoint) {
        buffer.add(point)
        val now = System.currentTimeMillis()
        val isMoving = point.speed > 1.5f
        // 이동 중: 12개 포인트 또는 1분 경과 시 전송 (초정밀 궤적)
        // 정지 중: 5개 포인트 또는 10분 경과 시 전송 (극저전력 모뎀 절전)
        val countLimit = if (isMoving) 12 else 5
        val timeLimit = if (isMoving) 60_000L else 600_000L

        if (buffer.size >= countLimit || now - batchStartTime.get() >= timeLimit) {
            emitBatch(now)
        }
    }

    /**
     * 지정된 시간(이동 1분, 정지 10분)이 경과한 미전송 버퍼가 있다면 즉시 배치로 방출합니다.
     * GpsLoggingService의 주기적 타이커(Watchdog)에서 호출됩니다.
     */
    @Synchronized
    fun flushIfTimeExceeded(): Boolean {
        if (buffer.isEmpty()) return false
        val now = System.currentTimeMillis()
        val hasMovingPoint = buffer.any { it.speed > 1.5f }
        val timeLimit = if (hasMovingPoint) 60_000L else 600_000L

        if (now - batchStartTime.get() >= timeLimit) {
            emitBatch(now)
            return true
        }
        return false
    }

    /**
     * 버퍼에 대기 중인 모든 포인트를 즉시 배치로 방출합니다.
     */
    @Synchronized
    fun flushNow(): Boolean {
        if (buffer.isEmpty()) return false
        val now = System.currentTimeMillis()
        emitBatch(now)
        return true
    }

    private fun emitBatch(endTime: Long) {
        val startTime = if (buffer.isNotEmpty()) buffer.first().timestamp else batchStartTime.get()
        val batch = GpsBatch(deviceId, startTime, endTime, buffer.toList())
        _batchFlow.tryEmit(batch)
        buffer.clear()
        batchStartTime.set(endTime)
    }

    /**
     * 버퍼 내에 아직 발신되지 않고 대기 중인 정지 포인트(속도 0)들을
     * 최신 수렴된 앵커 좌표 및 오차로 소급 개선(Refinement)합니다.
     */
    @Synchronized
    fun updateStationaryBuffer(anchorLat: Double, anchorLng: Double, anchorAcc: Float) {
        for (i in buffer.indices) {
            val p = buffer[i]
            if (p.speed == 0f) {
                buffer[i] = p.copy(
                    latitude = anchorLat,
                    longitude = anchorLng,
                    accuracy = anchorAcc
                )
            }
        }
    }
}
