package com.dxoone.multicam.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dxoone.multicam.domain.model.CameraUiState
import com.dxoone.multicam.usb.ConnectionState

/**
 * Card component displaying a single camera preview and status.
 */
@Composable
fun CameraPreviewCard(
    camera: CameraUiState,
    onDisconnect: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Live view or placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (camera.liveViewFrame != null) {
                    Image(
                        bitmap = camera.liveViewFrame.asImageBitmap(),
                        contentDescription = "Live view from ${camera.displayName}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    // Placeholder
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray
                    )
                }

                // Connection status indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            when (camera.connectionState) {
                                ConnectionState.CONNECTED -> Color.Green
                                ConnectionState.INITIALIZING -> Color.Yellow
                                ConnectionState.ERROR -> Color.Red
                                ConnectionState.DISCONNECTED -> Color.Gray
                            }
                        )
                )
            }

            // Camera info bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = camera.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Battery indicator
                        if (camera.batteryLevel != null) {
                            Icon(
                                imageVector = Icons.Filled.BatteryFull,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = when {
                                    camera.batteryLevel > 50 -> Color.Green
                                    camera.batteryLevel > 20 -> Color.Yellow
                                    else -> Color.Red
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${camera.batteryLevel}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        // Connection state text
                        Text(
                            text = when (camera.connectionState) {
                                ConnectionState.CONNECTED -> "Connected"
                                ConnectionState.INITIALIZING -> "Connecting..."
                                ConnectionState.ERROR -> "Error"
                                ConnectionState.DISCONNECTED -> "Disconnected"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (camera.connectionState) {
                                ConnectionState.CONNECTED -> Color.Green
                                ConnectionState.INITIALIZING -> Color.Yellow
                                ConnectionState.ERROR -> Color.Red
                                ConnectionState.DISCONNECTED -> Color.Gray
                            }
                        )
                    }
                }

                // Action buttons
                Row {
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Rename camera",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onDisconnect,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Disconnect camera",
                            modifier = Modifier.size(18.dp),
                            tint = Color.Red
                        )
                    }
                }
            }
        }
    }
}
