package com.example.androidtest.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMetadataAdapterTest {
    @Test
    fun parsesYoutubeWatchShortsAndShortLinkVariants() {
        assertEquals(
            ParsedMetadataLink(MetadataSource.YOUTUBE, "abcdefghijk"),
            parseMetadataSourceLink("Video: https://www.youtube.com/watch?v=abcdefghijk&t=12s")
        )
        assertEquals(
            ParsedMetadataLink(MetadataSource.YOUTUBE, "abcdefghijk"),
            parseMetadataSourceLink("https://m.youtube.com/shorts/abcdefghijk?feature=share")
        )
        assertEquals(
            ParsedMetadataLink(MetadataSource.YOUTUBE, "abcdefghijk"),
            parseMetadataSourceLink("https://youtu.be/abcdefghijk")
        )
    }

    @Test
    fun parsesRedditPostGalleryAndShortLinkVariants() {
        assertEquals(
            ParsedMetadataLink(MetadataSource.REDDIT, "abc123"),
            parseMetadataSourceLink("https://old.reddit.com/r/example/comments/abc123/post_title/")
        )
        assertEquals(
            ParsedMetadataLink(MetadataSource.REDDIT, "abc123"),
            parseMetadataSourceLink("https://www.reddit.com/gallery/abc123")
        )
        assertEquals(
            ParsedMetadataLink(MetadataSource.REDDIT, "abc123"),
            parseMetadataSourceLink("https://redd.it/abc123")
        )
    }

    @Test
    fun rejectsMalformedOrUntrustedUrls() {
        assertNull(parseMetadataSourceLink(null))
        assertNull(parseMetadataSourceLink("not a URL"))
        assertNull(parseMetadataSourceLink("file://youtube.com/watch?v=abcdefghijk"))
        assertNull(parseMetadataSourceLink("https://youtube.com.evil.test/watch?v=abcdefghijk"))
        assertNull(parseMetadataSourceLink("https://user@reddit.com/r/example/comments/abc123/title"))
        assertNull(parseMetadataSourceLink("https://notreddit.com/r/example/comments/abc123/title"))
        assertEquals(
            ParsedMetadataLink(MetadataSource.YOUTUBE, null),
            parseMetadataSourceLink("https://youtube.com/channel/not-a-video")
        )
    }

    @Test
    fun mapsRedditHostedVideoAndReliableDuration() {
        val post = mapOf(
            "title" to "A real post title",
            "is_video" to true,
            "secure_media" to mapOf("reddit_video" to mapOf("duration" to 42)),
            "preview" to mapOf("images" to listOf(mapOf(
                "source" to mapOf("url" to "https://preview.redd.it/post.jpg?width=640&amp;format=pjpg")
            )))
        )

        val metadata = SourceMetadataMapper.redditPost(post)

        assertEquals("A real post title", metadata?.title)
        assertEquals(MediaType.VIDEO, metadata?.mediaType)
        assertEquals(42_000L, metadata?.durationMs)
        assertEquals("https://preview.redd.it/post.jpg?width=640&format=pjpg", metadata?.thumbnailUrl)
    }

    @Test
    fun mapsRedditGalleryImagesWithoutInferringVideoOrDuration() {
        val post = mapOf(
            "title" to "Gallery post",
            "is_gallery" to true,
            "gallery_data" to mapOf("items" to listOf(mapOf("media_id" to "first"))),
            "media_metadata" to mapOf("first" to mapOf("s" to mapOf("u" to "https://i.redd.it/first.jpg")))
        )

        val metadata = SourceMetadataMapper.redditPost(post)

        assertEquals(MediaType.IMAGE, metadata?.mediaType)
        assertEquals("https://i.redd.it/first.jpg", metadata?.thumbnailUrl)
        assertNull(metadata?.durationMs)
    }

    @Test
    fun doesNotInventRedditMetadataForMissingOrDeletedPosts() {
        assertNull(SourceMetadataMapper.redditPost(emptyMap()))
        assertNull(SourceMetadataMapper.redditPost(mapOf("title" to "[deleted]", "author" to "[deleted]")))
        assertNull(SourceMetadataMapper.redditPost(mapOf(
            "title" to "Removed post",
            "removed_by_category" to "moderator"
        )))
        val unknown = SourceMetadataMapper.redditPost(mapOf(
            "title" to "Link post",
            "thumbnail" to "default",
            "url_overridden_by_dest" to "https://example.test/video.mp4"
        ))
        assertEquals(MediaType.UNKNOWN, unknown?.mediaType)
        assertNull(unknown?.thumbnailUrl)
    }

    @Test
    fun mapsYoutubeTitleThumbnailAndIsoDuration() {
        val video = mapOf(
            "id" to "abcdefghijk",
            "snippet" to mapOf(
                "title" to "Verified video title",
                "liveBroadcastContent" to "none",
                "thumbnails" to mapOf("high" to mapOf("url" to "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"))
            ),
            "contentDetails" to mapOf("duration" to "PT1M2.5S")
        )

        val metadata = SourceMetadataMapper.youtubeVideo(video, "abcdefghijk")

        assertEquals("Verified video title", metadata?.title)
        assertEquals(MediaType.VIDEO, metadata?.mediaType)
        assertEquals(62_500L, metadata?.durationMs)
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", metadata?.thumbnailUrl)
    }

    @Test
    fun handlesYoutubeMissingFieldsLiveVideosAndMismatchedIds() {
        assertNull(SourceMetadataMapper.youtubeVideo(emptyMap(), "abcdefghijk"))
        val wrongId = mapOf("id" to "zyxwvutsrqp", "snippet" to mapOf("title" to "Other video"))
        assertNull(SourceMetadataMapper.youtubeVideo(wrongId, "abcdefghijk"))

        val live = mapOf(
            "id" to "abcdefghijk",
            "snippet" to mapOf("title" to "Live", "liveBroadcastContent" to "live"),
            "contentDetails" to mapOf("duration" to "PT2H")
        )
        assertNull(SourceMetadataMapper.youtubeVideo(live, "abcdefghijk")?.durationMs)
    }

    @Test
    fun parsesOnlyValidIso8601Durations() {
        assertEquals(180_000L, parseIso8601DurationMs("PT3M"))
        assertEquals(3_723_000L, parseIso8601DurationMs("PT1H2M3S"))
        assertNull(parseIso8601DurationMs(null))
        assertNull(parseIso8601DurationMs("1:23"))
        assertNull(parseIso8601DurationMs("PT"))
        assertTrue(parseIso8601DurationMs("PT0.25S") == 250L)
    }
}
