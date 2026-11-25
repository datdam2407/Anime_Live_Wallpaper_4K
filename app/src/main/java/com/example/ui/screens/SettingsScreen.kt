package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kuroanimelivewallpapers.app.R
import com.example.data.local.UserSettings
import com.example.domain.model.ResolutionTier
import com.example.ui.theme.CrimsonBlade
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianSurfaceHighest
import com.example.ui.theme.ObsidianVoid

private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    savedCount: Int = 0,
    onOpenSaved: () -> Unit = {},
    onResolutionSelected: (ResolutionTier) -> Unit,
    onSubmitWallpaperRequest: (email: String, content: String) -> Unit = { _, _ -> },
    showPrivacyOptionsButton: Boolean = false,
    onOpenPrivacyOptions: () -> Unit = {},
    scrollToTopTick: Int = 0,
    modifier: Modifier = Modifier,
) {
    var showRequestDialog by remember { mutableStateOf(false) }
    var showNotificationsDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    var lastHandledScrollTick by rememberSaveable { mutableIntStateOf(scrollToTopTick) }

    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick != lastHandledScrollTick) {
            lastHandledScrollTick = scrollToTopTick
            if (scrollState.value > 0) {
                scrollState.animateScrollTo(0)
            }
        }
    }

    if (showRequestDialog) {
        RequestWallpapersDialog(
            onDismiss = { showRequestDialog = false },
            onSubmit = { email, content ->
                showRequestDialog = false
                onSubmitWallpaperRequest(email, content)
            },
        )
    }

    if (showNotificationsDialog) {
        NotificationsPlaceholderDialog(
            onDismiss = { showNotificationsDialog = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Engine Settings",
                style = MaterialTheme.typography.headlineLarge,
                color = InkWhite,
            )

            // Saved Wallpapers Card (moved from Bottom Navigation into Settings)
            Surface(
                color = ObsidianSurfaceElevated,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, CrimsonBlade.copy(alpha = 0.40f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSaved)
                    .testTag("settings_saved_wallpapers_card"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Surface(
                            color = CrimsonBlade.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = null,
                                tint = CrimsonBlade,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(id = R.string.settings_saved_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = InkWhite,
                                )
                                Surface(
                                    color = CrimsonBlade.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text(
                                        text = stringResource(id = R.string.settings_saved_count, savedCount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CrimsonBlade,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(id = R.string.settings_saved_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkMuted,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(id = R.string.settings_saved_title),
                        tint = InkMuted,
                    )
                }
            }

            // Resolution Quality Card (DataStore)
            Surface(
                color = ObsidianSurfaceElevated,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.HighQuality,
                            contentDescription = null,
                            tint = CrimsonBlade,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Minimum API Resolution",
                            style = MaterialTheme.typography.titleMedium,
                            color = InkWhite,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enforces server filtering and resolution checks to prevent blurry wallpapers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkMuted,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ResolutionTier.entries.forEach { tier ->
                            FilterChip(
                                selected = settings.preferredResolution == tier,
                                onClick = { onResolutionSelected(tier) },
                                label = { Text(tier.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CrimsonBlade,
                                    selectedLabelColor = InkWhite,
                                    containerColor = ObsidianSurfaceHighest,
                                    labelColor = InkMuted,
                                ),
                            )
                        }
                    }
                }
            }

            // Requests & Updates Card (Request Wallpapers + Notifications)
            Surface(
                color = ObsidianSurfaceElevated,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.SupportAgent,
                            contentDescription = null,
                            tint = CyberCyan,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Community & Updates",
                            style = MaterialTheme.typography.titleMedium,
                            color = InkWhite,
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { showRequestDialog = true }
                            .padding(vertical = 8.dp)
                            .testTag("settings_request_wallpapers_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddPhotoAlternate,
                                contentDescription = null,
                                tint = CrimsonBlade,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Request Wallpapers",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = InkWhite,
                                )
                                Text(
                                    text = "Suggest anime series, characters, or live loops",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = InkMuted,
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Open Request Wallpapers dialog",
                            tint = InkMuted,
                        )
                    }

                    HorizontalDivider(color = HairlineBorder)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { showNotificationsDialog = true }
                            .padding(vertical = 8.dp)
                            .testTag("settings_notifications_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.NotificationsNone,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Notifications",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = InkWhite,
                                )
                                Text(
                                    text = "Check latest artwork & feature drops",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = InkMuted,
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Open Notifications popup",
                            tint = InkMuted,
                        )
                    }

                    if (showPrivacyOptionsButton) {
                        HorizontalDivider(color = HairlineBorder)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(onClick = onOpenPrivacyOptions)
                                .padding(vertical = 8.dp)
                                .testTag("settings_privacy_options_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PrivacyTip,
                                    contentDescription = null,
                                    tint = ElectricViolet,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = stringResource(id = R.string.settings_ad_privacy_title),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = InkWhite,
                                    )
                                    Text(
                                        text = stringResource(id = R.string.settings_ad_privacy_subtitle),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = InkMuted,
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = stringResource(id = R.string.settings_ad_privacy_title),
                                tint = InkMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestWallpapersDialog(
    onDismiss: () -> Unit,
    onSubmit: (email: String, content: String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var emailError by remember { mutableStateOf<String?>(null) }
    var contentError by remember { mutableStateOf<String?>(null) }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = CrimsonBlade,
        unfocusedBorderColor = HairlineBorder,
        focusedTextColor = InkWhite,
        unfocusedTextColor = InkWhite,
        focusedLabelColor = CrimsonBlade,
        unfocusedLabelColor = InkMuted,
        cursorColor = CrimsonBlade,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianSurface,
        titleContentColor = InkWhite,
        textContentColor = InkMuted,
        modifier = Modifier.testTag("request_wallpapers_dialog"),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AddPhotoAlternate,
                    contentDescription = null,
                    tint = CrimsonBlade,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Request Wallpapers",
                    style = MaterialTheme.typography.headlineSmall,
                    color = InkWhite,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        emailError = null
                    },
                    label = { Text("Email") },
                    placeholder = { Text("you@example.com") },
                    singleLine = true,
                    isError = emailError != null,
                    supportingText = emailError?.let { msg -> { Text(msg, color = CrimsonBlade) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = textFieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("request_email_input"),
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = {
                        content = it
                        contentError = null
                    },
                    label = { Text("What do you want to request?") },
                    placeholder = { Text("Anime series, character, 4K static or Live MP4 scene…") },
                    minLines = 3,
                    maxLines = 5,
                    isError = contentError != null,
                    supportingText = contentError?.let { msg -> { Text(msg, color = CrimsonBlade) } },
                    colors = textFieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("request_content_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedEmail = email.trim()
                    val trimmedContent = content.trim()
                    val validEmail = EMAIL_REGEX.matches(trimmedEmail)
                    val validContent = trimmedContent.isNotEmpty()
                    emailError = if (!validEmail) "Please enter a valid email address" else null
                    contentError = if (!validContent) "Please describe the wallpaper you want" else null
                    if (validEmail && validContent) {
                        onSubmit(trimmedEmail, trimmedContent)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlade),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("request_submit_button"),
            ) {
                Text("Submit", color = InkWhite)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = InkMuted)
            }
        },
    )
}

@Composable
private fun NotificationsPlaceholderDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianSurface,
        titleContentColor = InkWhite,
        textContentColor = InkMuted,
        modifier = Modifier.testTag("notifications_dialog"),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    tint = CyberCyan,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Notifications",
                    style = MaterialTheme.typography.headlineSmall,
                    color = InkWhite,
                )
            }
        },
        text = {
            Text(
                text = "New images are being updated... Stay tuned!",
                style = MaterialTheme.typography.bodyLarge,
                color = InkWhite,
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("notifications_ok_button"),
            ) {
                Text("OK", color = ObsidianVoid)
            }
        },
    )
}
