package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleDefaultsTest {
    @Test
    fun `new settings preserve v1_6_0 bubble behavior`() {
        val settings = Settings()
        assertTrue(settings.bubbleOnlyOnFields)
        assertFalse(settings.bubbleKeyboardOnly)
        assertEquals(120, settings.bubbleDebounceMs)
        assertEquals(170, settings.bubbleShowAnimationMs)
        assertEquals(140, settings.bubbleHideAnimationMs)
    }
}
