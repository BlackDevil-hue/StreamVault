package com.example.ui.state

import com.example.domain.model.VideoAnalysisResult
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalysisSessionCache @Inject constructor() {
    private val cache = ConcurrentHashMap<String, VideoAnalysisResult>()

    fun put(url: String, result: VideoAnalysisResult) {
        cache[url] = result
        cache[result.normalizedUrl] = result
    }

    fun get(url: String): VideoAnalysisResult? = cache[url]
}
