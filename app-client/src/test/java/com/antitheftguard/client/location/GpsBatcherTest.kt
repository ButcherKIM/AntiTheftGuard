package com.antitheftguard.client.location

import com.antitheftguard.core.model.GpsBatch
import com.antitheftguard.core.model.GpsPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GpsBatcherTest {
    @Test
    fun `이동 중일 때는 12개 포인트가 모이면 배치를 방출한다`() = runTest {
        val batcher = GpsBatcher("test_device")
        val batches = mutableListOf<GpsBatch>()
        
        val job = launch(UnconfinedTestDispatcher()) {
            batcher.batchFlow.collect {
                batches.add(it)
            }
        }
        
        for (i in 1..12) {
            batcher.addPoint(GpsPoint(37.5, 127.0, 5f, 3.0f, System.currentTimeMillis(), 100))
        }

        assertEquals(1, batches.size)
        assertEquals(12, batches[0].points.size)
        job.cancel()
    }

    @Test
    fun `정지 중일 때는 5개 포인트가 모이면 절전 배치를 방출한다`() = runTest {
        val batcher = GpsBatcher("test_device")
        val batches = mutableListOf<GpsBatch>()
        
        val job = launch(UnconfinedTestDispatcher()) {
            batcher.batchFlow.collect {
                batches.add(it)
            }
        }
        
        for (i in 1..5) {
            batcher.addPoint(GpsPoint(37.5, 127.0, 5f, 0.0f, System.currentTimeMillis(), 100))
        }

        assertEquals(1, batches.size)
        assertEquals(5, batches[0].points.size)
        job.cancel()
    }
}
