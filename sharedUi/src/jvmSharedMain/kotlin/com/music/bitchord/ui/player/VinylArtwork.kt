package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

/**
 * A read-only artwork renderer. It neither owns a player nor issues playback commands.
 * The record's texture and the tonearm are drawn procedurally; only the label rotates.
 */
@Composable
internal fun VinylArtwork(
    artRequest: ImageRequest,
    artLoaded: Boolean,
    onArtState: (AsyncImagePainter.State) -> Unit,
    isPlaying: Boolean,
    position: PlaybackPosition,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val reduceMotion by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val rotation = remember { mutableFloatStateOf(0f) }
    val spinning = isPlaying && position.advancing && !reduceMotion &&
        lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    // Read the angle only inside the graphics layer, not in composition.
    // Cancellation on pause/stop preserves the last angle. 360 / 1.8 s = 33 1/3 RPM.
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
                    rotation.floatValue = (rotation.floatValue + elapsed / 5_000_000f) % 360f
                }
                lastFrame = frame
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Box(Modifier.size(side)) {
            // Stationary grooves and highlights simulate fixed lighting on the spinning disc.
            Canvas(Modifier.fillMaxSize()) {
                val s = size.minDimension
                val c = Offset(s * .48f, s * .54f)
                val r = s * .43f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Black.copy(alpha = .55f), Color.Transparent),
                        center = c + Offset(0f, s * .012f),
                        radius = r * 1.09f,
                    ),
                    radius = r * 1.09f,
                    center = c + Offset(0f, s * .012f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF191B20), Color(0xFF111216), Color(0xFF272930), Color(0xFF090A0D)),
                        center = c,
                        radius = r,
                    ),
                    radius = r,
                    center = c,
                )
                for (i in 0..25) {
                    val groove = s * (.185f + i * .0092f)
                    drawCircle(
                        color = Color.White.copy(alpha = if (i % 5 == 0) .13f else .045f),
                        radius = groove,
                        center = c,
                        style = Stroke(width = (s * .0012f).coerceAtLeast(.5f)),
                    )
                }
                drawCircle(
                    color = Color(0xFF41434A),
                    radius = r * .985f,
                    center = c,
                    style = Stroke(width = s * .003f),
                )
                drawArc(
                    color = Color.White.copy(alpha = .09f),
                    startAngle = 205f,
                    sweepAngle = 58f,
                    useCenter = false,
                    topLeft = c - Offset(r * .78f, r * .78f),
                    size = Size(r * 1.56f, r * 1.56f),
                    style = Stroke(width = s * .025f, cap = StrokeCap.Round),
                )
            }

            Box(
                modifier = Modifier.align(Alignment.Center)
                    .offset(x = side * -.02f, y = side * .04f)
                    .size(side * .36f)
                    .graphicsLayer {
                        rotationZ = rotation.floatValue
                        shape = CircleShape
                        clip = true
                    }
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

            // Read progress only in the draw scope. Seeks immediately reposition the stylus.
            Canvas(Modifier.fillMaxSize()) {
                val s = size.minDimension
                val center = Offset(s * .48f, s * .54f)
                drawCircle(Color(0xFF101216), s * .017f, center)
                drawCircle(Color(0xFFCED2D7), s * .009f, center)

                val pivot = Offset(s * .87f, s * .13f)
                val fraction = progress().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
                val angle = (91.0 + 25.0 * fraction) * kotlin.math.PI / 180.0
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                val tip = pivot + direction * (s * .54f)
                val head = tip - direction * (s * .045f)
                val elbow = pivot + direction * (s * .35f) + Offset(s * .018f, 0f)
                val shade = Offset(s * .009f, s * .011f)
                drawCircle(Color.Black.copy(alpha = .45f), s * .049f, pivot + shade)
                drawLine(Color.Black.copy(alpha = .5f), pivot + shade, elbow + shade, s * .025f, StrokeCap.Round)
                drawLine(Color.Black.copy(alpha = .5f), elbow + shade, tip + shade, s * .025f, StrokeCap.Round)
                drawLine(Color(0xFF777E86), pivot, elbow, s * .018f, StrokeCap.Round)
                drawLine(Color(0xFFB7BEC5), elbow, head, s * .016f, StrokeCap.Round)
                drawLine(Color(0xFFE8EAED), pivot, elbow, s * .006f, StrokeCap.Round)
                drawLine(Color(0xFF2E333A), head, tip, s * .034f, StrokeCap.Round)
                drawLine(Color(0xFFC4C9CE), head, tip, s * .019f, StrokeCap.Round)
                drawCircle(Color(0xFF16191E), s * .049f, pivot)
                drawCircle(Color(0xFF9BA2AA), s * .037f, pivot)
                drawCircle(Color(0xFF4D535B), s * .023f, pivot)
                drawCircle(Color(0xFFD5D9DE), s * .012f, pivot)
            }
        }
    }
}
