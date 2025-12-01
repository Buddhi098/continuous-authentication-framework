package com.ca.authframework.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ca.continuousauth.ContinuousAuth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CollectionViewModel(private val auth: ContinuousAuth) : ViewModel() {

    val isCollecting: StateFlow<Boolean> = auth.isCollecting.stateIn(
        viewModelScope, SharingStarted.Eagerly, false
    )

    val progress: StateFlow<Float> = auth.progress.stateIn(
        viewModelScope, SharingStarted.Eagerly, 0f
    )

    // Collected count tracked dynamically
    var collectedCount by mutableStateOf(auth.getCollectedSampleCount())
        private set

    var isPaused by mutableStateOf(false)
        private set

    init {
        // Update collectedCount dynamically as new samples come in
        viewModelScope.launch {
            auth.collectedSamples.collect {
                collectedCount = auth.getCollectedSampleCount()
            }
        }
    }

    fun startCollection(targetSamples: Int) {
        isPaused = false
        auth.startCollecting(targetSamples)
        // Initialize count immediately
        collectedCount = auth.getCollectedSampleCount()
    }

    fun pauseCollection() {
        auth.pauseCollecting()
        isPaused = true
    }

    fun resumeCollection() {
        auth.resumeCollecting()
        isPaused = false
        // Update count immediately on resume
        collectedCount = auth.getCollectedSampleCount()
    }

    fun stopCollection() {
        auth.stopCollecting()
        isPaused = false
        collectedCount = 0
    }
}
