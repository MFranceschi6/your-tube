package com.yourtube.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourtube.core.data.preferences.ThemePreference
import com.yourtube.core.data.update.UpdateStatus
import com.yourtube.core.ui.LocalAppShellInsets
import com.yourtube.core.ui.R as CoreUiR
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Maps a [ThemePreference] to its localised display label. */
@Composable
private fun ThemePreference.displayLabel(): String = when (this) {
    ThemePreference.SYSTEM -> stringResource(CoreUiR.string.lbl_settings_theme_system)
    ThemePreference.LIGHT -> stringResource(CoreUiR.string.lbl_settings_theme_light)
    ThemePreference.DARK -> stringResource(CoreUiR.string.lbl_settings_theme_dark)
}

/**
 * Outcome forwarded from the AppShell-scoped `SharingViewModel` into the
 * Settings destination so the C16 inline banner can react to import results
 * without the Settings module depending on the `:app` module's
 * `PlaylistImportResult` sealed type.
 *
 * - [Success] — the picked file was imported.
 * - [Failure] — the file was unreadable, malformed, or used a schema this app
 *               doesn't understand. All three map to the same banner copy in
 *               `design-system/handoff/state-catalog/copy.md` C16.import.
 */
enum class SettingsImportOutcome { Success, Failure }

/**
 * MIME types passed to the [ActivityResultContracts.OpenDocument] launcher used by
 * Settings → Import playlist.
 *
 * We accept:
 * - `application/json`: the canonical type for our exported `.ytplaylist.json` files.
 * - `application/octet-stream`: some content providers (notably files surfaced via
 *   "Recent" / Downloads on Pixel devices) classify untyped JSON exports as octet-stream
 *   when no extension-based mapping is available. Keeping it here ensures a freshly
 *   exported playlist is always selectable without forcing the user to flip the SAF
 *   filter to "All files".
 *
 * `text/plain` and the wildcard `* / *` (without spaces) are intentionally omitted:
 * they over-broaden the picker so SAF lists arbitrary system files
 * (PNGs, XML, screenshots) which the user must then scan.
 * The downstream import handler validates content shape, so we can keep this filter tight.
 */
