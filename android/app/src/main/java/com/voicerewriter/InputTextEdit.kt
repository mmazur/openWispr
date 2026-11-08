package com.voicerewriter

internal data class InputTextEdit(val text: String, val cursor: Int)

/** Accessibility may expose an empty editor's placeholder as its text. */
internal fun buildInputTextEdit(
    reportedText: String?, showingHint: Boolean, selectionStart: Int, selectionEnd: Int, insert: String,
): InputTextEdit? {
    val current = if (showingHint) "" else reportedText.orEmpty()
    val (start, end) = when {
        selectionStart in 0..current.length && selectionEnd in 0..current.length ->
            minOf(selectionStart, selectionEnd) to maxOf(selectionStart, selectionEnd)
        current.isEmpty() -> 0 to 0
        else -> return null // Preserve real content when the cursor is unknown.
    }
    return InputTextEdit(current.substring(0, start) + insert + current.substring(end), start + insert.length)
}
