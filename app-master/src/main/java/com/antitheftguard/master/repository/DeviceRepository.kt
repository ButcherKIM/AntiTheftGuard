package com.antitheftguard.master.repository

import com.antitheftguard.core.firebase.FirestoreManager
import com.antitheftguard.core.model.DeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DeviceRepository(private val firestoreManager: FirestoreManager) {
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices

    suspend fun loadDevices(userId: String) {
        val result = firestoreManager.getUserDevices(userId)
        if (result.isSuccess) {
            _devices.value = result.getOrNull() ?: emptyList()
        }
    }
    
    // TODO: addSnapshotListener를 통한 실시간 배터리 및 마지막 접속 시간 업데이트 로직
}
