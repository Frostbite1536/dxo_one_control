package com.dxoone.multicam.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Large circular capture button for triggering multi-camera capture.
 *
 * @param cameraCount Total number of connected cameras
 * @param selectedCount Number of cameras selected (0 = all will be captured)
 * @param isCapturing Whether capture is in progress
 * @param enabled Whether the button is enabled
 * @param onClick Callback when button is clicked
 */
@Composable
fun CaptureButton(
    cameraCount: Int,
    selectedCount: Int = 0,
    isCapturing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Determine how many cameras will be captured
    val captureCount = if (selectedCount > 0) selectedCount else cameraCount
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.9f else 1f,
        label = "capture_button_scale"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    when {
                        isCapturing -> Color.Gray
                        !enabled -> Color.Gray.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                .border(
                    width = 4.dp,
                    color = Color.White.copy(alpha = 0.8f),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled && !isCapturing,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isCapturing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
            } else {
                // Inner white circle
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when {
                isCapturing -> "Capturing..."
                cameraCount == 0 -> "No Cameras"
                selectedCount > 0 -> "Capture ($selectedCount of $cameraCount)"
                else -> "Capture All ($cameraCount)"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled && !isCapturing) {
                MaterialTheme.colorScheme.onBackground
            } else {
                Color.Gray
            }
        )
    }
}