internal val IMPORT_PLAYLIST_MIME_TYPES: Array<String> = arrayOf(
    "application/json",
    "application/octet-stream",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onImportFromUri: (Uri) -> Unit,
    /**
     * YT-0251 — installed versionCode passed from AppShell (where BuildConfig is accessible).
     * The ViewModel is in :feature:settings which has no :app dependency, so the value is
     * threaded in here rather than read inside the ViewModel.
     */
    installedVersionCode: Long,
    modifier: Modifier = Modifier,
    importEvents: Flow<SettingsImportOutcome> = emptyFlow(),
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()
    var showQualityDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    val shellInsets = LocalAppShellInsets.current
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // YT-0164 C15: signal in-progress while the SAF document is read
            // by the downstream importer. The terminal state is now driven by
            // [importEvents] (YT-0187) — Success flips back to Idle; any
            // failure variant flips to Failed and renders the C16 banner.
            viewModel.onImportStarted()
            onImportFromUri(uri)
        }
    }

    // YT-0187: drive the terminal row state from real importer outcomes
    // rather than the previous 600 ms auto-clear. The shell maps
    // SharingViewModel.PlaylistImportResult into SettingsImportOutcome so this
    // module stays free of an :app dependency.
    LaunchedEffect(importEvents, viewModel) {
        importEvents.collect { outcome ->
            when (outcome) {
                SettingsImportOutcome.Success -> viewModel.onImportSucceeded()
                SettingsImportOutcome.Failure -> viewModel.onImportFailed()
            }
        }
    }

    // YT-0164 C16: surface retry requests from the inline banner by re-
    // launching the SAF picker. The banner's "Try again" calls
    // viewModel.retryImport() which emits on retryImportRequests.
    LaunchedEffect(viewModel) {
        viewModel.retryImportRequests.collect {
            importLauncher.launch(IMPORT_PLAYLIST_MIME_TYPES)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(CoreUiR.string.lbl_settings_title)) })
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            // YT-0061: bottom inset from AppShell ensures content clears MiniPlayer + nav bar.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = shellInsets.calculateBottomPadding(),
            ),
        ) {
            item {
                SectionHeader(stringResource(CoreUiR.string.lbl_settings_section_playback))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(CoreUiR.string.lbl_settings_audio_quality_title)) },
                    supportingContent = { Text(uiState.audioQuality.label) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = stringResource(CoreUiR.string.lbl_settings_audio_quality_click),
                            onClick = { showQualityDialog = true },
                        ),
                )
                HorizontalDivider()
            }
            // YT-0241 — opt-in toggle: when ON, swiping the app card from recents also
            // stops audio + dismisses the foreground notification. Default OFF preserves
            // YT-0076 AC#5 Spotify-style persistence.
            item {
                StopOnTaskRemovedRow(
                    enabled = uiState.stopOnTaskRemoved,
                    onToggle = { viewModel.toggleStopOnTaskRemoved() },
                )
                HorizontalDivider()
            }
            // YT-0097 — haptics toggle. Default ON.
            item {
                HapticsRow(
                    enabled = uiState.hapticsEnabled,
                    onToggle = { viewModel.toggleHapticsEnabled() },
                )
                HorizontalDivider()
            }
            // YT-0089 — autoplay toggle. Default ON.
            item {
                AutoplayRow(
                    enabled = uiState.autoplayEnabled,
                    onToggle = { viewModel.toggleAutoplay() },
                )
                HorizontalDivider()
            }
            item {
                SectionHeader(stringResource(CoreUiR.string.lbl_settings_section_appearance))
            }
            // YT-0316 — Theme picker: System / Light / Dark. Comes first in Appearance section.
            item {
                ThemePickerRow(
                    currentPreference = uiState.themePreference,
                    onClick = { showThemeDialog = true },
                )
                HorizontalDivider()
            }
            // YT-0102 — AMOLED-black toggle. Default OFF. No-op in light mode.
            // Grayed when themePreference is not DARK — the setting has no effect otherwise.
            item {
                AmoledBlackRow(
                    enabled = uiState.amoledBlackEnabled,
                    interactive = uiState.themePreference == ThemePreference.DARK,
                    onToggle = { viewModel.toggleAmoledBlack() },
                )
                HorizontalDivider()
            }
            item {
                SectionHeader(stringResource(CoreUiR.string.lbl_settings_section_playlists))
            }
            item {
                val importing = uiState.importRow == ImportRowState.InProgress
                ListItem(
                    headlineContent = { Text(stringResource(CoreUiR.string.lbl_settings_import_title)) },
                    // C15 status text: replace the row subtitle with the
                    // operation-scoped progress copy while in flight.
                    supportingContent = {
                        Text(
                            if (importing) stringResource(CoreUiR.string.lbl_settings_import_subtitle_loading)
                            else stringResource(CoreUiR.string.lbl_settings_import_subtitle_idle),
                        )
                    },
                    // C15 indicator: 24 dp spinner replaces the row chevron
                    // while the operation runs. Settings page itself stays
                    // interactive — only this row's trailing area changes.
                    trailingContent = if (importing) {
                        {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = !importing,
                            onClickLabel = stringResource(CoreUiR.string.lbl_settings_import_click),
                            onClick = {
                                importLauncher.launch(IMPORT_PLAYLIST_MIME_TYPES)
                            },
                        ),
                )
                // YT-0187 C16: inline banner attaches *below* the failed row,
                // full-width within the row's section group. Renders only when
                // the most recent import attempt failed; the spinner row above
                // stays untouched.
                if (uiState.importRow == ImportRowState.Failed) {
                    ImportErrorBanner(
                        onRetry = { viewModel.retryImport() },
                        onDismiss = { viewModel.dismissImportError() },
                    )
                }
                HorizontalDivider()
            }
            item {
                SectionHeader(stringResource(CoreUiR.string.lbl_settings_section_about))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(CoreUiR.string.lbl_settings_about_app_name)) },
                    supportingContent = { Text(stringResource(CoreUiR.string.lbl_settings_about_app_desc)) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(CoreUiR.string.lbl_settings_version_title)) },
                    supportingContent = { Text(stringResource(CoreUiR.string.lbl_settings_version_value)) },
                )
                HorizontalDivider()
            }
            // YT-0251 — "Check for updates" row. Triggers the update-feed fetch and
            // shows results inline. A spinner replaces the trailing area while checking.
            item {
                CheckForUpdatesRow(
                    rowState = uiState.updateCheckRow,
                    onCheck = { viewModel.checkForUpdates(installedVersionCode) },
                    onRetry = { viewModel.checkForUpdates(installedVersionCode) },
                    onDismissError = { viewModel.dismissUpdateCheckError() },
                )
                // Non-null, non-UpToDate result: show result summary below row.
                when (val status = updateStatus) {
                    is UpdateStatus.UpdateAvailable -> {
                        UpdateAvailableBanner(
                            versionName = status.latestVersionName,
                            onUpdate = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(status.apkUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try {
                                    context.startActivity(intent)
                                } catch (e: ActivityNotFoundException) {
                                    Toast.makeText(context, status.apkUrl, Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                    }
                    // UpdateRequired is handled at AppShell level; no banner here.
                    else -> Unit
                }
                HorizontalDivider()
            }
        }
    }

    if (showQualityDialog) {
        AudioQualityDialog(
            currentQuality = uiState.audioQuality,
            onSelect = { quality ->
                viewModel.setAudioQuality(quality)
                showQualityDialog = false
            },
            onDismiss = { showQualityDialog = false },
        )
    }

    if (showThemeDialog) {
        ThemePickerDialog(
            currentPreference = uiState.themePreference,
            onSelect = { preference ->
                viewModel.setThemePreference(preference)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun ThemePickerRow(
    currentPreference: ThemePreference,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(stringResource(CoreUiR.string.lbl_settings_theme_title)) },
        supportingContent = { Text(currentPreference.displayLabel()) },
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(CoreUiR.string.lbl_settings_theme_click),
                onClick = onClick,
            ),
    )
}

@Composable
private fun ThemePickerDialog(
    currentPreference: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreUiR.string.lbl_settings_theme_dialog_title)) },
        text = {
            Column {
                ThemePreference.entries.forEach { pref ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(pref) },
                    ) {
                        RadioButton(
                            selected = pref == currentPreference,
                            onClick = { onSelect(pref) },
                        )
                        Text(pref.displayLabel())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.lbl_settings_theme_cancel)) }
        },
    )
}

