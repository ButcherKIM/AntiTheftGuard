package com.antitheftguard.client.location

import com.antitheftguard.core.model.GpsPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GpsBatcherTest {
    @Test
    fun `포인트가 12개 모이면 배치를 방출한다`() = runTest {
        val batcher = GpsBatcher("test_device")
        var batchReceived = false
        
        val job = launch {
            val batch = batcher.batchFlow.first()
            assertEquals(12, batch.points.size)
            batchReceived = true
        }
        
        for (i in 1..12) {
            batcher.addPoint(GpsPoint(0.0, 0.0, 0f, 0f, System.currentTimeMillis(), 100))
        }
        job.join()
        assert(batchReceived)
    }
}
