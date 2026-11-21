package com.voxora.player.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.request.videoFrameOption
import com.voxora.player.R
import com.voxora.player.model.MediaPermissionState
import com.voxora.player.model.PlaybackHistoryItem
import com.voxora.player.model.VideoFolder
import com.voxora.player.model.VideoItem
import com.voxora.player.ui.theme.LocalVoxoraColors
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraAmber
import com.voxora.player.ui.theme.VoxoraAmberBright
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraElevatedCharcoal
import com.voxora.player.ui.theme.VoxoraHairlineBorder
import com.voxora.player.ui.theme.VoxoraSunsetBrush
import com.voxora.player.ui.theme.VoxoraSunsetHorizontalBrush
import com.voxora.player.ui.theme.VoxoraSunsetOrange
import com.voxora.player.util.FormatUtils
import com.voxora.player.util.TitleFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-memory LRU fallback cache for MediaMetadataRetriever frames (fixes black/missing .mkv frames).
 */
private object MkvFrameFallbackCache {
    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024L / 16L).toInt().coerceIn(8 * 1024, 32 * 1024)
    private val cache = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bmp: Bitmap) {
        if (bmp.byteCount > 0) cache.put(key, bmp)
    }

    suspend fun extractBrightFrame(
        context: Context,
        uriString: String,
        preferredMs: Long
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${uriString}@${preferredMs / 1000L}"
        get(cacheKey)?.let { return@withContext it }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uriString))
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val candidateTimesMs = buildList {
                if (preferredMs > 0L) add(preferredMs)
                add(8_000L)
                add(5_000L)
                if (durationMs > 20_000L) add(durationMs / 6L)
                add(2_000L)
            }.map { t ->
                if (durationMs > 2_000L) t.coerceIn(1_000L, durationMs - 500L) else t
            }.distinct()

            var bestBitmap: Bitmap? = null
            for (ms in candidateTimesMs) {
                val frame = runCatching {
                    retriever.getScaledFrameAtTime(
                        ms * 1_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        480,
                        270
                    )
                }.getOrNull() ?: runCatching {
                    retriever.getFrameAtTime(ms * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()

                if (frame != null) {
                    if (!isNearlyBlackFrame(frame)) {
                        bestBitmap = frame
                        break
                    } else if (bestBitmap == null) {
                        bestBitmap = frame
                    } else {
                        frame.recycle()
                    }
                }
            }
            bestBitmap?.also { put(cacheKey, it) }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isNearlyBlackFrame(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true
        var sumLuma = 0L
        var count = 0
        val stepX = (w / 6).coerceAtLeast(1)
        val stepY = (h / 6).coerceAtLeast(1)
        var y = stepY / 2
        while (y < h) {
            var x = stepX / 2
            while (x < w) {
                val px = bitmap.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                sumLuma += (r * 3 + g * 6 + b) / 10
                count++
                x += stepX
            }
            y += stepY
        }
        return count == 0 || (sumLuma / count) < 12L
    }
}

/**
 * Reliable, non-black Video & MKV Thumbnail loader:
 * - Targets 5s–10s into the video by default (avoiding black studio/fade-in intro frames).
 * - Uses Coil VideoFrameDecoder with disk+memory caching and smooth crossfade.
 * - Automatically falls back to MediaMetadataRetriever multi-timestamp luminance probing if
 *   Coil fails on `.mkv` containers or returns an empty frame.
 */
@Composable
fun VideoThumbnail(
    uriString: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    frameTimeMs: Long = 7_000L,
    durationMs: Long = 0L
) {
    val context = LocalContext.current
    val resolvedFrameMs = remember(frameTimeMs, durationMs) {
        when {
            durationMs in 1_000L..12_000L -> (durationMs / 3L).coerceAtLeast(1_000L)
            durationMs > 12_000L -> frameTimeMs.coerceIn(5_000L, (durationMs - 1_000L).coerceAtLeast(5_000L))
            else -> frameTimeMs.coerceAtLeast(5_000L)
        }
    }

    var coilFailed by remember(uriString, resolvedFrameMs) { mutableStateOf(false) }
    var fallbackBitmap by remember(uriString, resolvedFrameMs) {
        mutableStateOf(
            uriString?.let { MkvFrameFallbackCache.get("${it}@${resolvedFrameMs / 1000L}") }
        )
    }

    val isMkv = remember(uriString) {
        uriString?.contains(".mkv", ignoreCase = true) == true
    }

    LaunchedEffect(uriString, resolvedFrameMs, coilFailed) {
        if (!uriString.isNullOrBlank() && (coilFailed || (isMkv && fallbackBitmap == null))) {
            fallbackBitmap = MkvFrameFallbackCache.extractBrightFrame(context, uriString, resolvedFrameMs)
        }
    }

    Box(
        modifier = modifier.background(VoxoraElevatedCharcoal),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Movie,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.16f),
            modifier = Modifier.size(26.dp)
        )

        val bmp = fallbackBitmap
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (!uriString.isNullOrBlank()) {
            AsyncImage(
                model = remember(uriString, resolvedFrameMs) {
                    ImageRequest.Builder(context)
                        .data(Uri.parse(uriString))
                        .videoFrameMillis(resolvedFrameMs)
                        .videoFrameOption(MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(250)
                        .build()
                },
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onError = { coilFailed = true },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Signature Sunset Amber-to-Coral Episode Pill Badge (`S01 · E06` / `EP 06`).
 */
@Composable
fun EpisodeBadgePill(
    badgeText: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false
) {
    if (filled) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(7.dp))
                .background(VoxoraSunsetBrush)
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
            )
        }
    } else {
        Surface(
            color = VoxoraSunsetOrange.copy(alpha = 0.18f),
            shape = RoundedCornerShape(7.dp),
            border = BorderStroke(0.8.dp, VoxoraSunsetOrange.copy(alpha = 0.55f)),
            modifier = modifier
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = VoxoraAmberBright,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
            )
        }
    }
}

