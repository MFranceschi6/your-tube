package com.yourtube.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Top-of-screen search field with clear button.
 * Uses Material 3 [SearchBar] in its collapsed (non-expanded) form to provide
 * a consistent rounded pill appearance matching the mockup.
 *
 * Callers control the expanded state and provide the results slot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YourTubeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search YouTube",
    content: @Composable () -> Unit = {},
) {
    val searchFieldLabel = stringResource(R.string.cd_search_bar_field)
    val clearSearchLabel = stringResource(R.string.cd_search_bar_clear)
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                placeholder = { Text(placeholder) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.semantics { contentDescription = clearSearchLabel },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else null,
                modifier = Modifier.semantics { contentDescription = searchFieldLabel },
            )
        },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
        content = { content() },
    )
}

// ── Previews ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun SearchBarIdlePreview() {
    MaterialTheme {
        YourTubeSearchBar(
            query = "",
            onQueryChange = {},
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun SearchBarWithTextPreview() {
    MaterialTheme {
        YourTubeSearchBar(
            query = "lofi beats",
            onQueryChange = {},
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}
