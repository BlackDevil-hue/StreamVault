package com.example.data.repository

import com.example.data.network.ArchiveOrgExtractor
import com.example.data.network.DirectMediaExtractor
import com.example.data.network.SocialYtDlpExtractor
import com.example.data.network.Html5OpenMediaExtractor
import com.example.data.network.UrlSecurityValidator
import com.example.data.network.UrlValidationResult
import com.example.data.network.WikimediaExtractor
import com.example.data.network.YouTubeExtractor
import com.example.domain.repository.VideoAnalyzerRepository
import com.example.domain.source.ExtractionOutcome
import com.example.domain.source.VideoSourceExtractor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoAnalyzerRepositoryImpl @Inject constructor(
    private val urlValidator: UrlSecurityValidator,
    archiveOrgExtractor: ArchiveOrgExtractor,
    wikimediaExtractor: WikimediaExtractor,
    directMediaExtractor: DirectMediaExtractor,
    html5OpenMediaExtractor: Html5OpenMediaExtractor,
    youtubeExtractor: YouTubeExtractor,
    socialYtDlpExtractor: SocialYtDlpExtractor
) : VideoAnalyzerRepository {

    private val extractors: List<VideoSourceExtractor> = listOf(
        archiveOrgExtractor,
        socialYtDlpExtractor,
        youtubeExtractor,
        wikimediaExtractor,
        directMediaExtractor,
        html5OpenMediaExtractor
    ).sortedBy { it.priority }

    override suspend fun analyzeUrl(rawUrl: String): ExtractionOutcome {
        return when (val validation = urlValidator.validateAndNormalize(rawUrl)) {
            is UrlValidationResult.Invalid -> ExtractionOutcome.Failure(validation.error)
            is UrlValidationResult.Valid -> {
                val extractor = extractors.firstOrNull { it.canHandle(validation.httpsUrl) }
                    ?: return ExtractionOutcome.Failure(com.example.domain.model.DownloadError.UnsupportedSource())
                extractor.extract(validation.httpsUrl)
            }
        }
    }

    override fun extractFirstUrlFromText(text: String?): String? =
        urlValidator.extractFirstUrlFromText(text)
}
