package com.rootdroid.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rootdroid.inspector.ui.theme.*

@Composable
fun PermissionsScreen(
    overlayGranted: Boolean,
    usageGranted: Boolean,
    onGrantOverlay: () -> Unit,
    onGrantUsage: () -> Unit,
    onContinue: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text(
                    "Setup",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Text(
                    "Grant permissions to enable all features",
                    fontSize = 13.sp,
                    color = TextSecond,
                )
            }

            // ── Permission cards ──────────────────────────────────────────────
            PermissionCard(
                icon = Icons.Default.Layers,
                title = "Display over other apps",
                description = "Required — shows the debug overlay when a container app is running.",
                granted = overlayGranted,
                required = true,
                onGrant = onGrantOverlay,
            )

            PermissionCard(
                icon = Icons.Default.QueryStats,
                title = "Usage access",
                description = "Optional — improves process detection in the overlay.",
                granted = usageGranted,
                required = false,
                onGrant = onGrantUsage,
            )

            // ── Continue ──────────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                enabled = overlayGranted,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Background,
                    disabledContainerColor = SurfaceHigh,
                    disabledContentColor = TextMuted,
                ),
                shape = RoundedCornerShape(10.dp),
                elevation = ButtonDefaults.buttonElevation(0.dp),
            ) {
                Text(
                    if (overlayGranted) "Continue" else "Grant overlay permission to continue",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    required: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (granted) StatusGreen.copy(alpha = 0.35f) else Border,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (granted) StatusGreen.copy(alpha = 0.12f) else SurfaceHigh,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                Icon(Icons.Default.Check, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(18.dp))
            } else {
                Icon(icon, contentDescription = null, tint = TextSecond, modifier = Modifier.size(18.dp))
            }
        }

        // Text
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                if (required) {
                    Text(
                        "required",
                        fontSize = 9.sp,
                        color = StatusRed,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(StatusRed.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            Text(description, fontSize = 12.sp, color = TextSecond, lineHeight = 16.sp)
        }

        // Grant button
        if (!granted) {
            TextButton(
                onClick = onGrant,
                colors = ButtonDefaults.textButtonColors(contentColor = Accent),
            ) {
                Text("Grant", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}
