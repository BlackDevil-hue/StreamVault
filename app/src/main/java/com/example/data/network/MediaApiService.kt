package com.example.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class ArchiveMetadataResponse(
    val server: String? = null,
    val dir: String? = null,
    val metadata: ArchiveItemMetadata? = null,
    val files: List<ArchiveFileItem>? = null
)

@JsonClass(generateAdapter = true)
data class ArchiveItemMetadata(
    val title: String? = null,
    val creator: String? = null,
    val mediatype: String? = null
)

@JsonClass(generateAdapter = true)
data class ArchiveFileItem(
    val name: String? = null,
    val format: String? = null,
    val size: String? = null,
    val length: String? = null,
    val height: String? = null,
    val width: String? = null
)

@JsonClass(generateAdapter = true)
data class WikimediaApiResponse(
    val query: WikimediaQuery? = null
)

@JsonClass(generateAdapter = true)
data class WikimediaQuery(
    val pages: Map<String, WikimediaPage>? = null
)

@JsonClass(generateAdapter = true)
data class WikimediaPage(
    val title: String? = null,
    val videoinfo: List<WikimediaVideoInfo>? = null,
    val imageinfo: List<WikimediaVideoInfo>? = null
)

@JsonClass(generateAdapter = true)
data class WikimediaVideoInfo(
    val url: String? = null,
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Double? = null,
    val mime: String? = null,
    val thumburl: String? = null,
    val derivatives: List<WikimediaDerivative>? = null
)

@JsonClass(generateAdapter = true)
data class WikimediaDerivative(
    val src: String? = null,
    val type: String? = null,
    val title: String? = null,
    val shorttitle: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bandwidth: Long? = null
)

interface ArchiveOrgApiService {
    @GET("metadata/{identifier}")
    suspend fun getMetadata(@Path("identifier") identifier: String): ArchiveMetadataResponse
}

interface WikimediaApiService {
    @GET("w/api.php")
    suspend fun getMediaInfo(
        @Query("action") action: String = "query",
        @Query("titles") titles: String,
        @Query("prop") prop: String = "videoinfo|imageinfo",
        @Query("viprop") viprop: String = "url|size|mime|dimensions|derivatives",
        @Query("iiprop") iiprop: String = "url|size|mime|dimensions",
        @Query("iiurlwidth") iiurlwidth: Int = 640,
        @Query("format") format: String = "json"
    ): WikimediaApiResponse
}

