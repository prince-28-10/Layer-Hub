package com.example.androidtest.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SourcePlatformIdentifierTest {
    @Test
    fun identifiesAllSevenSupportedPlatformsAndShortLinks() {
        assertEquals(SourcePlatform.YOUTUBE, identifySourcePlatform("https://youtube.com/watch?v=abc"))
        assertEquals(SourcePlatform.YOUTUBE, identifySourcePlatform("https://youtu.be/abc"))
        assertEquals(SourcePlatform.INSTAGRAM, identifySourcePlatform("https://instagram.com/reel/abc"))
        assertEquals(SourcePlatform.REDDIT, identifySourcePlatform("https://redd.it/abc"))
        assertEquals(SourcePlatform.REDDIT, identifySourcePlatform("https://reddit.com/r/android"))
        assertEquals(SourcePlatform.FACEBOOK, identifySourcePlatform("https://fb.watch/abc"))
        assertEquals(SourcePlatform.FACEBOOK, identifySourcePlatform("https://facebook.com/story.php?id=1"))
        assertEquals(SourcePlatform.X, identifySourcePlatform("https://x.com/user/status/1"))
        assertEquals(SourcePlatform.X, identifySourcePlatform("https://twitter.com/user/status/1"))
        assertEquals(SourcePlatform.TELEGRAM, identifySourcePlatform("https://t.me/channel"))
        assertEquals(SourcePlatform.WHATSAPP, identifySourcePlatform("https://wa.me/123456789"))
    }

    @Test
    fun acceptsSupportedSubdomainsAndNormalizesCaseAndWww() {
        assertEquals(SourcePlatform.YOUTUBE, identifySourcePlatform("HTTPS://WWW.YouTube.COM/watch?v=abc"))
        assertEquals(SourcePlatform.YOUTUBE, identifySourcePlatform("https://m.youtube.com/shorts/abc"))
        assertEquals(SourcePlatform.INSTAGRAM, identifySourcePlatform("www.instagram.com/p/example"))
        assertEquals(SourcePlatform.REDDIT, identifySourcePlatform("https://old.reddit.com/r/example"))
        assertEquals(SourcePlatform.FACEBOOK, identifySourcePlatform("https://business.facebook.com/page"))
        assertEquals(SourcePlatform.X, identifySourcePlatform("https://mobile.twitter.com/user"))
        assertEquals(SourcePlatform.TELEGRAM, identifySourcePlatform("https://web.telegram.me/channel"))
        assertEquals(SourcePlatform.WHATSAPP, identifySourcePlatform("https://chat.whatsapp.com/invite"))
    }

    @Test
    fun ignoresQueriesAndFragmentsWhenIdentifyingTheHost() {
        assertEquals(SourcePlatform.YOUTUBE, identifySourcePlatform("https://youtube.com/watch?v=one&feature=share#comments"))
        assertEquals(SourcePlatform.INSTAGRAM, identifySourcePlatform("https://instagram.com/p/one/?utm_source=x#image"))
        assertEquals(SourcePlatform.REDDIT, identifySourcePlatform("https://reddit.com/r/topic?sort=top#post"))
    }

    @Test
    fun rejectsMisleadingMalformedAndUnsupportedLinks() {
        assertEquals(SourcePlatform.UNKNOWN, identifySourcePlatform("https://youtube.com.example.org/watch"))
        assertEquals(SourcePlatform.UNKNOWN, identifySourcePlatform("https://notyoutube.com/watch"))
        assertEquals(SourcePlatform.UNKNOWN, identifySourcePlatform("https://user@youtube.com/watch"))
        assertEquals(SourcePlatform.UNKNOWN, identifySourcePlatform("https://youtube.com:99999/watch"))
        assertEquals(SourcePlatform.UNKNOWN, identifySourcePlatform("https://example.org/path"))
        assertEquals(SourcePlatform.UNKNOWN, identifySourcePlatform("not a URL"))
        assertEquals(SourcePlatform.UNKNOWN, identifySourcePlatform(null))
        assertEquals(SourcePlatform.UNKNOWN, identifySourcePlatform("https://"))
    }

    @Test
    fun extractsUrlsFromSurroundingTextAndHandlesMultipleUrlsSafely() {
        assertEquals(SourcePlatform.YOUTUBE, identifySourcePlatform("Watch this clip: https://youtu.be/abc?t=3 — it is short."))
        assertEquals(SourcePlatform.REDDIT, identifySourcePlatform("Post from www.reddit.com/r/android. Interesting!"))
        assertEquals(SourcePlatform.UNKNOWN, identifySourcePlatform("https://youtube.com/watch https://instagram.com/p/one"))
        assertEquals(SourcePlatform.YOUTUBE, identifySourcePlatform("https://youtube.com/watch and https://youtu.be/abc"))
        assertEquals(SourcePlatform.UNKNOWN, identifySourcePlatform("https://youtube.com/watch https://example.org/page"))
    }
}