@Composable
fun ContinueWatchingCard(
    item: PlaybackHistoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatted = remember(item.title) { TitleFormatter.format(item.title) }
    Card(
        modifier = modifier
            .width(236.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("continue_watching_card_${item.uriString.hashCode()}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = VoxoraCardCharcoal),
        border = BorderStroke(0.8.dp, VoxoraHairlineBorder)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                VideoThumbnail(
                    uriString = item.uriString,
                    contentDescription = formatted.cleanTitle,
                    frameTimeMs = item.lastPositionMs.coerceAtLeast(6_000L),
                    durationMs = item.durationMs,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xB8050608))
                            )
                        )
                )

                formatted.episodeBadge?.let { badge ->
                    EpisodeBadgePill(
                        badgeText = badge,
                        filled = true,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    )
                }

                Surface(
                    color = Color(0xCC050608),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = FormatUtils.formatDuration(item.durationMs),
                        style = TabularTimeStyle,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Thin Warm Amber watched progress bar
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.18f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(item.progressFraction)
                            .background(VoxoraSunsetHorizontalBrush)
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = formatted.cleanTitle,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${FormatUtils.formatDuration(item.lastPositionMs)} watched · ${item.folderName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Flagship Cinema List Row Card for Videos & Folder Detail.
 */
@Composable
fun VideoRowCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lastPositionMs: Long? = null,
    onMoreClick: (() -> Unit)? = null
) {
    val voxoraColors = LocalVoxoraColors.current
    val formatted = remember(video.title) { TitleFormatter.format(video.title) }
    val resolution = FormatUtils.formatResolution(video.width, video.height)
    val sizeText = FormatUtils.formatFileSize(video.sizeBytes)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("video_item_${video.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(0.8.dp, voxoraColors.cardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(136.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                VideoThumbnail(
                    uriString = video.uriString,
                    contentDescription = formatted.cleanTitle,
                    frameTimeMs = 7_000L,
                    durationMs = video.durationMs,
                    modifier = Modifier.fillMaxSize()
                )
                val topLeftBadge = formatted.episodeBadge ?: resolution
                if (topLeftBadge != null) {
                    if (formatted.episodeBadge != null) {
                        EpisodeBadgePill(
                            badgeText = topLeftBadge,
                            filled = true,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(5.dp)
                        )
                    } else {
                        Surface(
                            color = Color(0xCC050608),
                            shape = RoundedCornerShape(5.dp),
                            border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.2f)),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(5.dp)
                        ) {
                            Text(
                                text = topLeftBadge,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                            )
                        }
                    }
                }
                if (video.durationMs > 0L) {
                    Surface(
                        color = Color(0xD6050608),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(5.dp)
                    ) {
                        Text(
                            text = FormatUtils.formatDuration(video.durationMs),
                            style = TabularTimeStyle,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (lastPositionMs != null && lastPositionMs > 2_000L && video.durationMs > 0L) {
                    val progress = (lastPositionMs.toFloat() / video.durationMs.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.22f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(VoxoraSunsetHorizontalBrush)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = formatted.cleanTitle,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (resolution != null && formatted.episodeBadge != null) {
                        Text(
                            text = resolution,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = VoxoraAmberBright
                        )
                        Text("·", color = voxoraColors.textMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        text = video.folderName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (sizeText.isNotBlank()) {
                        Text(
                            text = sizeText,
                            style = TabularTimeStyle,
                            color = voxoraColors.textMuted
                        )
                    }
                    val ext = video.displayName.substringAfterLast('.', "").uppercase()
                    if (ext.isNotBlank()) {
                        Text(
                            text = ext,
                            style = TabularTimeStyle,
                            color = VoxoraAmberBright.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            if (onMoreClick != null) {
                IconButton(
                    onClick = onMoreClick,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("video_more_${video.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.cd_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Flagship 2-Column Cinema Grid Card for Videos Tab (16:9 poster + top-left quality/episode badge
 * + bottom-right duration pill + thin Amber watched progress bar).
 */
@Composable
fun VideoGridCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lastPositionMs: Long? = null,
    onMoreClick: (() -> Unit)? = null
) {
    val voxoraColors = LocalVoxoraColors.current
    val formatted = remember(video.title) { TitleFormatter.format(video.title) }
    val resolution = FormatUtils.formatResolution(video.width, video.height)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("video_grid_item_${video.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(0.8.dp, voxoraColors.cardBorder)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                VideoThumbnail(
                    uriString = video.uriString,
                    contentDescription = formatted.cleanTitle,
                    frameTimeMs = 7_000L,
                    durationMs = video.durationMs,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0x33050608), Color.Transparent, Color(0x99050608))
                            )
                        )
                )

                val topBadge = formatted.episodeBadge ?: resolution
                if (topBadge != null) {
                    if (formatted.episodeBadge != null) {
                        EpisodeBadgePill(
                            badgeText = topBadge,
                            filled = true,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(7.dp)
                        )
                    } else {
                        Surface(
                            color = Color(0xCC050608),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.22f)),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(7.dp)
                        ) {
                            Text(
                                text = topBadge,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (video.durationMs > 0L) {
                    Surface(
                        color = Color(0xD6050608),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(7.dp)
                    ) {
                        Text(
                            text = FormatUtils.formatDuration(video.durationMs),
                            style = TabularTimeStyle,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (lastPositionMs != null && lastPositionMs > 2_000L && video.durationMs > 0L) {
                    val progress = (lastPositionMs.toFloat() / video.durationMs.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.22f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(VoxoraSunsetHorizontalBrush)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatted.cleanTitle,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${video.folderName} · ${FormatUtils.formatFileSize(video.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onMoreClick != null) {
                    IconButton(
                        onClick = onMoreClick,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.cd_more_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sleek 2-Column Visual Media Folder Grid Card with real folder preview frame, video count badge,
 * clean folder name, and total size.
 */
@Composable
fun FolderGridCard(
    folder: VideoFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val voxoraColors = LocalVoxoraColors.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("folder_card_${folder.bucketId}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(0.8.dp, voxoraColors.cardBorder)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
            ) {
                if (folder.previewUriString != null) {
                    VideoThumbnail(
                        uriString = folder.previewUriString,
                        contentDescription = folder.name,
                        frameTimeMs = 7_000L,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(VoxoraElevatedCharcoal),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = VoxoraAmber.copy(alpha = 0.65f),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0x22050608), Color.Transparent, Color(0xB8050608))
                            )
                        )
                )
                Surface(
                    color = Color(0xCC050608),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.7.dp, VoxoraAmber.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "${folder.videoCount}",
                        style = TabularTimeStyle.copy(fontWeight = FontWeight.Bold),
                        color = VoxoraAmberBright,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.5.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val countLabel = if (folder.videoCount == 1) "1 video" else "${folder.videoCount} videos"
                val sizeLabel = if (folder.totalSizeBytes > 0L) " · ${FormatUtils.formatFileSize(folder.totalSizeBytes)}" else ""
                Text(
                    text = "$countLabel$sizeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun MediaPermissionBanner(
    permissionState: MediaPermissionState,
    onRequestPermission: () -> Unit,
    onOpenSafVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (permissionState == MediaPermissionState.FULL_ACCESS) return
    val voxoraColors = LocalVoxoraColors.current
    val isPartial = permissionState == MediaPermissionState.PARTIAL_ACCESS

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(0.8.dp, VoxoraAmber.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isPartial) "Partial Video Access Active" else "Access Local Videos",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isPartial) {
                    "VOXORA currently sees only the videos you selected. Grant full video access or manage your selection anytime."
                } else {
                    "Allow VOXORA to scan your local video folders via scoped storage, or open any individual video directly."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoxoraAmber,
                        contentColor = Color(0xFF140D00)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("grant_permission_button")
                ) {
                    Text(if (isPartial) "Manage Access" else "Grant Access", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onOpenSafVideo,
                    border = BorderStroke(0.8.dp, voxoraColors.cardBorder),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_saf_video_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open File")
                }
            }
        }
    }
}

@Composable
fun EmptyLibraryView(
    title: String,
    subtitle: String,
    onRefresh: () -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(VoxoraAmber.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.VideoLibrary,
                contentDescription = null,
                tint = VoxoraAmber,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.testTag("empty_refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Refresh")
            }
            Button(
                onClick = onOpenFile,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoxoraAmber,
                    contentColor = Color(0xFF140D00)
                ),
                modifier = Modifier.testTag("empty_open_file_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Pick Video", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
