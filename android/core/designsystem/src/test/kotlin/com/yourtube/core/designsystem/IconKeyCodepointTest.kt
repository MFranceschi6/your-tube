package com.yourtube.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * YT-0065 regression guard -- locks Material Symbols Rounded codepoints for the
 * two entries whose wrong values were caught in the blocker review (AC3).
 *
 * If either assertion fails after a font update, verify the new codepoint
 * against design-system/handoff/symbol-map/symbol-map.md before changing.
 */
class IconKeyCodepointTest {

    @Test
    fun iconKey_Library_hasCorrectCodepoint() =
        assertEquals("", IconKey.Library.codepoint)

    @Test
    fun iconKey_Error_hasCorrectCodepoint() =
        assertEquals("", IconKey.Error.codepoint)
}
