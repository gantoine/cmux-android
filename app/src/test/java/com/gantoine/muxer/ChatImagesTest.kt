package com.gantoine.muxer

import com.gantoine.muxer.images.ChatImages
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatImagesTest {
    @Test
    fun smallImagesKeepTheirSize() {
        assertEquals(1f, ChatImages.scaleFor(1080, 2048, 2048))
        assertEquals(1f, ChatImages.scaleFor(0, 0, 2048))
    }

    @Test
    fun largeImagesShrinkToFitTheLongestEdge() {
        assertEquals(0.5f, ChatImages.scaleFor(4096, 3072, 2048))
        assertEquals(0.5f, ChatImages.scaleFor(1500, 4096, 2048))
    }
}
