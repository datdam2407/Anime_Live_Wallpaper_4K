package com.example.ui.screens


import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.kuroanimelivewallpapers.app.R
import com.example.ui.theme.CrimsonBlade
import com.example.ui.theme.CrimsonGlow
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianVoid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


// Wallhaven https://wallhaven.cc/w/5g12j5
const val SPLASH_WALLPAPER_THUMB_URL =
    "https://th.wallhaven.cc/lg/5g/5g12j5.jpg"
const val SPLASH_WALLPAPER_JPG_URL =
    "https://w.wallhaven.cc/full/5g/wallhaven-5g12j5.jpg"
const val SPLASH_WALLPAPER_PNG_URL =
    "https://w.wallhaven.cc/full/5g/wallhaven-5g12j5.png"


const val SPLASH_DURATION_MS = 5_000L
private const val SPLASH_HANDOFF_ANIMATION_MS = 400L
private const val SPLASH_PRELOAD_WINDOW_MS = SPLASH_DURATION_MS - SPLASH_HANDOFF_ANIMATION_MS


@Composable
fun SplashScreen(
    isPreloadComplete: Boolean = false,
    onFinished: () -> Unit,
) {
    val contentAlpha = remember { Animatable(0f) }
    val hudAlpha = remember { Animatable(1f) }
    val progress = remember { Animatable(0.08f) }
    val imageZoom = remember { Animatable(1.0f) }


    val latestPreloadComplete by rememberUpdatedState(isPreloadComplete)
    val latestOnFinished by rememberUpdatedState(onFinished)


    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()
        launch {
            imageZoom.animateTo(
                targetValue = 1.08f,
                animationSpec = tween(durationMillis = SPLASH_DURATION_MS.toInt(), easing = LinearOutSlowInEasing),
            )
        }
        contentAlpha.animateTo(1f, animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing))
        progress.animateTo(0.38f, animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing))


        // Hold inside the 5-second preload window while initial API feed, thumbnails, and Ads warm up
        while (true) {
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= SPLASH_PRELOAD_WINDOW_MS) {
                break
            }
            val fraction = (elapsed.toFloat() / SPLASH_PRELOAD_WINDOW_MS.toFloat()).coerceIn(0f, 1f)
            val targetProgress = if (latestPreloadComplete) {
                (0.38f + fraction * 0.56f).coerceAtMost(0.94f)
            } else {
                (0.38f + fraction * 0.50f).coerceAtMost(0.88f)
            }
            if (targetProgress > progress.value) {
                progress.snapTo(targetProgress)
            }
            delay(50L)
        }


        progress.animateTo(1f, animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))
        // Smooth pre-handoff flourish during the final ~160ms of the 5,000ms window
        launch {
            hudAlpha.animateTo(0f, animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing))
        }
        launch {
            imageZoom.animateTo(1.10f, animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing))
        }
        val remainingMs = (SPLASH_DURATION_MS - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)
        if (remainingMs > 0L) {
            delay(remainingMs)
        }
        latestOnFinished()
    }


    val context = LocalContext.current
    val fullResCandidates = remember {
        listOf(
            SPLASH_WALLPAPER_JPG_URL,
            SPLASH_WALLPAPER_PNG_URL,
            SPLASH_WALLPAPER_THUMB_URL,
        )
    }
    var candidateIndex by remember { mutableIntStateOf(0) }
    val activeFullUrl = fullResCandidates[candidateIndex.coerceAtMost(fullResCandidates.lastIndex)]


    val thumbRequest = remember(context) {
        ImageRequest.Builder(context)
            .data(SPLASH_WALLPAPER_THUMB_URL)
            .size(1080, 1920)
            .precision(Precision.INEXACT)
            .scale(Scale.FILL)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .allowHardware(true)
            .build()
    }


    val fullRequest = remember(context, activeFullUrl) {
        ImageRequest.Builder(context)
            .data(activeFullUrl)
            .size(1440, 2560)
            .precision(Precision.INEXACT)
            .scale(Scale.FILL)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .allowHardware(true)
            .crossfade(220)
            .build()
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .testTag("splash_screen"),
    ) {
        // Clean atmospheric backdrop behind Wallhaven 5g12j5 while image streams in
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            ObsidianVoid,
                            Color(0xFF140D24),
                            ObsidianVoid,
                        ),
                    ),
                ),
        )


        // Subtle Ken Burns zoom motion on Wallhaven 5g12j5 artwork
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(imageZoom.value),
        ) {
            AsyncImage(
                model = thumbRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            AsyncImage(
                model = fullRequest,
                contentDescription = "Kuro anime splash wallpaper 5g12j5",
                contentScale = ContentScale.Crop,
                onError = {
                    if (candidateIndex < fullResCandidates.lastIndex) {
                        candidateIndex += 1
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }


        // Top atmospheric gradient scrim so title & progress bar remain sharp and high-contrast
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.42f)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            ObsidianVoid.copy(alpha = 0.88f),
                            ObsidianVoid.copy(alpha = 0.58f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )


        // Bottom atmospheric gradient scrim framing "Loading..." cleanly
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.24f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            ObsidianVoid.copy(alpha = 0.70f),
                            ObsidianVoid.copy(alpha = 0.95f),
                        ),
                    ),
                ),
        )


        // Top title & glowing horizontal loading bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp, start = 28.dp, end = 28.dp)
                .alpha(contentAlpha.value * hudAlpha.value),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(id = R.string.splash_headline),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.6.sp,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.85f),
                        offset = Offset(0f, 3f),
                        blurRadius = 10f,
                    ),
                ),
                color = InkWhite,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("splash_headline"),
            )


            Spacer(modifier = Modifier.height(6.dp))


            Text(
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = 0.6.sp,
                ),
                color = InkWhite.copy(alpha = 0.78f),
                textAlign = TextAlign.Center,
            )


            Spacer(modifier = Modifier.height(18.dp))


            Box(
                modifier = Modifier
                    .width(216.dp)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(InkWhite.copy(alpha = 0.24f))
                    .border(
                        width = 0.5.dp,
                        color = InkWhite.copy(alpha = 0.18f),
                        shape = CircleShape,
                    )
                    .testTag("splash_progress_bar"),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.value.coerceIn(0.06f, 1f))
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    InkWhite,
                                    Color(0xFFFFD8E6),
                                    CrimsonGlow,
                                    CrimsonBlade,
                                ),
                            ),
                        ),
                )
            }
        }


        // Bottom "Loading..." label
        Text(
            text = stringResource(id = R.string.splash_loading),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.9f),
                    offset = Offset(0f, 2f),
                    blurRadius = 8f,
                ),
            ),
            color = InkWhite.copy(alpha = 0.86f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 26.dp)
                .alpha(contentAlpha.value * hudAlpha.value)
                .testTag("splash_loading_text"),
        )
    }
}



