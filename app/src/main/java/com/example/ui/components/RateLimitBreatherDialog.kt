package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.remote.RateLimitDistractionPrompt
import com.example.ui.theme.CrimsonBlade
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import kotlinx.coroutines.delay


/**
 * Fun anime trivia breather dialog shown when the user hits the 40th Wallhaven request
 * inside a 60-second window, naturally stalling for a few seconds to prevent HTTP 429 blocks.
 */
@Composable
fun RateLimitBreatherDialog(
    prompt: RateLimitDistractionPrompt,
    onDismiss: () -> Unit,
) {
    val totalSeconds = prompt.breatherSeconds.coerceAtLeast(1)
    var remainingSeconds by remember(prompt.id) { mutableIntStateOf(totalSeconds) }


    LaunchedEffect(prompt.id) {
        remainingSeconds = totalSeconds
        while (remainingSeconds > 0) {
            delay(1_000L)
            remainingSeconds -= 1
        }
    }


    val canDismiss = remainingSeconds <= 0
    val progressTarget = (totalSeconds - remainingSeconds).toFloat() / totalSeconds.toFloat()
    val animatedProgress by animateFloatAsState(
        targetValue = progressTarget.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "breather_progress",
    )


    Dialog(
        onDismissRequest = {
            if (canDismiss) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = canDismiss,
            dismissOnClickOutside = canDismiss,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ObsidianSurface,
            shadowElevation = 24.dp,
            border = BorderStroke(1.2.dp, CyberCyan.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 380.dp)
                .testTag("rate_limit_breather_dialog"),
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ObsidianSurfaceElevated,
                                ObsidianSurface,
                            ),
                        ),
                    )
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    CrimsonBlade.copy(alpha = 0.24f),
                                    ElectricViolet.copy(alpha = 0.28f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(26.dp),
                    )
                }


                Spacer(modifier = Modifier.height(12.dp))


                Surface(
                    color = CyberCyan.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.4f)),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "QUICK ANIME BREATHER • ${prompt.requestCount}/45 REQ",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = CyberCyan,
                        )
                    }
                }


                Spacer(modifier = Modifier.height(14.dp))


                Text(
                    text = prompt.headline,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = InkWhite,
                    textAlign = TextAlign.Center,
                )


                Spacer(modifier = Modifier.height(10.dp))


                Text(
                    text = prompt.funFact,
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkMuted,
                    textAlign = TextAlign.Center,
                )


                Spacer(modifier = Modifier.height(18.dp))


                LinearProgressIndicator(
                    progress = { animatedProgress },
                    color = if (canDismiss) CrimsonBlade else CyberCyan,
                    trackColor = HairlineBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50)),
                )


                Spacer(modifier = Modifier.height(18.dp))


                Button(
                    onClick = onDismiss,
                    enabled = canDismiss,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CrimsonBlade,
                        contentColor = InkWhite,
                        disabledContainerColor = ObsidianSurfaceElevated,
                        disabledContentColor = InkMuted,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("rate_limit_breather_continue_button"),
                ) {
                    Text(
                        text = if (canDismiss) {
                            "Continue Exploring"
                        } else {
                            "Recharging 4K Portal… ${remainingSeconds}s"
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}