package andromedvn.heuristic.activity.tracker.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import andromedvn.heuristic.activity.tracker.utils.SPONSOR_URL
import andromedvn.heuristic.activity.tracker.utils.openExternalWebLink

/**
 * One-time, non-blocking support ask. Shown the first time the user reaches
 * theme customization, never again, with no nag surfaces anywhere else.
 * Any exit - button, back gesture, outside tap - dismisses it permanently
 * because the caller persists "seen" in every path.
 */
@Composable
fun SupportPromptDialog(onDismissed: () -> Unit) {
    val context = LocalContext.current
    HatOutlinedDialog(
        onDismissRequest = onDismissed,
        title = "One quick thing"
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "HAT is built and maintained by one person. No company behind it, " +
                    "no ads, no trackers, and no server holding your data anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "I'm going full-time building apps like this. If HAT has earned " +
                    "its place on your phone, your support makes that possible. " +
                    "It stays optional. Every feature works exactly the same either way.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismissed) {
                    Text(
                        "Maybe later",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(onClick = {
                    if (!context.openExternalWebLink(SPONSOR_URL)) {
                        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
                    }
                    onDismissed()
                }) {
                    Text(
                        "Support my work",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
