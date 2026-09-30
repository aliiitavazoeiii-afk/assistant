package com.ali.assistant

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun FilterChip(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
    )
}
