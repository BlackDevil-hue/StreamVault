package com.example.data.network

import java.util.Locale

data class YouTubeAudioMetadata(
    val songTitle: String,
    val songArtist: String?
)

/**
 * Extracts a clean song title (without video/channel/movie noise) and the performing
 * artist/singer from YouTube yt-dlp metadata, descriptions, and video titles.
 */
object YouTubeAudioMetadataExtractor {

    fun extract(
        rawTitle: String,
        rawTrack: String? = null,
        rawAltTitle: String? = null,
        rawArtist: String? = null,
        rawArtists: List<String> = emptyList(),
        rawAlbumArtist: String? = null,
        rawCreator: String? = null,
        rawCreators: List<String> = emptyList(),
        uploader: String? = null,
        channel: String? = null,
        description: String? = null
    ): YouTubeAudioMetadata {
        val structuredArtist = normalizeArtistList(
            buildList {
                rawArtist?.let(::add)
                addAll(rawArtists)
                if (isEmpty()) rawAlbumArtist?.let(::add)
                if (isEmpty()) rawCreator?.let(::add)
                if (isEmpty()) addAll(rawCreators)
            }
        )

        val (descTitle, descArtist) = extractFromDescription(description)
        val channelArtist = extractChannelArtist(uploader) ?: extractChannelArtist(channel)

        val cleanTrackTitle = rawTrack?.let { cleanSongTitle(it) }
        val cleanAltTitle = rawAltTitle?.let { cleanSongTitle(it) }
        val parsedFromVideoTitle = parseFromVideoTitle(
            rawTitle = rawTitle,
            knownTrackTitle = cleanTrackTitle?.title ?: cleanAltTitle?.title ?: descTitle,
            knownArtistHint = structuredArtist ?: descArtist ?: channelArtist
        )

        val resolvedSongTitle = listOfNotNull(
            cleanTrackTitle?.title?.takeIf { it.isNotBlank() },
            cleanAltTitle?.title?.takeIf { it.isNotBlank() },
            descTitle?.takeIf { it.isNotBlank() },
            parsedFromVideoTitle.songTitle.takeIf { it.isNotBlank() },
            rawTitle.trim().takeIf { it.isNotBlank() }
        ).firstOrNull() ?: "YouTube audio"

        val baseArtist = structuredArtist
            ?: descArtist
            ?: parsedFromVideoTitle.songArtist
            ?: channelArtist

        val extraFeatured = (cleanTrackTitle?.featuredArtists.orEmpty() +
            cleanAltTitle?.featuredArtists.orEmpty() +
            parsedFromVideoTitle.featuredArtists)
            .distinctBy { it.lowercase(Locale.US) }

        val finalArtist = mergeFeaturedArtists(baseArtist, extraFeatured)

        return YouTubeAudioMetadata(
            songTitle = resolvedSongTitle,
            songArtist = finalArtist
        )
    }

    private data class CleanedTitle(
        val title: String,
        val featuredArtists: List<String> = emptyList(),
        val hadVideoNoise: Boolean = false
    )

    private data class TitleParseResult(
        val songTitle: String,
        val songArtist: String?,
        val featuredArtists: List<String>
    )

