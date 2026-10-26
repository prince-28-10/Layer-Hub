package com.example.androidtest.media

/** Small platform-neutral result from a source metadata response. It is not a prepared media file. */
data class SourceMediaMetadata(
    val title: String?,
    val thumbnailUrl: String?,
    val mediaType: MediaType,
    val durationMs: Long? = null
)

/** Mapping functions are kept independent of networking and Compose so API payloads can be tested. */
object SourceMetadataMapper {
    fun redditPost(post: Map<String, Any?>): SourceMediaMetadata? {
        val title = post.string("title")?.takeIf(String::isNotBlank) ?: return null
        val author = post.string("author")
        if (author == "[deleted]" || title.equals("[deleted]", ignoreCase = true) ||
            post.boolean("removed") == true || post.string("removed_by_category") != null
        ) return null

        val galleryItems = post.objectValue("gallery_data")?.list("items").orEmpty()
        val galleryThumbnail = galleryItems.asSequence()
            .mapNotNull { item ->
                val mediaId = (item as? Map<*, *>)?.get("media_id") as? String
                mediaId?.let { post.objectValue("media_metadata")?.objectValue(it)?.objectValue("s")?.string("u") }
            }
            .firstOrNull(::isHttpUrl)
        val previewThumbnail = post.objectValue("preview")?.list("images")
            ?.asSequence()
            ?.mapNotNull { image ->
                val source = (image as? Map<*, *>)?.get("source") as? Map<*, *>
                source?.get("url") as? String
            }
            ?.firstOrNull(::isHttpUrl)
        val thumbnail = listOfNotNull(
            galleryThumbnail,
            post.string("thumbnail")?.takeIf(::isHttpUrl),
            previewThumbnail
        ).firstOrNull()?.decodeRedditHtmlEntities()

        val video = post.boolean("is_video") == true
        val gallery = post.boolean("is_gallery") == true
        val galleryHasItems = galleryItems.isNotEmpty() || galleryThumbnail != null
        val mediaType = when {
            video -> MediaType.VIDEO
            gallery && galleryHasItems -> MediaType.IMAGE
            post.string("post_hint") == "image" -> MediaType.IMAGE
            else -> MediaType.UNKNOWN
        }
        val durationSeconds = post.objectValue("secure_media")?.objectValue("reddit_video")?.number("duration")
            ?: post.objectValue("media")?.objectValue("reddit_video")?.number("duration")
        val durationMs = if (video) durationSeconds?.toMilliseconds() else null

        return SourceMediaMetadata(
            title = title,
            thumbnailUrl = thumbnail,
            mediaType = mediaType,
            durationMs = durationMs
        )
    }

    fun youtubeVideo(video: Map<String, Any?>, expectedVideoId: String): SourceMediaMetadata? {
        if (video.string("id") != expectedVideoId) return null
        val snippet = video.objectValue("snippet") ?: return null
        val title = snippet.string("title")?.takeIf(String::isNotBlank)
        val thumbnails = snippet.objectValue("thumbnails")
        val thumbnail = listOf("maxres", "standard", "high", "medium", "default")
            .asSequence()
            .mapNotNull { size -> thumbnails?.objectValue(size)?.string("url") }
            .firstOrNull(::isHttpUrl)
        val isLive = snippet.string("liveBroadcastContent") == "live"
        val duration = if (isLive) null else parseIso8601DurationMs(video.objectValue("contentDetails")?.string("duration"))

        return SourceMediaMetadata(
            title = title,
            thumbnailUrl = thumbnail,
            mediaType = MediaType.VIDEO,
            durationMs = duration
        )
    }
}

fun parseIso8601DurationMs(value: String?): Long? {
    val match = Regex("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?$", RegexOption.IGNORE_CASE)
        .matchEntire(value.orEmpty()) ?: return null
    if (match.groupValues.drop(1).all(String::isEmpty)) return null
    val hours = match.groupValues[1].toDoubleOrNull() ?: 0.0
    val minutes = match.groupValues[2].toDoubleOrNull() ?: 0.0
    val seconds = match.groupValues[3].toDoubleOrNull() ?: 0.0
    val totalMillis = ((hours * 60 * 60 + minutes * 60 + seconds) * 1000.0)
    return totalMillis.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE }?.toLong()
}

internal fun Map<String, Any?>.objectValue(key: String): Map<String, Any?>? {
    val value = this[key] as? Map<*, *> ?: return null
    return value.entries.mapNotNull { (nestedKey, nestedValue) ->
        (nestedKey as? String)?.let { it to nestedValue }
    }.toMap()
}
internal fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<Any?>
internal fun Map<String, Any?>.string(key: String): String? = this[key] as? String
internal fun Map<String, Any?>.boolean(key: String): Boolean? = this[key] as? Boolean
internal fun Map<String, Any?>.number(key: String): Double? = when (val value = this[key]) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
}

private fun Double.toMilliseconds(): Long? =
    (this * 1000.0).takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE }?.toLong()

private fun isHttpUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

private fun String.decodeRedditHtmlEntities(): String =
    replace("&amp;", "&", ignoreCase = true)
        .replace("&#38;", "&", ignoreCase = true)
        .replace("&#x26;", "&", ignoreCase = true)
