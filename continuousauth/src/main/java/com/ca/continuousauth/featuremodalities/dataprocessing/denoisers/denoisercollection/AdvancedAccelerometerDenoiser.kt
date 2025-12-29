import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.utils.Logger
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

class AdvancedAccelerometerDenoiser(
    private val baseHighPassAlpha: Float = 0.6f,
    private val lowPassAlpha: Float = 0.4f,
    private val madMultiplier: Float = 10.0f,
    private val gainFactor: Float = 3f,
    private val historySize: Int = 15
) : SensorDenoiser {

    private val intensityHistory = ArrayDeque<Float>()

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        if (window.size < 5) return window

        val startTime = System.nanoTime()

        // 1. Gravity removal
        val gravityRemoved = removeGravity(window, baseHighPassAlpha)

        // 2. Compute window intensity
        val intensity = computeWindowIntensity(gravityRemoved)
        updateIntensityHistory(intensity)

        // 3. Compute dynamic intensity threshold
        val threshold = computeDynamicIntensityThreshold()

        // 4. Adaptive denoising
        val denoised = if (intensity < threshold) {
            // Low intensity → amplify micro-movements, minimal filtering
            adaptiveAmplify(gravityRemoved, gainFactor)
        } else {
            // High intensity → reduce spikes, smooth signal
            val spikeReduced = removeMADSpikes(gravityRemoved, madMultiplier)
            dualPassSmooth(spikeReduced, lowPassAlpha)
        }

        val endTime = System.nanoTime()
//        Logger.d("MicroMovementAccelerometerDenoiser -> denoiseWindow executed in %.3f ms"
//            .format((endTime - startTime) / 1_000_000.0))

        return denoised
    }

    private fun removeGravity(window: List<Pair<Long, List<Float>>>, alpha: Float): List<Pair<Long, List<Float>>> {
        val numAxes = window[0].second.size
        val gravity = MutableList(numAxes) { window[0].second[it] }

        return window.map { (ts, values) ->
            val highPass = values.mapIndexed { i, v ->
                gravity[i] = alpha * gravity[i] + (1 - alpha) * v
                v - gravity[i]
            }
            ts to highPass
        }
    }

    private fun computeWindowIntensity(window: List<Pair<Long, List<Float>>>): Float {
        val flattened = window.flatMap { it.second }
        return flattened.map { abs(it) }.average().toFloat()
    }

    private fun updateIntensityHistory(intensity: Float) {
        if (intensityHistory.size >= historySize) intensityHistory.removeFirst()
        intensityHistory.addLast(intensity)
    }

    private fun computeDynamicIntensityThreshold(): Float {
        if (intensityHistory.size < historySize) return 0.02f // safe minimum
        val sorted = intensityHistory.sorted()
        val median = sorted[sorted.size / 2]
        val mad = sorted.map { abs(it - median) }.sorted()[sorted.size / 2]
        return median + 1.5f * mad // threshold = median + 1.5 * MAD
    }

    private fun removeMADSpikes(
        window: List<Pair<Long, List<Float>>>,
        multiplier: Float
    ): List<Pair<Long, List<Float>>> {
        val numAxes = window[0].second.size
        val result = window.map { it.second.toMutableList() }

        for (i in 0 until numAxes) {
            val axisValues = window.map { it.second[i] }
            val median = axisValues.sorted()[axisValues.size / 2]
            val mad = axisValues.map { abs(it - median) }.sorted()[axisValues.size / 2]
            val threshold = (mad + 1e-6f) * multiplier

            for (j in window.indices) {
                if (abs(result[j][i] - median) > threshold) {
                    val neighbors = listOfNotNull(
                        result.getOrNull(j - 1)?.get(i),
                        result.getOrNull(j + 1)?.get(i)
                    )
                    result[j][i] = if (neighbors.isNotEmpty()) neighbors.average().toFloat() else median
                }
            }
        }
        return window.mapIndexed { idx, pair -> pair.first to result[idx] }
    }

    private fun dualPassSmooth(window: List<Pair<Long, List<Float>>>, alpha: Float): List<Pair<Long, List<Float>>> {
        val numAxes = window[0].second.size
        val forward = MutableList(window.size) { MutableList(numAxes) { 0f } }

        val prev = window[0].second.toMutableList()
        window.forEachIndexed { idx, (_, values) ->
            val smoothed = values.mapIndexed { i, v ->
                prev[i] = alpha * v + (1 - alpha) * prev[i]
                prev[i]
            }
            forward[idx] = smoothed.toMutableList()
        }

        val backward = MutableList(window.size) { MutableList(numAxes) { 0f } }
        val prevB = forward.last().toMutableList()
        for (i in window.indices.reversed()) {
            backward[i] = forward[i].mapIndexed { j, v ->
                prevB[j] = alpha * v + (1 - alpha) * prevB[j]
                prevB[j]
            }.toMutableList()
        }

        return window.mapIndexed { idx, pair -> pair.first to backward[idx] }
    }

    private fun adaptiveAmplify(window: List<Pair<Long, List<Float>>>, gain: Float): List<Pair<Long, List<Float>>> {
        val flattened = window.flatMap { it.second }
        val std = sqrt(flattened.map { it.pow(2) }.average()).coerceAtLeast(1e-6)
        val adaptiveGain = gain / std

        return window.map { (ts, values) ->
            ts to values.map { v -> (adaptiveGain * tanh(v.toDouble())).toFloat() }
        }
    }
}
