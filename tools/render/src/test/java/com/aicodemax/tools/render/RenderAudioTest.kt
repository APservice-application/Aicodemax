package com.aicodemax.tools.render

import com.aicodemax.data.media.Clip
import com.aicodemax.data.media.ClipKeyframes
import com.aicodemax.data.media.ClipSpeed
import com.aicodemax.data.media.ClipTransition
import com.aicodemax.data.media.KeyPoint
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.Track
import com.aicodemax.data.media.Timeline
import com.aicodemax.tools.audio.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderAudioTest {
    private val video = Clip("v", "video", 1_000, 3_000, 500)
    private val music = Clip("a", "music", 0, 2_000, 0)

    @Test fun embeddedVideoAudioAndMusicAreSelectedAndDetachmentDoesNotDouble() {
        val timeline = Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(video)), Track("A1", MediaKind.AUDIO, listOf(music))))
        assertEquals(listOf("a", "v"), RenderAudio.selectedClips(timeline, true, mapOf("video" to true)).map { it.id })
        assertEquals(listOf("a"), RenderAudio.selectedClips(timeline, true, mapOf("video" to false)).map { it.id })
        assertTrue(RenderAudio.selectedClips(timeline, false, mapOf("video" to true)).isEmpty())
        val detached = timeline.copy(tracks = listOf(
            Track("V1", MediaKind.VIDEO, listOf(video.copy(volume = 0))),
            Track("A1", MediaKind.AUDIO, listOf(music)),
        ))
        assertEquals(listOf("a"), RenderAudio.selectedClips(detached, true, mapOf("video" to true)).map { it.id })
    }

    @Test fun trackMuteHideAndVolumeControlNativeSound() {
        val timeline = Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(video))))
        assertEquals(listOf("v"), RenderAudio.selectedClips(timeline, true, mapOf("video" to true)).map { it.id })
        assertTrue(RenderAudio.selectedClips(timeline.copy(tracks = listOf(Track("V1", MediaKind.VIDEO, listOf(video), muted = true))), true, mapOf("video" to true)).isEmpty())
        assertTrue(RenderAudio.selectedClips(timeline.copy(tracks = listOf(Track("V1", MediaKind.VIDEO, listOf(video), hidden = true))), true, mapOf("video" to true)).isEmpty())
        assertTrue(RenderAudio.selectedClips(timeline.copy(tracks = listOf(Track("V1", MediaKind.VIDEO, listOf(video.copy(volume = 0))))), true, mapOf("video" to true)).isEmpty())
        assertTrue(RenderAudio.selectedClips(Timeline(listOf(Track("A1", MediaKind.AUDIO, listOf(music), muted = true))), true, emptyMap()).isEmpty())
    }

    @Test fun fastCopyRequiresOnlyUntouchedNativeSoundOrNoSound() {
        assertTrue(RenderAudio.canFastCopy(video, listOf(video)))
        assertTrue(RenderAudio.canFastCopy(video, emptyList()))
        assertFalse(RenderAudio.canFastCopy(video.copy(volume = 25), listOf(video.copy(volume = 25))))
        assertFalse(RenderAudio.canFastCopy(video, listOf(video, music)))
        assertFalse(RenderAudio.canFastCopy(video, listOf(music)))
    }

    @Test fun trimmedAndReverseSourceWindowsAndSpeedAreBounded() {
        assertEquals(RenderAudio.SourceWindow(1_000, 1_521), RenderAudio.sourceRange(video, 500.0, 1_000.0))
        val reversed = video.copy(speed = ClipSpeed(reverse = true))
        assertEquals(RenderAudio.SourceWindow(2_480, 3_000), RenderAudio.sourceRange(reversed, 500.0, 1_000.0))
        val faster = video.copy(speed = ClipSpeed(rate = 200))
        assertEquals(RenderAudio.SourceWindow(1_000, 2_021), RenderAudio.sourceRange(faster, 500.0, 1_000.0))
        assertEquals(null, RenderAudio.sourceRange(video, 3_000.0, 3_500.0))
    }

    @Test fun mixedOverlappingClipsUseStereoVolumeAndFrameAccurateOffset() {
        val first = Clip("c1", "s1", 0, 1000, 0, volume = 50)
        val second = Clip("c2", "s2", 0, 1000, 500)
        val source = PcmAudio(1000, 1, FloatArray(1000) { 0.4f })
        val chunk = FloatArray(1000 * 2)
        RenderAudio.mixChunk(chunk, 0, 1000, first, source, 0)
        RenderAudio.mixChunk(chunk, 0, 1000, second, source, 0)
        assertEquals(0.2f, chunk[400 * 2], 0.0001f)
        assertEquals(0.6f, chunk[750 * 2], 0.0001f)
        assertEquals(chunk[750 * 2], chunk[750 * 2 + 1], 0.0001f)
    }

    @Test fun twoAdjacentChunksMatchOneFullMixWithTrimSpeedAndFade() {
        val clip = video.copy(
            speed = ClipSpeed(rate = 200),
            transitionIn = ClipTransition("fade", 200),
            keyframes = ClipKeyframes(volume = listOf(KeyPoint(0, 100f), KeyPoint(1_000, 50f))),
        )
        val source = PcmAudio(1000, 2, FloatArray(2_000 * 2) { i -> if (i % 2 == 0) (i / 2) / 2000f else -0.3f })
        val full = FloatArray(2000 * 2)
        RenderAudio.mixChunk(full, 0, 1000, clip, source, 1_000)
        val split = FloatArray(2000 * 2)
        val left = FloatArray(1000 * 2)
        val right = FloatArray(1000 * 2)
        RenderAudio.mixChunk(left, 0, 1000, clip, source, 1_000)
        RenderAudio.mixChunk(right, 1000, 1000, clip, source, 1_000)
        left.copyInto(split, 0)
        right.copyInto(split, 2000)
        assertTrue(full.contentEquals(split))
        assertEquals(0f, full[500 * 2], 0.001f) // fade starts at clip.atMs=500
        assertTrue(full[800 * 2] > 0f)
    }

    @Test fun reverseAudioStartsOnLastRealSourceFrame() {
        val clip = Clip("rev", "v", 0, 1000, 0, speed = ClipSpeed(reverse = true))
        val src = PcmAudio(1000, 1, FloatArray(1000) { it / 1000f })
        val into = FloatArray(1000 * 2)
        RenderAudio.mixChunk(into, 0, 1000, clip, src, 0)
        assertEquals(0.999f, into[0], 0.002f)
        assertTrue(into[0] > into[998 * 2])
    }

    @Test fun sourceWindowsAtChunkBoundaryRemainAligned() {
        val clip = Clip("c", "v", 1000, 3000, 0, speed = ClipSpeed(rate = 200))
        val firstWindow = RenderAudio.sourceRange(clip, 0.0, 500.0)!!
        val secondWindow = RenderAudio.sourceRange(clip, 500.0, 1000.0)!!
        assertTrue(firstWindow.startMs <= 1000 && firstWindow.endMs > 2000)
        assertTrue(secondWindow.startMs <= 2000 && secondWindow.endMs >= 3000)
    }
}