    private fun parseFromVideoTitle(
        rawTitle: String,
        knownTrackTitle: String?,
        knownArtistHint: String?
    ): TitleParseResult {
        val primarySegment = pickPrimaryPipeSegment(rawTitle)
        val withoutTrailingNoiseDash = TRAILING_DASH_NOISE_REGEX.replace(primarySegment, "").trim()
        val workingTitle = withoutTrailingNoiseDash.ifBlank { primarySegment }

        val dashSplit = SPACED_DASH_REGEX.find(workingTitle)
        if (dashSplit != null) {
            val leftRaw = workingTitle.substring(0, dashSplit.range.first).trim()
            val rightRaw = workingTitle.substring(dashSplit.range.last + 1).trim()
            if (leftRaw.isNotBlank() && rightRaw.isNotBlank()) {
                val leftClean = cleanSongTitle(leftRaw)
                val rightClean = cleanSongTitle(rightRaw)

                val trackMatchesLeft = knownTrackTitle != null &&
                    titlesRoughlyMatch(leftClean.title, knownTrackTitle)
                val trackMatchesRight = knownTrackTitle != null &&
                    titlesRoughlyMatch(rightClean.title, knownTrackTitle)

                val artistMatchesLeft = knownArtistHint != null &&
                    artistsOverlap(leftClean.title, knownArtistHint)
                val artistMatchesRight = knownArtistHint != null &&
                    artistsOverlap(rightClean.title, knownArtistHint)

                val (songSide, artistSide) = when {
                    trackMatchesRight && !trackMatchesLeft -> rightClean to leftClean.title
                    trackMatchesLeft && !trackMatchesRight -> {
                        // Left is the song; only treat right as artist if it matches a known artist hint
                        // (otherwise right is often a movie/album subtitle like "Song - Movie Name").
                        val candidateArtist = if (artistMatchesRight) rightClean.title else null
                        leftClean to candidateArtist
                    }
                    artistMatchesLeft && !artistMatchesRight -> rightClean to leftClean.title
                    artistMatchesRight && !artistMatchesLeft -> leftClean to rightClean.title
                    leftClean.hadVideoNoise && !rightClean.hadVideoNoise -> leftClean to rightClean.title
                    else -> rightClean to leftClean.title
                }

                val validatedArtist = artistSide
                    ?.let(::normalizeArtistCandidate)
                    ?.takeUnless(::isLikelyNoiseOrLabel)

                if (songSide.title.isNotBlank()) {
                    return TitleParseResult(
                        songTitle = songSide.title,
                        songArtist = validatedArtist,
                        featuredArtists = songSide.featuredArtists
                    )
                }
            }
        }

        val cleaned = cleanSongTitle(workingTitle)
        return TitleParseResult(
            songTitle = cleaned.title.ifBlank { rawTitle.trim() },
            songArtist = null,
            featuredArtists = cleaned.featuredArtists
        )
    }

