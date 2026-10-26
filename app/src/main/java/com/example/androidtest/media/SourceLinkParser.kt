package com.example.androidtest.media

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class MetadataSource { REDDIT, YOUTUBE }

enum class SourcePlatform(val displayName: String) {
    YOUTUBE("YouTube"),
    INSTAGRAM("Instagram"),
    REDDIT("Reddit"),
    FACEBOOK("Facebook"),
    X("X"),
    TELEGRAM("Telegram"),
    WHATSAPP("WhatsApp"),
    UNKNOWN("Unknown")
}

/** Identifies a shared text's source only when every extracted URL points to the same known platform. */
fun identifySourcePlatform(text: String?): SourcePlatform {
    val urls = extractUrlCandidates(text)
    if (urls.isEmpty()) return SourcePlatform.UNKNOWN
    val detected = urls.map(::identifyUrlPlatform)
    val first = detected.first()
    return first.takeIf { it != SourcePlatform.UNKNOWN && detected.all { candidate -> candidate == first } }
        ?: SourcePlatform.UNKNOWN
}

private val sharedUrlPattern = Regex(
    "(?i)(?:https?://|www\\.)[^\\s<>{}\\[\\]\\\"',;]+|(?<![\\w@.-])(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d+)?(?:/[^\\s<>{}\\[\\]\\\"',;]*)?"
)

private fun extractUrlCandidates(text: String?): List<String> = sharedUrlPattern.findAll(text.orEmpty())
    .map { it.value.trimEnd('.', '!', '?', ':', ')', ']', '}') }
    .filter(String::isNotBlank)
    .distinct()
    .toList()

private fun identifyUrlPlatform(candidate: String): SourcePlatform {
    val normalized = when {
        candidate.startsWith("www.", ignoreCase = true) -> "https://$candidate"
        candidate.startsWith("http://", ignoreCase = true) || candidate.startsWith("https://", ignoreCase = true) -> candidate
        else -> "https://$candidate"
    }
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return SourcePlatform.UNKNOWN
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
        ?: return SourcePlatform.UNKNOWN
    if (scheme !in setOf("http", "https") || uri.rawUserInfo != null || uri.port !in -1..65535) {
        return SourcePlatform.UNKNOWN
    }

    return when {
        host.isOneOfOrSubdomainOf("youtube.com", "youtube-nocookie.com", "youtu.be") -> SourcePlatform.YOUTUBE
        host.isOneOfOrSubdomainOf("instagram.com", "instagr.am") -> SourcePlatform.INSTAGRAM
        host.isOneOfOrSubdomainOf("reddit.com", "redd.it") -> SourcePlatform.REDDIT
        host.isOneOfOrSubdomainOf("facebook.com", "fb.com", "fb.watch") -> SourcePlatform.FACEBOOK
        host.isOneOfOrSubdomainOf("x.com", "twitter.com", "t.co") -> SourcePlatform.X
        host.isOneOfOrSubdomainOf("t.me", "telegram.me") -> SourcePlatform.TELEGRAM
        host.isOneOfOrSubdomainOf("wa.me", "whatsapp.com", "whatsapp.net") -> SourcePlatform.WHATSAPP
        else -> SourcePlatform.UNKNOWN
    }
}

private fun String.isOneOfOrSubdomainOf(vararg domains: String): Boolean =
    domains.any { this == it || endsWith(".$it") }

/** A source URL reduced to the identifier used by that source's metadata API. */
data class ParsedMetadataLink(
    val source: MetadataSource,
    val contentId: String?
)

/** Parses only known post/video URL forms; it never treats a host name as proof of media type. */
fun parseMetadataSourceLink(text: String?): ParsedMetadataLink? {
    val candidate = extractHttpUrlCandidate(text) ?: return null
    val normalized = if (candidate.startsWith("www.", ignoreCase = true)) "https://$candidate" else candidate
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (scheme !in setOf("http", "https") || !uri.rawUserInfo.isNullOrEmpty()) return null
    if (uri.port != -1 && uri.port !in setOf(80, 443)) return null

    val pathSegments = uri.rawPath.orEmpty().split('/').filter(String::isNotBlank)
        .mapNotNull { segment -> runCatching { URLDecoder.decode(segment, StandardCharsets.UTF_8.name()) }.getOrNull() }
    val isReddit = host == "redd.it" || host == "www.redd.it" ||
        host == "reddit.com" || host.endsWith(".reddit.com")
    if (isReddit) {
        val postId = when {
            host == "redd.it" || host == "www.redd.it" -> pathSegments.firstOrNull()
            else -> {
                val commentsIndex = pathSegments.indexOfFirst { it.equals("comments", ignoreCase = true) }
                val galleryIndex = pathSegments.indexOfFirst { it.equals("gallery", ignoreCase = true) }
                when {
                    commentsIndex >= 0 -> pathSegments.getOrNull(commentsIndex + 1)
                    galleryIndex >= 0 -> pathSegments.getOrNull(galleryIndex + 1)
                    else -> null
                }
            }
        }?.takeIf { it.matches(Regex("[a-zA-Z0-9]+")) }
        return ParsedMetadataLink(MetadataSource.REDDIT, postId)
    }

    val isYouTube = host == "youtu.be" || host.endsWith(".youtu.be") ||
        host == "youtube.com" || host.endsWith(".youtube.com") ||
        host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
    if (isYouTube) {
        val queryVideoId = uri.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
            val parts = parameter.split('=', limit = 2)
            val key = runCatching { URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) }.getOrNull()
            val value = runCatching {
                URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
            }.getOrNull()
            if (key == "v") value else null
        }.firstOrNull()
        val videoId = when {
            host == "youtu.be" || host.endsWith(".youtu.be") -> pathSegments.firstOrNull()
            pathSegments.firstOrNull()?.lowercase(Locale.ROOT) in setOf("shorts", "embed", "live", "v") ->
                pathSegments.getOrNull(1)
            else -> queryVideoId
        }?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        return ParsedMetadataLink(MetadataSource.YOUTUBE, videoId)
    }
    return null
}

internal fun extractHttpUrlCandidate(text: String?): String? =
    Regex("(?i)(https?://\\S+|www\\.\\S+)")
        .find(text.orEmpty())?.value
        ?.trimEnd('.', ',', '!', '?', ')', ']', '}', '"', '\'')
