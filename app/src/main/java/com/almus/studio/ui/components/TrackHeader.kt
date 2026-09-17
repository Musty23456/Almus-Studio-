package com.almus.studio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.almus.studio.data.Track
import com.almus.studio.ui.theme.StudioRecord
import com.almus.studio.ui.theme.StudioSurface
import com.almus.studio.ui.theme.StudioTextSecondary

@Composable
fun TrackHeader(
    track: Track,
    isArmed: Boolean,
    onVolumeChange: (Float) -> Unit,
    onPanChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onToggleArm: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(180.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(StudioSurface)
            .padding(8.dp)
    ) {
        Text(track.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleChip(label = "M", active = track.muted, activeColor = StudioTextSecondary, onClick = onToggleMute)
            ToggleChip(label = "S", active = track.solo, activeColor = Color(0xFFFFD54F), onClick = onToggleSolo)
            ToggleChip(label = "R", active = isArmed, activeColor = StudioRecord, onClick = onToggleArm)
        }

        Spacer(Modifier.height(6.dp))
        Text("Vol ${track.volumeDb.toInt()} dB", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
        Slider(
            value = track.volumeDb,
            onValueChange = onVolumeChange,
            valueRange = -60f..12f,
            modifier = Modifier.height(24.dp)
        )

        Text("Pan ${"%.1f".format(track.pan)}", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
        Slider(
            value = track.pan,
            onValueChange = onPanChange,
            valueRange = -1f..1f,
            modifier = Modifier.height(24.dp)
        )
    }
}

@Composable
private fun ToggleChip(label: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) activeColor else Color.Black.copy(alpha = 0.3f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Color.Black else StudioTextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}
