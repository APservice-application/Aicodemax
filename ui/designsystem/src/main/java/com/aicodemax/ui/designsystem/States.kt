package com.aicodemax.ui.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Animation timing tokens from 1.2.txt §99 (ms). */
object AicodeAnim {
    const val MICRO = 120
    const val NORMAL = 220
    const val MODAL = 300
    /** Skeleton shimmer: slow by spec (§86 "Animation ช้าๆ"). */
    const val SKELETON = 900
}

/**
 * Loading skeleton (§86): shimmer bars instead of a full-screen spinner.
 * Slow sweep per spec; [lines] bars with the last one shorter.
 */
@Composable
fun ShimmerSkeleton(lines: Int = 3, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val sweep by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(AicodeAnim.SKELETON, easing = LinearEasing)),
        label = "sweep",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val shine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines.coerceAtLeast(1)) { index ->
            val fraction = if (index == lines - 1 && lines > 1) 0.6f else 1f
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(16.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(base, shine, base),
                            start = Offset(sweep * 300f - 300f, 0f),
                            end = Offset(sweep * 300f, 120f),
                        ),
                        RoundedCornerShape(8.dp),
                    ),
            )
        }
    }
}

/**
 * Empty state (§87): 64dp icon + title + description + primary/secondary actions.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (primaryLabel != null && onPrimary != null) {
                Button(onClick = onPrimary) { Text(primaryLabel) }
            }
            if (secondaryLabel != null && onSecondary != null) {
                OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
            }
        }
    }
}

/**
 * Error state (§88): icon + title + explanation + retry + expandable details.
 */
@Composable
fun ErrorState(
    icon: ImageVector,
    title: String,
    explanation: String,
    onRetry: () -> Unit,
    details: String? = null,
    modifier: Modifier = Modifier,
) {
    var showDetails by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Button(onClick = onRetry) { Text("ลองใหม่") }
        if (!details.isNullOrBlank()) {
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(if (showDetails) "ซ่อนรายละเอียด" else "รายละเอียด")
            }
            if (showDetails) {
                Text(details, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
