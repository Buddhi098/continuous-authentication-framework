package com.ca.authframework.features.evalhistory

import java.util.UUID

/** Represents the evaluator's role during an evaluation session. */
enum class EvaluatorLabel {
    IMPOSTOR,
    LEGITIMATE
}

/**
 * Data class representing a single evaluation record. Contains evaluator information and
 * authentication metrics.
 */
data class EvaluationRecord(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val evaluatorName: String,
        val evaluatorLabel: EvaluatorLabel,
        val finalConfidence: Double,
        val samplesProcessed: Int,
        val averageScore: Double = 0.0,
        val medianScore: Double = 0.0,
        val overallWindowAccuracy: Double = 0.0
)
