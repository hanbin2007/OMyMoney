package app.omymoney.core.recognition

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class NoOpRecognitionEnhancer : RecognitionEnhancer {
    override suspend fun enhance(request: EnhancementRequest): EnhancementResult {
        currentCoroutineContext().ensureActive()
        return EnhancementResult.Unavailable
    }
}
