package com.ca.authframework.service
import com.ca.continuousauth.states.EnrollmentResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object EnrollmentResultBus {
    private val _results = MutableSharedFlow<EnrollmentResult>(extraBufferCapacity = 1)
    val results = _results.asSharedFlow()

    suspend fun emit(result: EnrollmentResult) {
        _results.emit(result)
    }
}