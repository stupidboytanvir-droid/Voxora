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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.voxora.player.ui.theme.VoxoraAmber
import com.voxora.player.ui.theme.VoxoraAmberBright
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraHairlineBorder

/**
 * Signature VOXORA Cinema Spatial brand mark:
 * Warm Cinema Amber play glyph + luminous acoustic arcs inside an obsidian glass frame.
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
                    colors = listOf(Color(0xFF17140E), Color(0xFF0D0F16))
                )
            )
            .border(
                width = 0.8.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        VoxoraAmber.copy(alpha = 0.72f),
                        Color.White.copy(alpha = 0.18f)
                    )
                ),
                shape = RoundedCornerShape(size * 0.28f)
            )
            .semantics { contentDescription = logoDesc },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.70f)) {
            val w = this.size.width
            val h = this.size.height

            val playPath = Path().apply {
                moveTo(w * 0.14f, h * 0.18f)
                lineTo(w * 0.58f, h * 0.50f)
                lineTo(w * 0.14f, h * 0.82f)
                close()
            }
            val amberBrush = Brush.linearGradient(
                colors = listOf(VoxoraAmberBright, VoxoraAmber),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )

            drawPath(
                path = playPath,
                brush = amberBrush,
                style = Stroke(
                    width = w * 0.11f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.cornerPathEffect(w * 0.08f)
                )
            )
            drawPath(
                path = playPath,
                brush = amberBrush
            )

            drawArc(
                color = VoxoraAmberBright,
                startAngle = -48f,
                sweepAngle = 96f,
                useCenter = false,
                topLeft = Offset(w * 0.42f, h * 0.25f),
                size = Size(w * 0.34f, h * 0.50f),
                style = Stroke(width = w * 0.085f, cap = StrokeCap.Round)
            )

            drawArc(
                color = Color.White.copy(alpha = 0.85f),
                startAngle = -54f,
                sweepAngle = 108f,
                useCenter = false,
                topLeft = Offset(w * 0.50f, h * 0.12f),
                size = Size(w * 0.44f, h * 0.76f),
                style = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * Minimal flagship Home header: crisp VOXORA wordmark + single Search icon button.
 */
@Composable
fun VoxoraTopHeader(
    onSearchClick: () -> Unit,
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
            VoxoraBrandMark(size = 34.dp)
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
