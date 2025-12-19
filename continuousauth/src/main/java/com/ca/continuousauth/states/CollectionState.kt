package com.ca.continuousauth.states

data class CollectionState(
    val collectedList: List<List<Float>>,
    val completed: Boolean = false
)