/**
 * YT-0241 — Switch row for "Stop playback when app is closed".
 *
 * Tapping anywhere on the row flips the preference (matching Material guidance for
 * full-row toggle affordance). The row's `contentDescription` announces the toggle's
 * current state ("on" / "off") so TalkBack reads "Stop playback when app is closed,
 * off" — same announcement pattern used by other a11y-friendly switch rows in the app.
 *
 * Inline literals here mirror the existing convention in this file (Audio Quality,
 * Import playlist, version row, etc.); a sweep to `strings.xml` is tracked separately
 * by YT-0064 across `core/ui` and `feature/settings` together.
 */
@Composable
private fun StopOnTaskRemovedRow(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val rowCd = stringResource(
        if (enabled) CoreUiR.string.cd_settings_stop_on_close_on
        else CoreUiR.string.cd_settings_stop_on_close_off,
    )
    val clickLabel = stringResource(
        if (enabled) CoreUiR.string.lbl_settings_stop_on_close_turn_off
        else CoreUiR.string.lbl_settings_stop_on_close_turn_on,
    )
    ListItem(
        headlineContent = { Text(stringResource(CoreUiR.string.lbl_settings_stop_on_close_title)) },
        supportingContent = {
            Text(stringResource(CoreUiR.string.lbl_settings_stop_on_close_body))
        },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = rowCd }
            .clickable(onClickLabel = clickLabel, onClick = onToggle),
    )
}

/**
 * YT-0097 — Switch row for "Haptic feedback".
 *
 * Mirrors the StopOnTaskRemovedRow pattern: full-row tap area, TalkBack announces current state.
 * Default on; disabling silences all vibration from playback actions.
 */
@Composable
private fun HapticsRow(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val rowCd = stringResource(
        if (enabled) CoreUiR.string.cd_settings_haptics_on
        else CoreUiR.string.cd_settings_haptics_off,
    )
    val clickLabel = stringResource(
        if (enabled) CoreUiR.string.lbl_settings_stop_on_close_turn_off
        else CoreUiR.string.lbl_settings_stop_on_close_turn_on,
    )
    ListItem(
        headlineContent = { Text(stringResource(CoreUiR.string.lbl_settings_haptics_title)) },
        supportingContent = { Text(stringResource(CoreUiR.string.lbl_settings_haptics_body)) },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = rowCd }
            .clickable(onClickLabel = clickLabel, onClick = onToggle),
    )
}

