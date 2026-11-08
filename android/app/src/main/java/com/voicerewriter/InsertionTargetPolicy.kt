package com.voicerewriter

internal data class EditableTarget(
    val focused: Boolean,
    val visible: Boolean,
    val enabled: Boolean,
    val supportsInputFocus: Boolean,
    val password: Boolean = false,
)

internal data class TargetChoice(val index: Int?, val needsFocus: Boolean, val reason: String)

/** Never guess between multiple editors or focus a hidden/disabled/password field. */
internal fun chooseEditableTarget(targets: List<EditableTarget>): TargetChoice {
    val usable = targets.indices.filter { targets[it].visible && targets[it].enabled }
    val focused = usable.filter { targets[it].focused }
    if (focused.size == 1) return TargetChoice(focused.single(), false, "focused_editable")
    if (focused.size > 1) return TargetChoice(null, false, "ambiguous_focused_editors")
    if (usable.size != 1) return TargetChoice(null, false, if (usable.isEmpty()) "no_editable_editor" else "ambiguous_editors")
    val index = usable.single()
    if (targets[index].password) return TargetChoice(null, false, "unfocused_password_editor")
    if (!targets[index].supportsInputFocus) return TargetChoice(null, false, "editor_cannot_request_input_focus")
    return TargetChoice(index, true, "unique_editor_needs_focus")
}

internal class InsertionRetryGate(private val intervalMs: Long = 150) {
    private var lastAttempt: Long? = null
    fun reset() { lastAttempt = null }
    fun delayMs(now: Long): Long = lastAttempt?.let { (intervalMs - (now - it)).coerceAtLeast(0) } ?: 0
    fun attempted(now: Long) { lastAttempt = now }
}
