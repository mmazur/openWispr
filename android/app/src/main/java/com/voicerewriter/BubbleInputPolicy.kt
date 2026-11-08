package com.voicerewriter

import android.text.InputType

/** Keyboard editor metadata takes priority over incomplete accessibility nodes.
 * Unknown types keep the IME fallback working for custom/web editors.
 */
internal fun bubbleInputAvailable(
    keyboardOnly: Boolean,
    imeOpen: Boolean,
    editable: Boolean,
    inputType: Int?,
    editorInputType: Int? = null,
): Boolean {
    val effectiveType = editorInputType?.takeIf { it != InputType.TYPE_NULL } ?: inputType
    val numeric = when (effectiveType?.and(InputType.TYPE_MASK_CLASS)) {
        InputType.TYPE_CLASS_NUMBER,
        InputType.TYPE_CLASS_PHONE,
        InputType.TYPE_CLASS_DATETIME -> true
        else -> false
    }
    return !numeric && (imeOpen || (!keyboardOnly && editable))
}
