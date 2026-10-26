package com.example.androidtest.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaCapabilityTest {
    @Test
    fun distinguishesDownloadPreparationMetadataAndConfigurationStatuses() {
        val direct = MediaResolution(
            status = ResolutionStatus.READY,
            sourceName = "Direct media link",
            canPrepare = true,
            remoteDownloadUrl = "https://media.example/video"
        )
        val local = MediaResolution(
            status = ResolutionStatus.READY,
            sourceName = "Shared media",
            canPrepare = true
        )
        val reddit = MediaResolution(status = ResolutionStatus.READY, sourceName = "Reddit")
        val youtube = MediaResolution(status = ResolutionStatus.NOT_CONFIGURED, sourceName = "YouTube")
        val restricted = MediaResolution(status = ResolutionStatus.RESTRICTED, sourceName = "Reddit")

        assertEquals("Direct download path verified", direct.capabilityLabel())
        assertEquals("Shared media preparation available", local.capabilityLabel())
        assertEquals("Metadata available · no download path", reddit.capabilityLabel())
        assertEquals("Configuration required", youtube.capabilityLabel())
        assertEquals("Restricted or access denied", restricted.capabilityLabel())
        assertEquals("Unsupported", MediaResolution(ResolutionStatus.UNSUPPORTED, "Instagram").capabilityLabel())
    }

    @Test
    fun threeMinuteCheckAllowsTheBoundaryAndRejectsUnknownOrLongVideos() {
        assertNull(directVideoDurationFailure(MAX_VIDEO_DURATION_MS))
        assertNotNull(directVideoDurationFailure(MAX_VIDEO_DURATION_MS + 1))
        assertNotNull(directVideoDurationFailure(null))
        assertNotNull(directVideoDurationFailure(0))
    }

    @Test
    fun analysisDurationFixturesCoverMediaTypesAndValidationBoundaries() {
        assertTrue(isDurationAllowed(MediaType.VIDEO, 180))
        assertFalse(isDurationAllowed(MediaType.VIDEO, 181))
        assertFalse(isDurationAllowed(MediaType.VIDEO, null))
        assertTrue(isDurationAllowed(MediaType.AUDIO, 180))
        assertFalse(isDurationAllowed(MediaType.AUDIO, 181))
        assertFalse(isDurationAllowed(MediaType.AUDIO, 0))
        assertTrue(isDurationAllowed(MediaType.IMAGE, null))
        assertFalse(isDurationAllowed(MediaType.UNKNOWN, null))
    }
}
