package com.tunex.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.tunex.data.model.OutputMode
import com.tunex.data.model.ReverbPreset
import com.tunex.data.model.VirtualizerMode
import com.tunex.ui.components.*
import com.tunex.ui.theme.*
import com.tunex.ui.viewmodel.MainViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val advancedSettings by viewModel.advancedSettings.collectAsState()
    val advancedTrackingEnabled by viewModel.advancedTrackingEnabled.collectAsState()
    
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val dumpGranted = remember(refreshTrigger) { viewModel.isDumpPermissionGranted() }
    val notifGranted = remember(refreshTrigger) { viewModel.isNotificationListenerEnabled() }
    val packageName = context.packageName
    
    val scrollState = rememberScrollState()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        BackgroundGradientStart,
                        BackgroundGradientMid,
                        BackgroundGradientEnd
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Bar
            TopAppBar(
                title = {
                    Text(
                        "Advanced DSP",
                        style = TunexTypography.titleLarge,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp)
            ) {
                // Bass Boost Section
                AdvancedSettingCard(
                    icon = Icons.Outlined.GraphicEq,
                    title = "Bass Boost",
                    description = "Enhance low frequencies",
                    isEnabled = advancedSettings.bassBoostEnabled,
                    onEnabledChange = { enabled ->
                        viewModel.setBassBoost(enabled, advancedSettings.bassBoostStrength)
                    },
                    accentColor = EqBandColor1
                ) {
                    Column {
                        Text(
                            text = "Strength: ${(advancedSettings.bassBoostStrength / 10f).roundToInt()}%",
                            style = TunexTypography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalSliderWithLabel(
                            value = advancedSettings.bassBoostStrength / 1000f,
                            onValueChange = { value ->
                                viewModel.setBassBoost(
                                    advancedSettings.bassBoostEnabled,
                                    (value * 1000).roundToInt()
                                )
                            },
                            activeColor = EqBandColor1,
                            enabled = advancedSettings.bassBoostEnabled
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Virtualizer / 3D Audio Section
                AdvancedSettingCard(
                    icon = Icons.Outlined.Surround,
                    title = "3D Virtualizer",
                    description = "Create immersive spatial audio",
                    isEnabled = advancedSettings.virtualizerEnabled,
                    onEnabledChange = { enabled ->
                        viewModel.setVirtualizer(
                            enabled,
                            advancedSettings.virtualizerStrength,
                            advancedSettings.virtualizerMode
                        )
                    },
                    accentColor = TunexSecondary
                ) {
                    Column {
                        Text(
                            text = "Strength: ${(advancedSettings.virtualizerStrength / 10f).roundToInt()}%",
                            style = TunexTypography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalSliderWithLabel(
                            value = advancedSettings.virtualizerStrength / 1000f,
                            onValueChange = { value ->
                                viewModel.setVirtualizer(
                                    advancedSettings.virtualizerEnabled,
                                    (value * 1000).roundToInt(),
                                    advancedSettings.virtualizerMode
                                )
                            },
                            activeColor = TunexSecondary,
                            enabled = advancedSettings.virtualizerEnabled
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "Mode",
                            style = TunexTypography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            VirtualizerMode.values().take(3).forEach { mode ->
                                TunexChip(
                                    text = mode.displayName,
                                    selected = advancedSettings.virtualizerMode == mode,
                                    onClick = {
                                        viewModel.setVirtualizer(
                                            advancedSettings.virtualizerEnabled,
                                            advancedSettings.virtualizerStrength,
                                            mode
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Reverb Section
                AdvancedSettingCard(
                    icon = Icons.Outlined.Waves,
                    title = "Reverb",
                    description = "Add room ambience",
                    isEnabled = advancedSettings.reverbEnabled,
                    onEnabledChange = { enabled ->
                        viewModel.setReverb(
                            enabled,
                            advancedSettings.reverbPreset,
                            advancedSettings.reverbStrength
                        )
                    },
                    accentColor = TunexAccent
                ) {
                    Column {
                        Text(
                            text = "Preset",
                            style = TunexTypography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Reverb presets
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ReverbPreset.values().take(4).forEach { preset ->
                                    TunexChip(
                                        text = preset.displayName,
                                        selected = advancedSettings.reverbPreset == preset,
                                        onClick = {
                                            viewModel.setReverb(
                                                advancedSettings.reverbEnabled,
                                                preset,
                                                advancedSettings.reverbStrength
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ReverbPreset.values().drop(4).forEach { preset ->
                                    TunexChip(
                                        text = preset.displayName,
                                        selected = advancedSettings.reverbPreset == preset,
                                        onClick = {
                                            viewModel.setReverb(
                                                advancedSettings.reverbEnabled,
                                                preset,
                                                advancedSettings.reverbStrength
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "Strength: ${(advancedSettings.reverbStrength / 10f).roundToInt()}%",
                            style = TunexTypography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalSliderWithLabel(
                            value = advancedSettings.reverbStrength / 1000f,
                            onValueChange = { value ->
                                viewModel.setReverb(
                                    advancedSettings.reverbEnabled,
                                    advancedSettings.reverbPreset,
                                    (value * 1000).roundToInt()
                                )
                            },
                            activeColor = TunexAccent,
                            enabled = advancedSettings.reverbEnabled
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Loudness Enhancer
                AdvancedSettingCard(
                    icon = Icons.Outlined.VolumeUp,
                    title = "Loudness Enhancer",
                    description = "Boost perceived loudness",
                    isEnabled = advancedSettings.loudnessEnhancerEnabled,
                    onEnabledChange = { enabled ->
                        viewModel.setLoudnessEnhancer(enabled, advancedSettings.loudnessTargetGain)
                    },
                    accentColor = StatusWarning
                ) {
                    Column {
                        Text(
                            text = "Gain: +${(advancedSettings.loudnessTargetGain / 10f).roundToInt()}%",
                            style = TunexTypography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalSliderWithLabel(
                            value = advancedSettings.loudnessTargetGain / 1000f,
                            onValueChange = { value ->
                                viewModel.setLoudnessEnhancer(
                                    advancedSettings.loudnessEnhancerEnabled,
                                    (value * 1000).roundToInt()
                                )
                            },
                            activeColor = StatusWarning,
                            enabled = advancedSettings.loudnessEnhancerEnabled
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Stereo Width
                AdvancedSettingCard(
                    icon = Icons.Outlined.Headphones,
                    title = "Stereo Width",
                    description = "Adjust stereo separation",
                    isEnabled = advancedSettings.stereoWidthEnabled,
                    onEnabledChange = { enabled ->
                        viewModel.setStereoWidth(enabled, advancedSettings.stereoWidth)
                    },
                    accentColor = TunexPrimaryLight
                ) {
                    Column {
                        val widthPercent = ((advancedSettings.stereoWidth - 0.5f) / 1.5f * 100).roundToInt()
                        Text(
                            text = when {
                                advancedSettings.stereoWidth < 0.7f -> "Narrow ($widthPercent%)"
                                advancedSettings.stereoWidth < 1.2f -> "Normal ($widthPercent%)"
                                else -> "Wide ($widthPercent%)"
                            },
                            style = TunexTypography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalSliderWithLabel(
                            value = (advancedSettings.stereoWidth - 0.5f) / 1.5f,
                            onValueChange = { value ->
                                val newWidth = 0.5f + (value * 1.5f)
                                viewModel.setStereoWidth(
                                    advancedSettings.stereoWidthEnabled,
                                    newWidth
                                )
                            },
                            activeColor = TunexPrimaryLight,
                            enabled = advancedSettings.stereoWidthEnabled
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Dialog Enhancement
                AdvancedSettingCard(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = "Dialog Enhancement",
                    description = "Improve voice clarity",
                    isEnabled = advancedSettings.dialogEnhancementEnabled,
                    onEnabledChange = { enabled ->
                        viewModel.setDialogEnhancement(enabled, advancedSettings.dialogEnhancementLevel)
                    },
                    accentColor = StatusInfo
                ) {
                    Column {
                        Text(
                            text = "Level: ${(advancedSettings.dialogEnhancementLevel * 100).roundToInt()}%",
                            style = TunexTypography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalSliderWithLabel(
                            value = advancedSettings.dialogEnhancementLevel,
                            onValueChange = { value ->
                                viewModel.setDialogEnhancement(
                                    advancedSettings.dialogEnhancementEnabled,
                                    value
                                )
                            },
                            activeColor = StatusInfo,
                            enabled = advancedSettings.dialogEnhancementEnabled
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Output Mode
                SectionHeader(title = "Output Device")
                Spacer(modifier = Modifier.height(12.dp))
                
                GlassmorphicCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        OutputMode.values().forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable {
                                        viewModel.setOutputMode(mode)
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = advancedSettings.outputMode == mode,
                                    onClick = { viewModel.setOutputMode(mode) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = TunexPrimary,
                                        unselectedColor = TextTertiary
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = mode.displayName,
                                    style = TunexTypography.bodyMedium,
                                    color = if (advancedSettings.outputMode == mode) TextPrimary else TextSecondary
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Advanced Player Tracking (experimental)
                SectionHeader(title = "Advanced Player Tracking")
                Spacer(modifier = Modifier.height(12.dp))
                
                GlassmorphicCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Experimental. Finds sessions for apps that don't announce " +
                                "themselves (e.g. Spotify), on top of the normal tracking that " +
                                "already covers most players. Two permissions needed.",
                            style = TunexTypography.bodySmall,
                            color = TextTertiary
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        val dumpCommand = "adb shell pm grant $packageName android.permission.DUMP"
                        Text(
                            text = "DUMP permission - lets us read the list of currently " +
                                "playing sessions. Only grantable via ADB from a computer " +
                                "(tap to copy the command):",
                            style = TunexTypography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = dumpCommand,
                            style = TunexTypography.bodySmall,
                            color = TunexPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceContainerHigh)
                                .clickable { clipboard.setText(AnnotatedString(dumpCommand)) }
                                .padding(12.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PermissionStatusRow(granted = dumpGranted, label = "DUMP permission")
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = SurfaceContainerHigh)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "Notification access - lets us notice when a media " +
                                "player's notification appears, as a trigger to check for " +
                                "new sessions. We only check whether a notification is a " +
                                "media player's, never its content.",
                            style = TunexTypography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PermissionStatusRow(granted = notifGranted, label = "Notification access")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { context.startActivity(viewModel.notificationListenerSettingsIntent()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Notification Access Settings")
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { refreshTrigger++ },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Refresh status")
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = SurfaceContainerHigh)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable Advanced Tracking",
                                    style = TunexTypography.titleSmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (dumpGranted && notifGranted)
                                        "Both permissions granted"
                                    else "Needs both permissions above first",
                                    style = TunexTypography.bodySmall,
                                    color = TextTertiary
                                )
                            }
                            TunexSwitch(
                                checked = advancedTrackingEnabled,
                                onCheckedChange = { viewModel.setAdvancedTrackingEnabled(it) },
                                activeColor = TunexPrimary,
                                enabled = dumpGranted && notifGranted
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}

@Composable
private fun AdvancedSettingCard(
    icon: ImageVector,
    title: String,
    description: String,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        glassOpacity = if (isEnabled) 0.1f else 0.06f
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isEnabled) accentColor.copy(alpha = 0.2f) 
                                else SurfaceContainerHigh
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isEnabled) accentColor else TextTertiary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column {
                        Text(
                            text = title,
                            style = TunexTypography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = description,
                            style = TunexTypography.bodySmall,
                            color = TextTertiary
                        )
                    }
                }
                
                TunexSwitch(
                    checked = isEnabled,
                    onCheckedChange = onEnabledChange,
                    activeColor = accentColor
                )
            }
            
            AnimatedVisibility(
                visible = isEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = CardBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    content()
                }
            }
        }
    }
}

// Extension to support icon
private val Icons.Outlined.Surround: ImageVector
    get() = Icons.Outlined.SurroundSound

@Composable
private fun PermissionStatusRow(granted: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (granted) StatusSuccess else TextTertiary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label - ${if (granted) "granted" else "not granted"}",
            style = TunexTypography.bodySmall,
            color = if (granted) TextPrimary else TextTertiary
        )
    }
}
