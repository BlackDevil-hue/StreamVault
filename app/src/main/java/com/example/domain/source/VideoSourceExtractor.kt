package com.example.domain.source

import com.example.domain.model.DownloadError
import com.example.domain.model.VideoAnalysisResult

sealed class ExtractionOutcome {
    data class Success(val result: VideoAnalysisResult) : ExtractionOutcome()
    data class Failure(val error: DownloadError) : ExtractionOutcome()
}

interface VideoSourceExtractor {
    val priority: Int
    fun canHandle(url: String): Boolean
    suspend fun extract(url: String): ExtractionOutcome
}
