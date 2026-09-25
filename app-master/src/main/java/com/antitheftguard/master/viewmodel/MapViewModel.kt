package com.antitheftguard.master.viewmodel

import androidx.lifecycle.ViewModel
import com.antitheftguard.master.repository.LocationRepository

class MapViewModel(private val repository: LocationRepository) : ViewModel() {
    val gpsBatches = repository.gpsBatches

    // TODO: add functions to filter data by date/time for the UI slider
}
