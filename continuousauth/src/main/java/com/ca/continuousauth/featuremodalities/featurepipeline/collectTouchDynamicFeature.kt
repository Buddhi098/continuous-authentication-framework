package com.ca.continuousauth.featuremodalities.featurepipeline

import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/** Full pipeline to process touch dynamics features as a Flow. */
fun collectTouchDynamicFeature(
        collector: () -> Flow<Pair<Long, List<Float>>>,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
): Flow<Pair<Long, List<Float>>> {

    return collector().flowOn(dispatcher).catch { ex ->
        Logger.e("Error in touch dynamics feature flow", ex)
    }
}
