package com.voxora.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxora.player.R
import com.voxora.player.ui.theme.LocalVoxoraColors
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraSubtleBlue

/**
 * Original VOXORA brand mark: modern rounded play triangle fused with sound-wave arcs.
 */
@Composable
fun VoxoraBrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp
) {
    val logoDesc = stringResource(R.string.cd_voxora_logo)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF181526), Color(0xFF101624))
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        VoxoraElectricPurple.copy(alpha = 0.65f),
                        VoxoraSubtleBlue.copy(alpha = 0.45f)
                    )
                ),
                shape = RoundedCornerShape(size * 0.28f)
            )
            .semantics { contentDescription = logoDesc },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.72f)) {
            val w = this.size.width
            val h = this.size.height

            val playPath = Path().apply {
                moveTo(w * 0.14f, h * 0.18f)
                lineTo(w * 0.58f, h * 0.50f)
                lineTo(w * 0.14f, h * 0.82f)
                close()
            }
            val symbolBrush = Brush.linearGradient(
                colors = listOf(VoxoraElectricPurple, Color(0xFF6366F1), VoxoraSubtleBlue),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )

            drawPath(
                path = playPath,
                brush = symbolBrush,
                style = Stroke(
                    width = w * 0.11f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.cornerPathEffect(w * 0.08f)
                )
            )
            drawPath(
                path = playPath,
                brush = symbolBrush
            )

            // Sound-wave inner arc
            drawArc(
                brush = symbolBrush,
                startAngle = -48f,
                sweepAngle = 96f,
                useCenter = false,
                topLeft = Offset(w * 0.42f, h * 0.25f),
                size = Size(w * 0.34f, h * 0.50f),
                style = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
            )

            // Sound-wave outer arc
            drawArc(
                color = VoxoraSubtleBlue,
                startAngle = -54f,
                sweepAngle = 108f,
                useCenter = false,
                topLeft = Offset(w * 0.50f, h * 0.12f),
                size = Size(w * 0.44f, h * 0.76f),
                style = Stroke(width = w * 0.085f, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun VoxoraTopHeader(
    onSearchClick: () -> Unit,
    onOpenFileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val voxoraColors = LocalVoxoraColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VoxoraBrandMark(size = 40.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "VOXORA",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.labelMedium,
                    color = voxoraColors.blueHighlight
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onOpenFileClick,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .testTag("header_open_file_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = stringResource(R.string.cd_pick_file),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .testTag("header_search_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.cd_search),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .testTag("header_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.cd_settings),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}
