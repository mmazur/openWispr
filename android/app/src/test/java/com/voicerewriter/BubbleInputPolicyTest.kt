package com.voicerewriter

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleInputPolicyTest {
    @Test fun numericFieldsVetoBothDetectionModesEvenWithKeyboardAndEditableFocus() {
        val numericTypes = listOf(
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE,
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME,
        )
        for (type in numericTypes) for (keyboardOnly in listOf(false, true)) {
            assertFalse("type=$type keyboardOnly=$keyboardOnly", bubbleInputAvailable(
                keyboardOnly, imeOpen = true, editable = true, inputType = type,
            ))
            assertFalse(bubbleInputAvailable(
                keyboardOnly, imeOpen = false, editable = true, inputType = type,
            ))
            assertFalse(bubbleInputAvailable(
                keyboardOnly, imeOpen = true, editable = false, inputType = type,
            ))
        }
    }

    @Test fun textAndUnknownTypesKeepKeyboardFallback() {
        val types = listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_NULL,
            null,
        )
        for (type in types) for (keyboardOnly in listOf(false, true)) {
            assertTrue("type=$type keyboardOnly=$keyboardOnly", bubbleInputAvailable(
                keyboardOnly, imeOpen = true, editable = false, inputType = type,
            ))
        }
    }

    @Test fun missingKeyboardStillHidesInKeyboardOnlyMode() {
        assertFalse(bubbleInputAvailable(true, false, true, InputType.TYPE_CLASS_TEXT))
        assertFalse(bubbleInputAvailable(true, false, true, null))
        assertTrue(bubbleInputAvailable(false, false, true, InputType.TYPE_CLASS_TEXT))
        assertTrue(bubbleInputAvailable(false, false, true, null))
        assertFalse(bubbleInputAvailable(false, false, false, null))
    }

    @Test fun keyboardEditorTypeSuppressesNumericFieldsEvenWhenNodeTypeIsUnknownOrText() {
        for (keyboardOnly in listOf(false, true)) {
            for (nodeType in listOf(null, InputType.TYPE_NULL, InputType.TYPE_CLASS_TEXT)) {
                for (editorType in listOf(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
                    InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)) {
                    assertFalse(bubbleInputAvailable(keyboardOnly, true, true, nodeType, editorType))
                }
            }
        }
    }

    @Test fun keyboardTextEditorOverridesStaleNumericNode() {
        for (keyboardOnly in listOf(false, true)) {
            assertTrue(bubbleInputAvailable(keyboardOnly, true, true,
                InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_TEXT))
        }
    }

    @Test fun absentOrUnspecifiedKeyboardMetadataPreservesNodeFallback() {
        for (editorType in listOf(null, InputType.TYPE_NULL)) {
            for (keyboardOnly in listOf(false, true)) {
                assertFalse(bubbleInputAvailable(keyboardOnly, true, true,
                    InputType.TYPE_CLASS_NUMBER, editorType))
                assertTrue(bubbleInputAvailable(keyboardOnly, true, true,
                    InputType.TYPE_CLASS_TEXT, editorType))
            }
        }
    }
}
