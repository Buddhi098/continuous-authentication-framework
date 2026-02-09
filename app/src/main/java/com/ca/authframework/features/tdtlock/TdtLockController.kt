package com.ca.authframework.features.tdtlock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Controller interface for TDT-based lock/unlock decisions. */
interface TdtLockController {
    /** Current lock state */
    val isLocked: StateFlow<Boolean>

    /**
     * Evaluate TDT accuracy and update lock state.
     * @param accuracy Current TDT accuracy (0.0 to 1.0)
     * @param totalWindows Total number of windows evaluated
     */
    fun evaluateTdt(accuracy: Float, totalWindows: Int)

    /** Reset lock state */
    fun reset()
}

/**
 * Default implementation of [TdtLockController].
 *
 * Lock logic:
 * - Lock when TDT accuracy drops below [TdtLockConfig.lockThreshold]
 * - Unlock when TDT accuracy recovers to [TdtLockConfig.unlockThreshold] or above
 */
class DefaultTdtLockController(private val config: TdtLockConfig = TdtLockConfig()) :
        TdtLockController {

    private val _isLocked = MutableStateFlow(false)
    override val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    override fun evaluateTdt(accuracy: Float, totalWindows: Int) {
        // Skip evaluation if no windows have been processed
        if (totalWindows == 0) return

        // Lock if trust drops below threshold
        if (accuracy < config.lockThreshold) {
            _isLocked.value = true
        }

        // Unlock if trust recovers above threshold
        if (_isLocked.value && accuracy >= config.unlockThreshold) {
            _isLocked.value = false
        }
    }

    override fun reset() {
        _isLocked.value = false
    }
}
