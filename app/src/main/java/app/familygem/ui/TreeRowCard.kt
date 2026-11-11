package app.familygem.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Read-only Compose content. Clicks, the overflow menu and drag-to-reorder
 * remain on the existing tree_view View row during the incremental migration.
 */
@Composable
fun TreeRowCard(
    title: String,
    details: String,
    derived: Boolean,
    exhausted: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    FamilyTreeTheme {
        val scheme = MaterialTheme.colorScheme
        val surface = when {
            exhausted -> scheme.surfaceVariant.copy(alpha = 0.8f)
            derived -> scheme.tertiaryContainer
            else -> scheme.surface
        }
        val firstLetter = title.trim().take(1).uppercase().ifBlank { "•" }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                // ComposeView intercepts taps: forward them to the existing
                // tree row handler so opening and drag-to-reorder still work.
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 62.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = CircleShape,
                    color = if (exhausted) scheme.outlineVariant else scheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = firstLetter,
                            style = MaterialTheme.typography.titleLarge,
                            color = if (exhausted) scheme.onSurfaceVariant else scheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title.ifBlank { "—" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (exhausted) scheme.onSurfaceVariant else scheme.onSurface
                    )
                    if (details.isNotBlank()) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
