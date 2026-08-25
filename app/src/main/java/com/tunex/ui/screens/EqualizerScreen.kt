package com.tunex.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunex.data.model.EqualizerPreset
import com.tunex.ui.components.*
import com.tunex.ui.theme.*
import com.tunex.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val equalizerBands by viewModel.equalizerBands.collectAsState()
    val bandFrequencies by viewModel.bandFrequencies.collectAsState()
    
    val scrollState = rememberScrollState()
    
    var parametricMode by remember { mutableStateOf(false) }
    var selectedBand by remember { mutableIntStateOf(0) }
    
    // Frequency labels for 10-band EQ
    val frequencyLabels = listOf(
        "31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K"
    )
    
    // EQ Band colors gradient
    val bandColors = listOf(
        EqBandColor1, EqBandColor1.copy(alpha = 0.9f),
        EqBandColor2, EqBandColor2.copy(alpha = 0.9f),
        EqBandColor3, EqBandColor3.copy(alpha = 0.9f),
        EqBandColor4, EqBandColor4.copy(alpha = 0.9f),
        EqBandColor5, EqBandColor5.copy(alpha = 0.9f)
    )
    
    var selectedPreset by remember { mutableStateOf<String?>(null) }
    
    // Frequency sliders use a log scale (20Hz-20kHz) since that's how pitch is perceived.
    fun sliderToFreq(t: Float): Float = 20f * Math.pow(1000.0, t.toDouble()).toFloat()
    fun freqToSlider(freq: Float): Float =
        (Math.log10((freq / 20f).toDouble()) / Math.log10(1000.0)).toFloat().coerceIn(0f, 1f)
    fun formatFreq(freq: Float): String =
        if (freq >= 1000f) "${"%.1f".format(freq / 1000f)} kHz" else "${freq.toInt()} Hz"
    
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
                        "Equalizer",
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
                actions = {
                    // Parametric mode toggle
                    IconButton(onClick = { parametricMode = !parametricMode }) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Toggle Parametric Mode",
                            tint = if (parametricMode) EqBandColor1 else TextSecondary
                        )
                    }
                    // Reset button
                    IconButton(onClick = { viewModel.resetEqualizer() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reset",
                            tint = TextSecondary
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
                // Current state indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PulsingIndicator(
                        color = if (uiState.isMasterEnabled) StatusSuccess else StatusError,
                        size = 8.dp,
                        isActive = uiState.isMasterEnabled
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            uiState.selectedProfile != null -> 
                                "${uiState.selectedProfile!!.brandName} ${uiState.selectedProfile!!.name}"
                            uiState.isUsingCustomEq -> "Custom Equalizer"
                            else -> "No Active Profile"
                        },
                        style = TunexTypography.bodyMedium,
                        color = TextSecondary
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Main Equalizer
                GlassmorphicCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    cornerRadius = 24.dp,
                    glassOpacity = 0.08f
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        // dB scale labels
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "+15 dB",
                                style = TunexTextStyles.frequencyLabel,
                                color = TextTertiary
                            )
                            Text(
                                text = "0 dB",
                                style = TunexTextStyles.frequencyLabel,
                                color = TextTertiary
                            )
                            Text(
                                text = "-15 dB",
                                style = TunexTextStyles.frequencyLabel,
                                color = TextTertiary
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Equalizer bands
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            equalizerBands.forEachIndexed { index, value ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(
                                            if (parametricMode) Modifier.clickable { selectedBand = index }
                                            else Modifier
                                        )
                                        .then(
                                            if (parametricMode && selectedBand == index) {
                                                Modifier.background(
                                                    bandColors[index].copy(alpha = 0.12f),
                                                    RoundedCornerShape(12.dp)
                                                )
                                            } else Modifier
                                        )
                                ) {
                                    EqualizerBandSlider(
                                        value = value,
                                        onValueChange = { newValue ->
                                            viewModel.setEqualizerBand(index, newValue)
                                            selectedPreset = null
                                        },
                                        frequencyLabel = if (parametricMode)
                                            formatFreq(bandFrequencies.getOrElse(index) { 1000f })
                                        else frequencyLabels[index],
                                        modifier = Modifier.fillMaxWidth(),
                                        barColor = bandColors[index],
                                        glowColor = bandColors[index].copy(alpha = 0.4f),
                                        enabled = uiState.isMasterEnabled
                                    )
                                }
                            }
                        }
                    }
                }
                
                AnimatedVisibility(visible = parametricMode) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Parametric frequency control for the selected band
                        GlassmorphicCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            glassOpacity = 0.08f
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    text = "Band ${selectedBand + 1} Frequency",
                                    style = TunexTypography.titleSmall,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Drag to move the center of this band. Gain is set above as usual.",
                                    style = TunexTypography.bodySmall,
                                    color = TextTertiary
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalSliderWithLabel(
                                    value = freqToSlider(bandFrequencies.getOrElse(selectedBand) { 1000f }),
                                    onValueChange = { t ->
                                        viewModel.setBandFrequency(selectedBand, sliderToFreq(t))
                                    },
                                    label = "",
                                    displayValue = formatFreq(bandFrequencies.getOrElse(selectedBand) { 1000f }),
                                    activeColor = bandColors[selectedBand],
                                    enabled = uiState.isMasterEnabled
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(28.dp))
                
                // Presets Section
                SectionHeader(title = "Quick Presets")
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Preset chips row 1
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(EqualizerPreset.systemPresets.take(5)) { preset ->
                        TunexChip(
                            text = preset.name,
                            selected = selectedPreset == preset.id,
                            onClick = {
                                selectedPreset = preset.id
                                viewModel.applyPreset(preset)
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Preset chips row 2
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(EqualizerPreset.systemPresets.drop(5)) { preset ->
                        TunexChip(
                            text = preset.name,
                            selected = selectedPreset == preset.id,
                            onClick = {
                                selectedPreset = preset.id
                                viewModel.applyPreset(preset)
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(28.dp))
                
                // Tips section
                GlassmorphicCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    glassOpacity = 0.06f
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Lightbulb,
                                contentDescription = null,
                                tint = StatusWarning,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Pro Tips",
                                style = TunexTypography.titleSmall,
                                color = TextPrimary
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "• Boost low frequencies (31-250Hz) for more bass\n" +
                                  "• Reduce mid-range (500Hz-2kHz) to minimize harshness\n" +
                                  "• Boost highs (8kHz-16kHz) for more clarity and sparkle\n" +
                                  "• Small adjustments (±3dB) often sound more natural",
                            style = TunexTypography.bodySmall,
                            color = TextSecondary,
                            lineHeight = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}
