package com.yourtube.feature.search

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YT-0325 — Asserts that `RecentSearchChip` reacts to both tap and long-press.
 *
 * The previous implementation wrapped a Material 3 `AssistChip` inside a
 * `Box.combinedClickable`; the chip absorbed pointer events so neither gesture
 * fired. The fix replaces the inner `AssistChip` with a `Surface` that owns
 * `combinedClickable` directly. These tests pin the regression by clicking and
 * long-clicking on the rendered label and verifying the corresponding lambdas run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentSearchChipTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tap on recent chip invokes onClick`() {
        var clicked = false
        var longClicked = false
        composeRule.setContent {
            RecentSearchChip(
                label = "lofi",
                onClick = { clicked = true },
                onLongClick = { longClicked = true },
            )
        }

        composeRule.onNodeWithText("lofi").performClick()

        assertTrue(clicked, "Tap on RecentSearchChip should fire onClick")
        assertFalse(longClicked, "Tap on RecentSearchChip must not fire onLongClick")
    }

    @Test
    fun `long-press on recent chip invokes onLongClick`() {
        var clickCount = 0
        var longClickCount = 0
        composeRule.setContent {
            RecentSearchChip(
                label = "lofi",
                onClick = { clickCount++ },
                onLongClick = { longClickCount++ },
            )
        }

        composeRule.onNodeWithText("lofi").performTouchInput { longClick() }

        assertEquals(1, longClickCount, "Long-press on RecentSearchChip should fire onLongClick")
        assertEquals(0, clickCount, "Long-press must not fire onClick as well")
    }
}
