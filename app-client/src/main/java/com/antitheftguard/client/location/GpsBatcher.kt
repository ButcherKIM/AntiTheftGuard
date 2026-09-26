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
        // 정지 중: 5개 포인트 또는 5분 경과 시 전송 (네트워크 및 CPU 절전)
        val countLimit = if (isMoving) 12 else 5
        val timeLimit = if (isMoving) 60_000L else 300_000L

        if (buffer.size >= countLimit || now - batchStartTime.get() >= timeLimit) {
            val batch = GpsBatch(deviceId, batchStartTime.get(), now, buffer.toList())
            _batchFlow.tryEmit(batch)
            buffer.clear()
            batchStartTime.set(now)
        }
    }
}
