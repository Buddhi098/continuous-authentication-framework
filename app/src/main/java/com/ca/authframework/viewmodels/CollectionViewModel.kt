package com.ca.authframework.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ca.continuousauth.ContinuousAuth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
class CollectionViewModel(
    private val auth: ContinuousAuth
) : ViewModel() {

    val isCollecting: StateFlow<Boolean> = auth.isCollecting

    val progress: StateFlow<Float> = auth.progress

    /** ✅ Auto-updates immediately when app opens */
    val collectedSampleCount: StateFlow<Int> = auth.collectedSamplesCount

    val isPaused: StateFlow<Boolean> = auth.isPaused

    fun startCollection(targetSamples: Int) {
        auth.startCollecting()
    }

    fun pauseCollection() {
        auth.pauseCollecting()
    }

    fun resumeCollection() {
        auth.resumeCollecting()
    }

    fun clearCollection() {
        auth.clearCollection()
    }
}
