package com.antitheftguard.client.location

import com.antitheftguard.core.model.GpsPoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StationaryAnchorManagerTest {

    private lateinit var manager: StationaryAnchorManager

    @Before
    fun setUp() {
        manager = StationaryAnchorManager(anchorRadiusMeters = 50f, breakoutDistanceMeters = 100f)
    }

    @Test
    fun `이동 중일 때는 포인트가 보정 없이 그대로 통과한다`() {
        val point = GpsPoint(37.5665, 126.9780, 5f, 3.5f, System.currentTimeMillis(), 80)
        val result = manager.processPoint(point, isMotionActive = true)

        assertFalse(result.isStationary)
        assertFalse(result.isBreakout)
        assertEquals(37.5665, result.refinedPoint.latitude, 0.000001)
        assertEquals(126.9780, result.refinedPoint.longitude, 0.000001)
        assertEquals(3.5f, result.refinedPoint.speed, 0.01f)
        assertFalse(manager.isStationaryConfirmed)
    }

    @Test
    fun `모션 센서가 정지(false)이고 속도가 낮으면 이중 확인을 거쳐 정지 앵커가 생성된다`() {
        val point = GpsPoint(37.5665, 126.9780, 10f, 0.2f, System.currentTimeMillis(), 80)
        val result = manager.processPoint(point, isMotionActive = false)

        assertTrue(result.isStationary)
        assertFalse(result.isBreakout)
        assertTrue(manager.isStationaryConfirmed)
        assertNotNull(manager.currentAnchor)
        assertEquals(0f, result.refinedPoint.speed, 0.01f)
        assertEquals(37.5665, manager.currentAnchor!!.latitude, 0.000001)
        assertEquals(126.9780, manager.currentAnchor!!.longitude, 0.000001)
    }

    @Test
    fun `정지 확정 상태에서는 반경 내의 지터(Jitter) 포인트가 앵커 가중 수렴 좌표로 고정된다`() {
        val baseTime = System.currentTimeMillis()
        val p1 = GpsPoint(37.566500, 126.978000, 10f, 0.0f, baseTime, 80)
        manager.processPoint(p1, isMotionActive = false)

        // 반경 약 15m 지터 포인트 수신 (accuracy 8m)
        val p2 = GpsPoint(37.566600, 126.978050, 8f, 0.5f, baseTime + 10_000, 80)
        val r2 = manager.processPoint(p2, isMotionActive = false)

        assertTrue(r2.isStationary)
        assertEquals(0f, r2.refinedPoint.speed, 0.01f)
        // 두 포인트의 가중 평균으로 수렴된 앵커 좌표가 반환되어야 함
        assertEquals(manager.currentAnchor!!.latitude, r2.refinedPoint.latitude, 0.000001)
        assertEquals(manager.currentAnchor!!.longitude, r2.refinedPoint.longitude, 0.000001)
        assertEquals(2, manager.currentAnchor!!.sampleCount)
    }

    @Test
    fun `오차가 큰 이상치(accuracy 70m)는 앵커 좌표를 왜곡시키지 않는다`() {
        val baseTime = System.currentTimeMillis()
        val p1 = GpsPoint(37.566500, 126.978000, 10f, 0.0f, baseTime, 80)
        manager.processPoint(p1, isMotionActive = false)
        val initialLat = manager.currentAnchor!!.latitude
        val initialLng = manager.currentAnchor!!.longitude

        // 정확도 70m의 노이즈 포인트 수신
        val p2 = GpsPoint(37.566700, 126.978200, 70f, 0.8f, baseTime + 10_000, 80)
        val r2 = manager.processPoint(p2, isMotionActive = false)

        assertTrue(r2.isStationary)
        // 이상치는 누적 가중치 계산에서 제외되므로 앵커 좌표가 유지됨
        assertEquals(initialLat, manager.currentAnchor!!.latitude, 0.000001)
        assertEquals(initialLng, manager.currentAnchor!!.longitude, 0.000001)
        assertEquals(1, manager.currentAnchor!!.sampleCount)
    }

    @Test
    fun `100m 이상 실제 이동 시 정지 앵커에서 탈출(Breakout)한다`() {
        val baseTime = System.currentTimeMillis()
        val p1 = GpsPoint(37.566500, 126.978000, 10f, 0.0f, baseTime, 80)
        manager.processPoint(p1, isMotionActive = false)
        assertTrue(manager.isStationaryConfirmed)

        // 약 200m 떨어진 위치로 이동한 포인트 (lat 차이 약 0.002 = 약 220m)
        val pFar = GpsPoint(37.568500, 126.978000, 10f, 2.5f, baseTime + 30_000, 80)
        val rFar = manager.processPoint(pFar, isMotionActive = false)

        assertFalse(rFar.isStationary)
        assertTrue(rFar.isBreakout)
        assertFalse(manager.isStationaryConfirmed)
        assertNull(manager.currentAnchor)
        assertEquals(37.568500, rFar.refinedPoint.latitude, 0.000001)
    }

    @Test
    fun `하드웨어 모션 센서가 움직임(isMotionActive=true)을 감지하면 즉시 앵커가 해제된다`() {
        val point = GpsPoint(37.5665, 126.9780, 10f, 0.2f, System.currentTimeMillis(), 80)
        manager.processPoint(point, isMotionActive = false)
        assertTrue(manager.isStationaryConfirmed)

        manager.updateMotionState(isMotionActive = true)
        assertFalse(manager.isStationaryConfirmed)
        assertNull(manager.currentAnchor)
    }

    @Test
    fun `Haversine 거리 계산 함수가 정확한 미터 단위 거리를 반환한다`() {
        // 서울시청 (37.5665, 126.9780) 에서 광화문광장 (37.5759, 126.9768) 까지 대략 1km (약 1050m)
        val distance = StationaryAnchorManager.distanceBetweenMeters(
            37.5665, 126.9780,
            37.5759, 126.9768
        )
        assertTrue("Expected ~1050m, got $distance", distance in 1000f..1100f)
    }
}
