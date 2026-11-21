package com.voxora.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
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
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraHairlineBorder
import com.voxora.player.ui.theme.VoxoraSunsetAmber
import com.voxora.player.ui.theme.VoxoraSunsetCoral
import com.voxora.player.ui.theme.VoxoraSunsetGradientColors
import com.voxora.player.ui.theme.VoxoraSunsetOrange

/**
 * Signature VOXORA Play-Wave Emblem (v2.6.0):
 * Sunset Amber-to-Coral (#F59E0B -> #F97316 -> #EC4899) Cinema Play Prism + Sound-Wave bars
 * inside a deep OLED Obsidian glass frame — matching the adaptive launcher & notification icons.
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
                Brush.radialGradient(
                    colors = listOf(
                        VoxoraSunsetOrange.copy(alpha = 0.22f),
                        Color(0xFF0A0C12),
                        Color(0xFF050608)
                    )
                )
            )
            .border(
                width = 0.9.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        VoxoraSunsetAmber.copy(alpha = 0.8f),
                        VoxoraSunsetOrange.copy(alpha = 0.65f),
                        VoxoraSunsetCoral.copy(alpha = 0.7f)
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

            val sunsetBrush = Brush.linearGradient(
                colors = VoxoraSunsetGradientColors,
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )

            // Rounded Play Prism
            val playPath = Path().apply {
                moveTo(w * 0.12f, h * 0.17f)
                lineTo(w * 0.64f, h * 0.50f)
                lineTo(w * 0.12f, h * 0.83f)
                close()
            }
            drawPath(
                path = playPath,
                brush = sunsetBrush,
                style = Stroke(
                    width = w * 0.10f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.cornerPathEffect(w * 0.08f)
                )
            )
            drawPath(path = playPath, brush = sunsetBrush)

            // Sound-Wave Bar 1
            val barWidth = w * 0.105f
            val barCorner = CornerRadius(barWidth / 2f, barWidth / 2f)
            drawRoundRect(
                brush = sunsetBrush,
                topLeft = Offset(w * 0.71f, h * 0.24f),
                size = Size(barWidth, h * 0.52f),
                cornerRadius = barCorner
            )

            // Sound-Wave Bar 2
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(VoxoraSunsetOrange, VoxoraSunsetCoral),
                    start = Offset(w * 0.80f, h * 0.30f),
                    end = Offset(w, h * 0.72f)
                ),
                topLeft = Offset(w * 0.86f, h * 0.36f),
                size = Size(barWidth, h * 0.28f),
                cornerRadius = barCorner
            )
        }
    }
}

/**
 * Minimal flagship Home header: crisp VOXORA Play-Wave wordmark + subtle History clock icon + Search icon.
 */
@Composable
fun VoxoraTopHeader(
    onSearchClick: () -> Unit,
    onHistoryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VoxoraBrandMark(size = 36.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "VOXORA",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.2.sp,
                    fontSize = 19.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (onHistoryClick != null) {
                Surface(
                    onClick = onHistoryClick,
                    shape = CircleShape,
                    color = VoxoraCardCharcoal,
                    border = BorderStroke(0.8.dp, VoxoraHairlineBorder),
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("header_history_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = stringResource(R.string.cd_history),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }

            Surface(
                onClick = onSearchClick,
                shape = CircleShape,
                color = VoxoraCardCharcoal,
                border = BorderStroke(0.8.dp, VoxoraHairlineBorder),
                modifier = Modifier
                    .size(44.dp)
                    .testTag("header_search_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.cd_search),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
}
