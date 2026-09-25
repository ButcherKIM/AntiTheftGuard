package com.antitheftguard.master.viewmodel

import androidx.lifecycle.ViewModel
import com.antitheftguard.master.repository.DeviceRepository

class DashboardViewModel(private val repository: DeviceRepository) : ViewModel() {
    val devices = repository.devices
    
    // TODO: fetch list of paired devices when User logs in via Google
}
