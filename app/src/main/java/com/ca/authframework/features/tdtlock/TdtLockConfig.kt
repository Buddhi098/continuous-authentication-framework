package com.ca.authframework.features.tdtlock

/**
 * Configuration for TDT (Trust Decision Threshold) Lock feature.
 *
 * @param windowSize Number of authentication results to accumulate before computing a window
 * decision
 * @param lockThreshold TDT accuracy below this threshold triggers lock
 * @param unlockThreshold TDT accuracy at or above this threshold triggers unlock
 * @param windowMajorityThreshold Fraction of authenticated results in a window to consider it
 * authenticated
 */
data class TdtLockConfig(
        val windowSize: Int = 10,
        val lockThreshold: Float = 0.5f,
        val unlockThreshold: Float = 0.6f,
        val windowMajorityThreshold: Float = 0.5f
) {
    init {
        require(windowSize > 0) { "windowSize must be positive" }
        require(lockThreshold in 0f..1f) { "lockThreshold must be between 0 and 1" }
        require(unlockThreshold in 0f..1f) { "unlockThreshold must be between 0 and 1" }
        require(lockThreshold <= unlockThreshold) { "lockThreshold must be <= unlockThreshold" }
        require(windowMajorityThreshold in 0f..1f) {
            "windowMajorityThreshold must be between 0 and 1"
        }
    }
}
