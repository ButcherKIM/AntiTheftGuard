package com.antitheftguard.master.repository

import com.antitheftguard.core.firebase.FirestoreManager
import com.antitheftguard.core.model.GpsBatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LocationRepository(private val firestoreManager: FirestoreManager) {
    
    private val _gpsBatches = MutableStateFlow<List<GpsBatch>>(emptyList())
    val gpsBatches: StateFlow<List<GpsBatch>> = _gpsBatches

    suspend fun fetchLocationHistory(deviceId: String, timeRangeMillis: Long = 3 * 24 * 60 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        val result = firestoreManager.getLocationHistory(deviceId, now - timeRangeMillis, now)
        if (result.isSuccess) {
            _gpsBatches.value = result.getOrNull() ?: emptyList()
        }
    }
}