/**
 * YT-0089 — Switch row for "Autoplay".
 *
 * When enabled, the player automatically fetches and enqueues a related track when the
 * queue empties at end-of-playback. Default on. Mirrors the [StopOnTaskRemovedRow] pattern:
 * full-row tap area, TalkBack announces current state.
 */
@Composable
private fun AutoplayRow(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val rowCd = stringResource(
        if (enabled) CoreUiR.string.cd_settings_autoplay_on
        else CoreUiR.string.cd_settings_autoplay_off,
    )
    val clickLabel = stringResource(
        if (enabled) CoreUiR.string.lbl_settings_autoplay_turn_off
        else CoreUiR.string.lbl_settings_autoplay_turn_on,
    )
    ListItem(
        headlineContent = { Text(stringResource(CoreUiR.string.lbl_settings_autoplay_title)) },
        supportingContent = { Text(stringResource(CoreUiR.string.lbl_settings_autoplay_body)) },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = rowCd }
            .clickable(onClickLabel = clickLabel, onClick = onToggle),
    )
}

/**
 * YT-0102 — Switch row for "AMOLED black".
 *
 * When enabled in dark mode, `background` and `surface` color tokens are
 * overridden to pure black (#000000) for true-black OLED screens. The row
 * discloses that the setting has no effect in light mode via its supporting
 * text, matching the disclosure convention used by other conditional settings.
 *
 * Contrast: pure black (#000000) as background with `Color.White` as
 * `onBackground`/`onSurface` yields ∞:1 contrast ratio — well above the
 * WCAG AA requirement of 4.5:1 for normal text and 3:1 for large text.
 *
 * Inline literals mirror the convention in this file; a strings.xml sweep is
 * tracked by YT-0064.
 */
@Composable
private fun AmoledBlackRow(
    enabled: Boolean,
    onToggle: () -> Unit,
    interactive: Boolean = true,
) {
    val rowCd = stringResource(
        if (enabled) CoreUiR.string.cd_settings_amoled_black_on else CoreUiR.string.cd_settings_amoled_black_off,
    )
    val clickLabel = stringResource(
        if (enabled) CoreUiR.string.lbl_settings_amoled_black_turn_off else CoreUiR.string.lbl_settings_amoled_black_turn_on,
    )
    ListItem(
        headlineContent = { Text(stringResource(CoreUiR.string.lbl_settings_amoled_black_title)) },
        supportingContent = {
            Text(stringResource(CoreUiR.string.lbl_settings_amoled_black_body))
        },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = { if (interactive) onToggle() },
                enabled = interactive,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = rowCd }
            .clickable(enabled = interactive, onClickLabel = clickLabel, onClick = onToggle),
    )
}

/**
 * YT-0187 C16 inline banner — Settings · operation failed.
 *
 * Form factor and copy follow `design-system/handoff/state-catalog/error.md`
 * C16 and `copy.md` C16.import verbatim:
 *
 *  - Background: `Color(0xFFFF453A).copy(alpha = 0.12f)`
 *    (`--color-error-surface` from the catalog).
 *  - 3 dp left accent strip in `Color(0xFFFF453A)` — the only place red
 *    appears in this catalog, and only as a strip (icon and text stay
 *    `onSurfaceVariant`).
 *  - 12 dp internal padding.
 *  - 20 dp `Icons.Rounded.ErrorOutline` icon, tinted `onSurfaceVariant`.
 *  - Two right-aligned `TextButton`s — "Try again" and "Dismiss".
 *  - `liveRegion = Assertive` — the only assertive announcement in the
 *    catalog. Out-of-band failures justify the interrupt.
 *
 * The icon's `contentDescription` is null (decorative); the live region
 * announces the title + body together when the banner enters composition.
 */
@Composable
private fun ImportErrorBanner(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorRed = Color(0xFFFF453A)
    // 3 dp left accent strip drawn via `drawBehind` so the strip is the full
    // banner height regardless of the parent's vertical constraints. A leading
    // `fillMaxHeight()` Box renders at 0 dp inside a `LazyColumn` item because
    // the incoming `maxHeight` is `Constraints.Infinity`.
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .drawBehind {
                drawRect(
                    color = errorRed,
                    size = Size(width = 3.dp.toPx(), height = size.height),
                )
            },
        color = errorRed.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 3.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(CoreUiR.string.err_settings_import_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(CoreUiR.string.err_settings_import_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onRetry) { Text(stringResource(CoreUiR.string.lbl_settings_try_again)) }
                TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.lbl_settings_dismiss)) }
            }
        }
    }
}

