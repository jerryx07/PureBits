/*
 * Adapted from PonyMusic's AlbumCoverView.kt.
 * Copyright 2024 wangchenyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for PureBits: Compose rendering, responsive square artwork slot,
 * existing Coil request and read-only playback state, lifecycle/reduced motion.
 */
package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.ponymusic_disc
import com.music.bitchord.sharedui.resources.ponymusic_needle
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.compose.resources.painterResource

/**
 * PonyMusic-style artwork only. The existing player owns all audio and playback.
 * The disc and circular cover rotate together; the separate needle pivots at its top.
 */
@Composable
internal fun VinylArtwork(
    artRequest: ImageRequest,
    artLoaded: Boolean,
    onArtState: (AsyncImagePainter.State) -> Unit,
    isPlaying: Boolean,
    position: PlaybackPosition,
    modifier: Modifier = Modifier,
) {
    val reduceMotion by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val playing = isPlaying && position.advancing
    val spinning = playing && !reduceMotion &&
        lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val rotation = remember { mutableFloatStateOf(0f) }
    // Match the upstream 20-second rotation rather than a physical 33 RPM platter.
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        var lastFrame = 0L
        while (isActive) {
            if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) {
                lastFrame = 0L
                delay(250)
                continue
            }
            withFrameNanos { frame ->
                if (lastFrame != 0L) {
                    val elapsed = (frame - lastFrame).coerceIn(0L, 100_000_000L)
                    rotation.floatValue = (rotation.floatValue + elapsed / 55_555_555.56f) % 360f
                }
                lastFrame = frame
            }
        }
    }
    val needleAngle by animateFloatAsState(
        targetValue = if (playing) 0f else -25f,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 300),
        label = "vinylNeedle",
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // Preserve the upstream 6:4 disc/label and 2:3.33 needle proportions,
        // with a slightly larger disc fitted into PureBits' square artwork slot.
        val unit = minOf(maxWidth / 7.5f, maxHeight / 8.3f)
        val discSize = unit * 6f
        val coverSize = unit * 4f
        val needleWidth = unit * 2f
        val needleHeight = unit * 3.33f
        val discTop = needleHeight / 1.5f
        val border = minOf(6.dp, unit * .14f)

        Box(
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = discTop - border)
                .size(discSize + border * 2f)
                .background(Color.White.copy(alpha = .10f), CircleShape),
        )
        Box(
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = discTop)
                .size(discSize)
                .graphicsLayer { rotationZ = rotation.floatValue },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(coverSize)
                    .clip(CircleShape)
                    .background(Color(0xFF30343C)),
                contentAlignment = Alignment.Center,
            ) {
                if (!artLoaded) {
                    Icon(
                        imageVector = BitChordIcons.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = .45f),
                        modifier = Modifier.fillMaxSize(.42f),
                    )
                }
                AsyncImage(
                    model = artRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = onArtState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Image(
                painter = painterResource(Res.drawable.ponymusic_disc),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Pivot at the original needle's top hub, not its geometric center.
        Image(
            painter = painterResource(Res.drawable.ponymusic_needle),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(x = needleWidth * (0.5f - 1f / 5.5f))
                .size(needleWidth, needleHeight)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(1f / 5.5f, 2f / (5.5f * 3.33f))
                    rotationZ = needleAngle
                },
        )
    }
}
