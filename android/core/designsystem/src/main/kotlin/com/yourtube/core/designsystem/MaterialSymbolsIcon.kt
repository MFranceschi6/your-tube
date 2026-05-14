package com.yourtube.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourtube.core.designsystem.R

/**
 * Source of truth: design-system/README.md §ICONOGRAPHY and
 * design-system/handoff/symbol-map/symbol-map.md.
 *
 * Values are Material Symbols Rounded codepoints from Google's Material Symbols font.
 */
enum class IconKey(val codepoint: String) {
    Search("\uE8B6"),
    // TODO YT-0276: music_note_list (U+F4E6) is absent from the shipped font subset.
    //  This entry is kept for API stability but the Library navigation item renders
    //  Icons.Rounded.LibraryMusic instead.  Update this codepoint once the font subset
    //  is regenerated to include music_note_list.
    Library("\uF4E6"),  // music_note_list \u2014 NOT present in shipped subset; see TODO above
    History("\uE889"),
    Player("\uE1C4"),
    Settings("\uE8B8"),
    Play("\uE037"),
    Pause("\uE034"),
    SkipNext("\uE044"),
    SkipPrev("\uE045"),
    Shuffle("\uE043"),
    Repeat("\uE040"),
    RepeatOne("\uE041"),
    Cast("\uE307"),
    Queue("\uE03D"),
    Overflow("\uE5D4"),
    Download("\uF090"),
    PlaylistAdd("\uE03B"),
    Share("\uE80D"),
    Delete("\uE872"),
    Error("\uE001"),  // error
    Success("\uE86C"),
    // TODO YT-0282: bedtime (U+EF44) is absent from the shipped font subset.
    //  This entry is kept for API stability but the sleep-timer button in NowPlayingChrome
    //  renders Icons.Rounded.Bedtime instead.  Update once the font subset is regenerated.
    Bedtime("\uEF44"),  // bedtime (sleep timer) \u2014 NOT present in shipped subset; see TODO above
    Mic("\uE029"),      // mic (voice search)
    Speed("\uE9E4"),    // speed \u2014 playback-speed control in NowPlayingChrome
}

@Composable
@OptIn(ExperimentalTextApi::class)
fun Icon(
    icon: IconKey,
    filled: Boolean = false,
    weight: Int = 400,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: TextUnit = 24.sp,
) {
    val clampedWeight = weight.coerceIn(100, 700)
    val opticalSize = size.value.coerceIn(20f, 48f)
    val fontFamily = remember(filled, clampedWeight, opticalSize) {
        FontFamily(
            Font(
                resId = R.font.material_symbols_rounded,
                variationSettings = FontVariation.Settings(
                    FontVariation.Setting("FILL", if (filled) 1f else 0f),
                    FontVariation.weight(clampedWeight),
                    FontVariation.grade(0),
                    FontVariation.Setting("opsz", opticalSize),
                ),
            ),
        )
    }
    Text(
        text = icon.codepoint,
        modifier = modifier.then(
            if (contentDescription == null) {
                Modifier.clearAndSetSemantics {}
            } else {
                Modifier.semantics { this.contentDescription = contentDescription }
            },
        ),
        color = tint,
        fontFamily = fontFamily,
        fontSize = size,
        lineHeight = size,
        textAlign = TextAlign.Center,
        style = TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun MaterialSymbolsIconPreview() {
    MaterialTheme {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(400, 500, 600).forEach { weight ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconKey.entries.forEach { icon ->
                        Icon(icon = icon, weight = weight, contentDescription = null)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconKey.entries.forEach { icon ->
                        Icon(
                            icon = icon,
                            filled = true,
                            weight = weight,
                            contentDescription = null,
                        )
                    }
                }
            }
        }
    }
}