/**
 * YT-0251 — "Check for updates" settings row.
 *
 * States:
 * - [UpdateCheckRowState.Idle]     — tappable row with "Check for updates" label.
 * - [UpdateCheckRowState.Checking] — spinner in trailing area; row disabled.
 * - [UpdateCheckRowState.Error]    — C16 inline banner attaches below the row; "Try again" +
 *                                    "Dismiss" buttons per `design-system/handoff/state-catalog/error.md`.
 *
 * The error banner follows the same form factor as [ImportErrorBanner]: full-width, red error
 * surface background, 3dp left accent strip, [LiveRegionMode.Assertive] announcement. The row
 * itself reverts to its idle subtitle while the banner is visible so the row remains tappable.
 */
@Composable
private fun CheckForUpdatesRow(
    rowState: UpdateCheckRowState,
    onCheck: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val checking = rowState == UpdateCheckRowState.Checking
    val error = rowState == UpdateCheckRowState.Error
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(CoreUiR.string.lbl_settings_check_updates_title)) },
            supportingContent = {
                Text(
                    if (checking) stringResource(CoreUiR.string.lbl_settings_check_updates_checking)
                    else stringResource(CoreUiR.string.lbl_settings_check_updates_idle),
                )
            },
            trailingContent = if (checking) {
                {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = !checking,
                    onClickLabel = stringResource(CoreUiR.string.lbl_settings_check_updates_click),
                    onClick = onCheck,
                ),
        )
        // C16 inline banner — attaches below the row, same as ImportErrorBanner.
        if (error) {
            UpdateCheckErrorBanner(
                onRetry = onRetry,
                onDismiss = onDismissError,
            )
        }
    }
}

/**
 * YT-0281 C16 inline banner — Settings · update-check failed.
 *
 * Form factor and visual treatment match [ImportErrorBanner] verbatim, as both follow the
 * `design-system/handoff/state-catalog/error.md` C16 pattern:
 *
 *  - Background: `Color(0xFFFF453A).copy(alpha = 0.12f)` (`--color-error-surface`).
 *  - 3dp left accent strip in `Color(0xFFFF453A)`.
 *  - 12dp internal padding.
 *  - 20dp `Icons.Rounded.ErrorOutline` icon, tinted `onSurfaceVariant` (decorative — null cd).
 *  - Two right-aligned `TextButton`s — "Try again" and "Dismiss".
 *  - `liveRegion = Assertive` — the only assertive announcement in the catalog.
 */
@Composable
private fun UpdateCheckErrorBanner(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorRed = Color(0xFFFF453A)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .drawBehind {
                drawRect(
                    color = errorRed,
                    size = Size(width = 3.dp.toPx(), height = size.height),
                )
            },
        color = errorRed.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 3.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(CoreUiR.string.err_settings_check_updates_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(CoreUiR.string.err_settings_check_updates_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onRetry) { Text(stringResource(CoreUiR.string.lbl_settings_try_again)) }
                TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.lbl_settings_dismiss)) }
            }
        }
    }
}

/**
 * YT-0251 — inline banner shown below the "Check for updates" row when a newer
 * build is available. Provides the "Update" CTA which opens the APK URL in the
 * system browser via Intent.ACTION_VIEW (browser handoff — no install permissions needed).
 */
@Composable
private fun UpdateAvailableBanner(
    versionName: String,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(CoreUiR.string.lbl_settings_update_available_title, versionName),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(CoreUiR.string.lbl_settings_update_available_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onUpdate) { Text(stringResource(CoreUiR.string.lbl_settings_update_btn)) }
        }
    }
}

@Composable
private fun AudioQualityDialog(
    currentQuality: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreUiR.string.lbl_settings_audio_quality_dialog_title)) },
        text = {
            Column {
                AudioQuality.entries.forEach { quality ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(quality) },
                    ) {
                        RadioButton(
                            selected = quality == currentQuality,
                            onClick = { onSelect(quality) },
                        )
                        Text(quality.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreUiR.string.lbl_settings_audio_quality_dialog_cancel))
            }
        },
    )
}
