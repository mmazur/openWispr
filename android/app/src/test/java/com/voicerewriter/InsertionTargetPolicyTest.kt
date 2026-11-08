package com.voicerewriter

import org.junit.Assert.*
import org.junit.Test

class InsertionTargetPolicyTest {
    private fun editor(focused: Boolean = false, visible: Boolean = true, enabled: Boolean = true,
                       focusAction: Boolean = true, password: Boolean = false) =
        EditableTarget(focused, visible, enabled, focusAction, password)

    @Test fun `focused editor wins over another visible input`() {
        val choice = chooseEditableTarget(listOf(editor(), editor(focused = true)))
        assertEquals(1, choice.index)
        assertFalse(choice.needsFocus)
    }

    @Test fun `one unfocused visible editor can request actual input focus`() {
        val choice = chooseEditableTarget(listOf(editor()))
        assertEquals(0, choice.index)
        assertTrue(choice.needsFocus)
    }

    @Test fun `multiple unfocused editors are ambiguous`() {
        assertNull(chooseEditableTarget(listOf(editor(), editor())).index)
    }

    @Test fun `hidden and disabled editors cannot receive recovered focus`() {
        assertNull(chooseEditableTarget(listOf(editor(visible = false), editor(enabled = false))).index)
        assertEquals(1, chooseEditableTarget(listOf(editor(visible = false), editor())).index)
    }

    @Test fun `accessibility focus alone cannot stand in for input focus`() {
        assertNull(chooseEditableTarget(listOf(editor(focusAction = false))).index)
    }

    @Test fun `password editor must already have focus`() {
        assertNull(chooseEditableTarget(listOf(editor(password = true))).index)
        assertEquals(0, chooseEditableTarget(listOf(editor(password = true, focused = true))).index)
    }

    @Test fun `empty or conflicting focused candidates cannot be selected`() {
        assertNull(chooseEditableTarget(emptyList()).index)
        assertNull(chooseEditableTarget(listOf(editor(focused = true), editor(focused = true))).index)
    }

    @Test fun `event storm can produce at most sixteen attempts over the insertion window`() {
        val gate = InsertionRetryGate()
        var attempts = 0
        for (now in 0L until 2300L) {
            if (gate.delayMs(now) == 0L) { gate.attempted(now); attempts++ }
        }
        assertEquals(16, attempts)
        gate.reset()
        assertEquals(0L, gate.delayMs(2300))
    }

    @Test fun `scheduled backstop attempt is delayed until minimum interval elapses`() {
        val gate = InsertionRetryGate()
        gate.attempted(1000)
        assertEquals(100L, gate.delayMs(1050))
        assertEquals(0L, gate.delayMs(1200))
    }
}
