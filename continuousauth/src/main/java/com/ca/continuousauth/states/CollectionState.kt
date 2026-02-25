package com.ca.continuousauth.states

data class CollectionState(
        val sensorCollectedList: List<List<Float>>,
        val fusionCollectedList: List<List<Float>> = emptyList(),
        val completed: Boolean = false
)
