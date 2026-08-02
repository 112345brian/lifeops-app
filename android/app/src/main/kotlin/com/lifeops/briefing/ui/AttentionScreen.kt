package com.lifeops.briefing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.briefing.data.AttentionReason
import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.severityDotColor

/**
 * "Widget equals status plus next move. Full app equals status plus reasons
 * plus controls" (docs/lifeops_capability_todo.md's Design Principles) --
 * this screen is the "reasons" half: the widget only ever shows the glyph
 * (severityDotColor) + headline, this renders every [AttentionReason]'s
 * domain/severity/title/due/recommended action as a real list.
 */
@Composable
fun AttentionScreen(briefing: BriefingState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        Row {
            Surface(
                color = severityDotColor(briefing.attentionState ?: "ok"),
                shape = CircleShape,
                modifier = Modifier.size(14.dp),
            ) {}
            Spacer(Modifier.height(0.dp))
            Text(
                text = " ${briefing.attentionLabel ?: (briefing.attentionState ?: "OK").uppercase()}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = briefing.attentionHeadline ?: "You are clear. Follow the next scheduled move.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))

        if (briefing.reasons.isEmpty()) {
            Text(
                text = "No open reasons -- nothing is pulling attention right now.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(text = "Why", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(briefing.reasons) { reason -> ReasonRow(reason) }
            }
        }
    }
}

@Composable
private fun ReasonRow(reason: AttentionReason) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row {
                Surface(
                    color = severityDotColor(reason.severity),
                    shape = CircleShape,
                    modifier = Modifier.size(10.dp).clip(CircleShape),
                ) {}
                Text(
                    text = "  ${reason.domain.uppercase()} · ${reason.severity.uppercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = reason.title ?: reason.domain,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            reason.due?.let {
                Text(text = "Due: $it", style = MaterialTheme.typography.bodySmall)
            }
            reason.recommendedAction?.let {
                Spacer(Modifier.height(4.dp))
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
