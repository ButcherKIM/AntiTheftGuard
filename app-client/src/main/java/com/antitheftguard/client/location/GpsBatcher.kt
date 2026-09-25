package com.antitheftguard.client.location

import com.antitheftguard.core.model.GpsBatch
import com.antitheftguard.core.model.GpsPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/** GPS 포인트를 수집하여 일정 크기 또는 시간이 되면 배치(Batch)로 변환합니다. */
class GpsBatcher(private val deviceId: String) {
    private val buffer = mutableListOf<GpsPoint>()
    private val _batchFlow = MutableSharedFlow<GpsBatch>(extraBufferCapacity = 10)
    val batchFlow = _batchFlow.asSharedFlow()
    private var batchStartTime = AtomicLong(System.currentTimeMillis())

    @Synchronized
    fun addPoint(point: GpsPoint) {
        buffer.add(point)
        val now = System.currentTimeMillis()
        if (buffer.size >= 12 || now - batchStartTime.get() >= 60_000L) {
            val batch = GpsBatch(deviceId, batchStartTime.get(), now, buffer.toList())
            _batchFlow.tryEmit(batch)
            buffer.clear()
            batchStartTime.set(now)
        }
    }
}
