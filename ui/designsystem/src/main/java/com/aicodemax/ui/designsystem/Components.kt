package com.aicodemax.ui.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * CP-135 shell top bar (§10): ☰ + title + optional search + contextual actions.
 * Every screen reuses this; workspaces pass their own [actions].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AicodeTopBar(
    title: String,
    onMenu: () -> Unit,
    onSearch: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CenterAlignedTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onMenu, modifier = Modifier.semantics { contentDescription = "เมนู" }) {
                Icon(Icons.Filled.Menu, contentDescription = null)
            }
        },
        actions = {
            if (onSearch != null) {
                IconButton(
                    onClick = onSearch,
                    modifier = Modifier.semantics { contentDescription = "ค้นหา" },
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                }
            }
            actions()
        },
    )
}

/** CP-135 search field (§61): query + clear + IME search action. */
@Composable
fun AicodeSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String = "ค้นหา…",
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "ล้างคำค้น")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(AicodeRadii.XXL),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
    )
}

/**
 * CP-135 confirmation dialog (§59/§86): title + explanation + destructive-safe
 * actions with 48dp targets. Used for unsaved changes, deletes, destructive ops.
 */
@Composable
fun ConfirmDialog(
    title: String,
    explanation: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "ยกเลิก",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(explanation) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = AicodeSize.MinTouch),
            ) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = AicodeSize.MinTouch),
            ) { Text(dismissLabel) }
        },
    )
}

/** CP-135 offline banner (§60): local-ready messaging + retry. */
@Composable
fun OfflineBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val color = statusColor(StatusKind.WARNING)
    Surface(
        shape = RoundedCornerShape(AicodeRadii.M),
        color = color.copy(alpha = 0.12f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = LocalSpacing.current.md),
        ) {
            Text(
                text = "ออฟไลน์ — งานในเครื่องยังใช้ได้",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("ลองใหม่") }
        }
    }
}

/** CP-135 section header for drawers and workspace screens. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = modifier.padding(
            horizontal = LocalSpacing.current.md,
            vertical = LocalSpacing.current.sm,
        ),
    )
}

/** CP-135 launcher entry (§13): destination id + label + glyph + one-line hint. */
data class LauncherEntry(
    val id: String,
    val label: String,
    val glyph: String,
    val hint: String = "",
)

/** CP-135 launcher grid (§13): the app drawer behind ☰. */
@Composable
fun LauncherGrid(
    entries: List<LauncherEntry>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        items(entries, key = { it.id }) { entry ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(
                        onClick = { onOpen(entry.id) },
                        onClickLabel = entry.label,
                    )
                    .padding(vertical = LocalSpacing.current.sm),
            ) {
                Text(entry.glyph, style = MaterialTheme.typography.headlineMedium)
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** CP-135 read-only output block for tool results and search hits. */
@Composable
fun OutputBlock(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(AicodeRadii.S),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(LocalSpacing.current.sm),
        )
    }
}

/** CP-135 single-line labeled input used by workspace forms. */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