    private fun pickPrimaryPipeSegment(rawTitle: String): String {
        val segments = rawTitle.split(PIPE_OR_BULLET_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (segments.isEmpty()) return rawTitle.trim()
        if (segments.size == 1) return segments.first()
        val first = segments.first()
        return if (isLikelyLabelOrAggregator(first) && segments.size > 1) {
            segments[1]
        } else {
            first
        }
    }

    private fun cleanSongTitle(raw: String): CleanedTitle {
        var text = raw.trim()
        if (text.isBlank()) return CleanedTitle("")

        var hadNoise = false
        val featured = mutableListOf<String>()

        // Strip trailing hashtags (#music #shorts)
        val noTags = TRAILING_HASHTAGS_REGEX.replace(text, "").trim()
        if (noTags.length != text.length) hadNoise = true
        text = noTags

        // Extract and strip parenthetical/bracketed featuring credits
        PAREN_FEAT_REGEX.findAll(text).forEach { match ->
            extractArtistTokens(match.groupValues[1]).forEach { featured.add(it) }
        }
        val withoutParenFeat = PAREN_FEAT_REGEX.replace(text, " ").trim()
        if (withoutParenFeat.length != text.length) hadNoise = true
        text = withoutParenFeat

        // Strip noise blocks like (Official Video), [4K], (Full Song), (Lyric Video), (From "Movie")
        text = BRACKETED_BLOCK_REGEX.replace(text) { match ->
            val inner = match.groupValues[1].trim()
            if (isVideoNoiseTag(inner)) {
                hadNoise = true
                " "
            } else {
                match.value
            }
        }

        // Extract and strip inline "ft. Artist" / "feat. Artist"
        val inlineFeatMatch = INLINE_FEAT_REGEX.find(text)
        if (inlineFeatMatch != null) {
            extractArtistTokens(inlineFeatMatch.groupValues[1]).forEach { featured.add(it) }
            text = text.substring(0, inlineFeatMatch.range.first).trim()
            hadNoise = true
        }

        // Strip trailing unbracketed release noise (e.g., "Official Music Video", "Lyric Video")
        val withoutSuffixNoise = TRAILING_UNBRACKETED_NOISE_REGEX.replace(text, "").trim()
        if (withoutSuffixNoise.length != text.length) hadNoise = true
        text = withoutSuffixNoise

        val cleaned = text
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
            .trim('-', '–', '—', '|', ':', '•')
            .trim()

        return CleanedTitle(
            title = cleaned.ifBlank { raw.trim() },
            featuredArtists = featured.distinctBy { it.lowercase(Locale.US) },
            hadVideoNoise = hadNoise
        )
    }

    private fun isVideoNoiseTag(inner: String): Boolean {
        val lower = inner.lowercase(Locale.US).trim()
        if (lower.isBlank()) return true
        if (lower.startsWith("from ") || lower.startsWith("from\"")) return true
        if (lower.matches(RESOLUTION_OR_YEAR_ONLY_REGEX)) return true
        return NOISE_KEYWORDS.any { keyword ->
            Regex("\\b${Regex.escape(keyword)}\\b").containsMatchIn(lower)
        }
    }

    private fun extractFromDescription(description: String?): Pair<String?, String?> {
        if (description.isNullOrBlank()) return null to null

        val lines = description.lineSequence()
            .map { it.trim() }
            .toList()

        // 1. YouTube auto-generated "Provided to YouTube by..." description:
        //    "<Song Title> · <Artist 1> · <Artist 2>"
        val isAutoGenerated = description.contains("Provided to YouTube by", ignoreCase = true)
        var autoTitle: String? = null
        var autoArtist: String? = null
        if (isAutoGenerated) {
            val dotLine = lines.firstOrNull { line ->
                line.contains(" · ") &&
                    !line.startsWith("Provided to YouTube", ignoreCase = true) &&
                    !line.startsWith("http", ignoreCase = true)
            }
            if (dotLine != null) {
                val parts = dotLine.split(" · ").map { it.trim() }.filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    autoTitle = cleanSongTitle(parts.first()).title.takeIf { it.isNotBlank() }
                    autoArtist = normalizeArtistList(parts.drop(1))
                }
            }
        }

        // 2. Structured credit lines (Song: ..., Singer: ..., Artist: ..., Vocals: ...)
        var creditTitle: String? = null
        var primarySingerArtist: String? = null
        var secondaryArtist: String? = null

        for (line in lines.take(120)) {
            if (line.isBlank() || line.startsWith("http", ignoreCase = true)) continue
            val match = CREDIT_LINE_REGEX.matchEntire(line) ?: continue
            val key = match.groupValues[1].trim().lowercase(Locale.US)
                .replace(MULTI_SPACE_REGEX, " ")
            val rawValue = match.groupValues[2]
                .split(CREDIT_VALUE_SEPARATOR_REGEX)
                .firstOrNull()
                ?.trim()
                .orEmpty()
            if (rawValue.isBlank() || rawValue.startsWith("http", ignoreCase = true)) continue

            when (key) {
                in SONG_TITLE_CREDIT_KEYS -> {
                    if (creditTitle == null) {
                        val cleaned = cleanSongTitle(rawValue).title
                        if (cleaned.isNotBlank() && !isPlaceholderValue(cleaned)) {
                            creditTitle = cleaned
                        }
                    }
                }
                in PRIMARY_SINGER_CREDIT_KEYS -> {
                    if (primarySingerArtist == null) {
                        primarySingerArtist = normalizeArtistList(extractArtistTokens(rawValue))
                    }
                }
                in SECONDARY_ARTIST_CREDIT_KEYS -> {
                    if (secondaryArtist == null) {
                        secondaryArtist = normalizeArtistList(extractArtistTokens(rawValue))
                    }
                }
            }
        }

        val resolvedTitle = autoTitle ?: creditTitle
        val resolvedArtist = autoArtist ?: primarySingerArtist ?: secondaryArtist
        return resolvedTitle to resolvedArtist
    }

