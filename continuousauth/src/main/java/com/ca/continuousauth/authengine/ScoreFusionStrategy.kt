package com.ca.continuousauth.authengine

interface ScoreFusionStrategy {
    /**
     * Fuses the sensor-only score and the touch+sensor fusion score into a final score.
     *
     * @param sensorScore The score from the SensorOnlyModel
     * @param fusionScore The score from the TouchSensorFusionModel (can be null if no touch
     * occurred)
     * @return The final fused score
     */
    fun fuseScores(sensorScore: Float, fusionScore: Float?): Float
}

class WeightedScoreFusionStrategy(
        private val sensorWeight: Float,
        private val fusionWeight: Float
) : ScoreFusionStrategy {

    init {
        // Allow a small delta for floating point comparison if necessary, but exact 1.0f is
        // preferred here
        require(sensorWeight in 0.0f..1.0f) { "sensorWeight must be between 0.0 and 1.0" }
        require(fusionWeight in 0.0f..1.0f) { "fusionWeight must be between 0.0 and 1.0" }
        require(Math.abs((sensorWeight + fusionWeight) - 1.0f) < 0.0001f) {
            "Weights must sum to 1.0"
        }
    }

    override fun fuseScores(sensorScore: Float, fusionScore: Float?): Float {
        if (fusionScore == null) {
            // When no touch event is available, rely entirely on the sensor score
            return sensorScore
        }

        return (sensorWeight * sensorScore) + (fusionWeight * fusionScore)
    }
}
