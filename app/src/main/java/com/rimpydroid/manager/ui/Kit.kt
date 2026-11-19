package com.rimpydroid.manager.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Small building blocks shared by Settings, the welcome guide and the help page.

/** A titled group of settings in a bordered, rounded frame, so that neighbouring groups never run together. */
@Composable
fun SettingsCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        AppPanel(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** The line between two settings inside a [SettingsCard]. */
@Composable
fun CardDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** One setting: a title, an optional explanation and an optional control at the end. */
@Composable
fun CardRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val base = if (onClick != null) modifier.fillMaxWidth().clickable(onClick = onClick) else modifier.fillMaxWidth()
    Row(base.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrEmpty()) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) trailing()
    }
}

/** The round "( ? )" button that opens the help. */
@Composable
fun QuestionButton(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier
                .size(26.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Text("?", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A choice between a few big options (the selected one has a heavier frame). */
@Composable
fun OptionCard(selected: Boolean, title: String, body: String, onClick: () -> Unit, badge: String? = null) {
    val shape = MaterialTheme.shapes.medium
    val glass = LocalGlass.current
    val dark = LocalDarkTheme.current
    // in Liquid glass the glass has its own rim: only the chosen card gets a frame of its own
    val frame = when {
        selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        glass -> null
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (glass) Modifier.glassBackground(shape, dark) else Modifier)
            .clip(shape)
            .then(if (frame != null) Modifier.border(frame, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) Tag(badge, MaterialTheme.colorScheme.primary)
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Page dots of a step-by-step guide. */
@Composable
fun StepDots(count: Int, index: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            Box(
                Modifier
                    .size(if (i == index) 18.dp else 8.dp, 8.dp)
                    .clip(CircleShape)
                    .background(if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** The languages as chips, "Automatic" (follow the phone) first. [selected] is a language code or empty. */
@Composable
fun LanguageChips(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = selected.isBlank(), onClick = { onSelect("") }, label = { Text(tr("Automatic")) })
        for (language in Lang.ALL) {
            FilterChip(selected = selected == language.code, onClick = { onSelect(language.code) }, label = { Text(language.nativeName) })
        }
    }
}