    private fun extractChannelArtist(rawChannel: String?): String? {
        val trimmed = rawChannel?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.endsWith(" - Topic", ignoreCase = true)) {
            return normalizeArtistCandidate(
                trimmed.substring(0, trimmed.length - " - Topic".length)
            )
        }
        if (trimmed.endsWith("VEVO", ignoreCase = true)) {
            val base = trimmed.substring(0, trimmed.length - 4).trim()
            if (base.isBlank() || isLikelyLabelOrAggregator(base)) return null
            val spaced = if (!base.contains(' ')) {
                CAMEL_CASE_SPLIT_REGEX.replace(base, " ").trim()
            } else {
                base
            }
            return normalizeArtistCandidate(spaced)?.takeUnless(::isLikelyLabelOrAggregator)
        }
        val strippedOfficial = OFFICIAL_CHANNEL_SUFFIX_REGEX.replace(trimmed, "").trim()
        val candidate = normalizeArtistCandidate(strippedOfficial) ?: return null
        return candidate.takeUnless(::isLikelyLabelOrAggregator)
    }

    private fun normalizeArtistList(candidates: List<String>): String? {
        val tokens = candidates
            .flatMap(::extractArtistTokens)
            .mapNotNull(::normalizeArtistCandidate)
            .distinctBy { it.lowercase(Locale.US) }
        return tokens.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun mergeFeaturedArtists(baseArtist: String?, featured: List<String>): String? {
        val baseTokens = baseArtist?.let(::extractArtistTokens)?.mapNotNull(::normalizeArtistCandidate).orEmpty()
        val combined = (baseTokens + featured.mapNotNull(::normalizeArtistCandidate))
            .distinctBy { it.lowercase(Locale.US) }
        return combined.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun extractArtistTokens(raw: String): List<String> {
        return raw
            .replace(FEAT_PREFIX_REGEX, ",")
            .split(ARTIST_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun normalizeArtistCandidate(raw: String?): String? {
        val cleaned = raw
            ?.replace(MULTI_SPACE_REGEX, " ")
            ?.trim()
            ?.trim('-', '–', '—', ':', '|', ',', '•', '&')
            ?.trim()
            ?: return null
        if (cleaned.isBlank()) return null
        if (isPlaceholderValue(cleaned)) return null
        if (cleaned.startsWith("http", ignoreCase = true) || cleaned.startsWith("@")) return null
        return cleaned
    }

    private fun isPlaceholderValue(value: String): Boolean {
        val lower = value.lowercase(Locale.US).trim()
        return lower in setOf(
            "unknown", "unknown artist", "various artists", "na", "n/a", "none", "-", "null"
        )
    }

    private fun isLikelyNoiseOrLabel(value: String): Boolean =
        isLikelyLabelOrAggregator(value) || isVideoNoiseTag(value)

    private fun isLikelyLabelOrAggregator(name: String): Boolean {
        val lower = name.lowercase(Locale.US).trim()
        if (LABEL_SUBSTRINGS.any { lower.contains(it) }) return true
        return LABEL_WORDS.any { word ->
            Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(lower)
        }
    }

    private fun titlesRoughlyMatch(a: String, b: String): Boolean {
        val na = a.lowercase(Locale.US).replace(NON_ALNUM_REGEX, "")
        val nb = b.lowercase(Locale.US).replace(NON_ALNUM_REGEX, "")
        return na.isNotEmpty() && nb.isNotEmpty() && (na == nb || na.contains(nb) || nb.contains(na))
    }

    private fun artistsOverlap(candidate: String, artistList: String): Boolean {
        val candNorm = candidate.lowercase(Locale.US).replace(NON_ALNUM_REGEX, "")
        if (candNorm.isEmpty()) return false
        return extractArtistTokens(artistList).any { token ->
            val tokNorm = token.lowercase(Locale.US).replace(NON_ALNUM_REGEX, "")
            tokNorm.isNotEmpty() && (candNorm == tokNorm || candNorm.contains(tokNorm) || tokNorm.contains(candNorm))
        }
    }

    private val PIPE_OR_BULLET_REGEX = Regex("\\s*[|¦•]\\s*|\\s+//\\s+")
    private val SPACED_DASH_REGEX = Regex("\\s+[-–—]\\s+")
    private val TRAILING_DASH_NOISE_REGEX = Regex(
        "\\s+[-–—]\\s+(?:official\\s+(?:music\\s+)?(?:video|audio)|lyric(?:s)?\\s+video|lyrics|full\\s+(?:video|audio|song)|video\\s+song|hd|4k)\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_HASHTAGS_REGEX = Regex("(?:\\s+#\\S+)+\\s*$")
    private val PAREN_FEAT_REGEX = Regex(
        "[(\\[](?:feat\\.?|ft\\.?|featuring)\\s+([^)\\]]+)[)\\]]",
        RegexOption.IGNORE_CASE
    )
    private val INLINE_FEAT_REGEX = Regex(
        "\\s+(?:feat\\.?|ft\\.?|featuring)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val BRACKETED_BLOCK_REGEX = Regex("[(\\[【]([^)\\]】]+)[)\\]】]")
    private val TRAILING_UNBRACKETED_NOISE_REGEX = Regex(
        "\\s+(?:official\\s+(?:music\\s+)?(?:video|audio|lyric\\s+video|visualizer)|lyric(?:s)?\\s+video|full\\s+(?:video\\s+song|video|audio|song)|video\\s+song|music\\s+video|4k\\s+(?:video|uhd)|hd\\s+video)\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val RESOLUTION_OR_YEAR_ONLY_REGEX = Regex("^(?:hd|hq|uhd|4k|8k|1080p|720p|320\\s*kbps)$")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val CAMEL_CASE_SPLIT_REGEX = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private val OFFICIAL_CHANNEL_SUFFIX_REGEX = Regex(
        "\\s*(?:[-–—]\\s*)?official(?:\\s+channel)?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val CREDIT_LINE_REGEX = Regex(
        "^\\s*(?:[#*•\\-]+\\s*)?([A-Za-z][A-Za-z\\s()/&'.]{1,28}?)\\s*(?:[:\\-–—~]+)\\s*(.+?)\\s*$"
    )
    private val CREDIT_VALUE_SEPARATOR_REGEX = Regex("\\s*[|•]\\s*|\\s+//\\s+")
    private val FEAT_PREFIX_REGEX = Regex("\\b(?:feat\\.?|ft\\.?|featuring)\\b", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT_REGEX = Regex("\\s*(?:,|;|\\band\\b|&|\\s+/\\s+)\\s*", RegexOption.IGNORE_CASE)
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]")

    private val SONG_TITLE_CREDIT_KEYS = setOf(
        "song", "song name", "song title", "track", "track name", "track title", "audio"
    )
    private val PRIMARY_SINGER_CREDIT_KEYS = setOf(
        "singer", "singers", "singer(s)",
        "vocal", "vocals", "vocalist", "vocalists", "vocalist(s)",
        "sung by", "performed by", "voice"
    )
    private val SECONDARY_ARTIST_CREDIT_KEYS = setOf(
        "artist", "artists", "artist(s)", "featuring", "ft"
    )

    private val NOISE_KEYWORDS = listOf(
        "official", "music video", "lyric video", "lyrics", "lyric", "audio",
        "full video", "full song", "video song", "visualizer", "visualiser",
        "remastered", "remaster", "explicit", "clean version", "4k", "8k", "uhd",
        "hd", "hq", "vevo", "jukebox", "teaser", "trailer", "promo", "mp3",
        "320kbps", "video"
    )

    private val LABEL_SUBSTRINGS = listOf(
        "t-series", "tseries", "zee music", "sony music", "yash raj", "tips official",
        "tips music", "saregama", "speed records", "eros now", "shemaroo", "times music",
        "junglee music", "desimusicfactory", "desi music factory", "white hill",
        "geet mp3", "jass records", "humble music", "universal music", "warner music",
        "atlantic records", "columbia records", "republic records", "rca records",
        "interscope", "def jam", "capitol records", "epic records", "parlophone",
        "spinnin", "monstercat", "nocopyrightsounds", "trap nation", "chill nation",
        "bass nation", "mrsuicidesheep", "majestic casual", "lyrical lemonade",
        "colorsxstudios", "vevo"
    )

    private val LABEL_WORDS = listOf(
        "records", "entertainment", "studios", "studio", "films", "productions",
        "media", "music", "lyrics", "lyric", "audio", "radio", "channel", "tv",
        "network", "worldwide", "hits", "playlist", "compilation", "mashup",
        "soundtrack", "jukebox", "yrf", "ncs", "genius", "bbc", "mtv"
    )
}
