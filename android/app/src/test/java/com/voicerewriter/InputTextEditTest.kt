package com.voicerewriter

import org.junit.Assert.*
import org.junit.Test

class InputTextEditTest {
    @Test fun `placeholder with unknown selection is an empty field`() {
        assertEquals(InputTextEdit("Dictated text", 13),
            buildInputTextEdit("Message here", true, -1, -1, "Dictated text"))
    }

    @Test fun `placeholder is never retained even when selection refers to its text`() {
        assertEquals(InputTextEdit("hello", 5), buildInputTextEdit("Placeholder", true, 11, 11, "hello"))
    }

    @Test fun `unknown cursor in real content still uses fallback instead of replacing it`() {
        assertNull(buildInputTextEdit("Keep this", false, -1, -1, "hello"))
    }

    @Test fun `real text identical to a placeholder is preserved when hint flag is false`() {
        assertEquals(InputTextEdit("Message here!", 13), buildInputTextEdit("Message here", false, 12, 12, "!"))
    }

    @Test fun `selection replacement preserves surrounding content and reversed selection`() {
        assertEquals(InputTextEdit("one NEW three", 7), buildInputTextEdit("one two three", false, 7, 4, "NEW"))
    }

    @Test fun `empty or unavailable text permits insertion without selection metadata`() {
        assertEquals(InputTextEdit("hello", 5), buildInputTextEdit(null, false, -1, -1, "hello"))
    }
}
