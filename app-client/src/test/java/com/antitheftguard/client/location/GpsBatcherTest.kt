package com.antitheftguard.client.location

import com.antitheftguard.core.model.GpsBatch
import com.antitheftguard.core.model.GpsPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `flushNow 호출 시 대기 중인 버퍼가 즉시 방출된다`() = runTest {
        val batcher = GpsBatcher("test_device")
        val batches = mutableListOf<GpsBatch>()
        
        val job = launch(UnconfinedTestDispatcher()) {
            batcher.batchFlow.collect {
                batches.add(it)
            }
        }
        
        // 이동 포인트 3개 추가 (12개 미만이므로 아직 방출 안 됨)
        for (i in 1..3) {
            batcher.addPoint(GpsPoint(37.5, 127.0, 5f, 3.0f, System.currentTimeMillis(), 100))
        }
        assertEquals(0, batches.size)

        val flushed = batcher.flushNow()
        assertTrue(flushed)
        assertEquals(1, batches.size)
        assertEquals(3, batches[0].points.size)
        job.cancel()
    }

    @Test
    fun `updateStationaryBuffer 호출 시 버퍼 내 정지 포인트들의 좌표가 수렴 앵커 좌표로 소급 개선된다`() = runTest {
        val batcher = GpsBatcher("test_device")
        val batches = mutableListOf<GpsBatch>()
        
        val job = launch(UnconfinedTestDispatcher()) {
            batcher.batchFlow.collect {
                batches.add(it)
            }
        }
        
        // 4개의 정지 포인트 추가 (약간의 GPS jitter가 있는 상태)
        for (i in 1..4) {
            batcher.addPoint(GpsPoint(37.5000 + i * 0.0001, 127.0000, 15f, 0.0f, System.currentTimeMillis() + i * 1000, 100))
        }

        // 앵커가 수렴되어 버퍼 내 이전 포인트들을 개선
        val convergedLat = 37.50025
        val convergedLng = 127.00010
        val convergedAcc = 4.5f
        batcher.updateStationaryBuffer(convergedLat, convergedLng, convergedAcc)

        // 5번째 포인트 추가로 배치 방출 트리거
        batcher.addPoint(GpsPoint(convergedLat, convergedLng, convergedAcc, 0.0f, System.currentTimeMillis() + 5000, 100))

        assertEquals(1, batches.size)
        assertEquals(5, batches[0].points.size)
        // 버퍼에 대기 중이던 1~4번째 포인트가 모두 개선된 앵커 좌표로 갱신되었는지 검증
        for (i in 0..3) {
            assertEquals(convergedLat, batches[0].points[i].latitude, 0.000001)
            assertEquals(convergedLng, batches[0].points[i].longitude, 0.000001)
            assertEquals(convergedAcc, batches[0].points[i].accuracy, 0.01f)
        }
        job.cancel()
    }
}
