package com.ca.authframework.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ca.continuousauth.ContinuousAuth
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

class TrainingViewModel(private val auth: ContinuousAuth) : ViewModel() {

    // Samples synced with ContinuousAuth
    var collectedSamples = mutableStateListOf<List<Float>>()
        private set

    var isTraining by mutableStateOf(false)
        private set

    var trainingStatus by mutableStateOf("")
        private set

    var trainingProgress by mutableStateOf(0f)
        private set

    var errorMessage by mutableStateOf("")
        private set

    init {
        // Load existing collected samples on startup
        collectedSamples.addAll(auth.getAllCollectedSamples())

        // Observe new samples
        viewModelScope.launch {
            auth.collectedSamples.collect { sample ->
                collectedSamples.add(sample)
            }
        }

        // Observe training progress and status
        viewModelScope.launch {
            auth.trainingProgress.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)
                .collect { progress ->
                    trainingProgress = progress
                }
        }

        viewModelScope.launch {
            auth.trainingStatus.stateIn(viewModelScope, SharingStarted.Eagerly, "")
                .collect { status ->
                    trainingStatus = status
                }
        }

        viewModelScope.launch {
            auth.isTraining.stateIn(viewModelScope, SharingStarted.Eagerly, false)
                .collect { running ->
                    isTraining = running
                }
        }
    }

    fun startTraining() {
        errorMessage = ""
        if (collectedSamples.isEmpty()) {
            errorMessage = "No samples available for training"
            return
        }

        auth.startTrainingModel {
            if (!auth.isTraining.value) {
                trainingStatus = "Training completed"
            }
        }
    }

    fun stopTraining() {
        auth.stopTraining()
    }
}
