package com.rootdroid.inspector.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.rootdroid.inspector.ui.theme.*
import com.rootdroid.inspector.virtual.ContainerApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    containerApps: List<ContainerApp>,
    installingPkg: String?,           // non-null while install is in progress
    onAddApp: () -> Unit,
    onLaunch: (ContainerApp) -> Unit,
    onRemove: (ContainerApp) -> Unit,
    getIcon: (String) -> Drawable?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Virtual Space",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = TextPrimary,
                    )
                },
                actions = {
                    // App count badge
                    if (containerApps.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .background(SurfaceHigh, RoundedCornerShape(20.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                "${containerApps.size} installed",
                                fontSize = 11.sp,
                                color = TextSecond,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddApp,
                containerColor = Accent,
                contentColor = Background,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add app to space")
            }
        },
        containerColor = Background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalDivider(color = Border, thickness = 0.5.dp)

            if (containerApps.isEmpty() && installingPkg == null) {
                EmptyState()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Installing spinner card
                    if (installingPkg != null) {
                        item(key = "installing_$installingPkg") {
                            InstallingCard(pkg = installingPkg)
                        }
                    }

                    items(containerApps, key = { it.packageName }) { app ->
                        ContainerAppCard(
                            app    = app,
                            icon   = getIcon(app.packageName),
                            onLaunch = { onLaunch(app) },
                            onRemove = { onRemove(app) },
                        )
                    }
                }
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text("No apps in space", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextSecond)
            Text(
                "Tap + to install an app into the virtual container.\nIt gets its own isolated data folder and the overlay debugger launches automatically.",
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )
        }
    }
}

// ── Installing spinner ────────────────────────────────────────────────────────

@Composable
private fun InstallingCard(pkg: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .background(Surface, RoundedCornerShape(12.dp))
            .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            Text("Installing", fontSize = 10.sp, color = Accent, fontWeight = FontWeight.Medium)
            Text(
                pkg.split(".").last(),
                fontSize = 9.sp,
                color = TextMuted,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Container app card ────────────────────────────────────────────────────────

@Composable
private fun ContainerAppCard(
    app: ContainerApp,
    icon: Drawable?,
    onLaunch: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .background(Surface, RoundedCornerShape(12.dp))
            .border(0.5.dp, Border, RoundedCornerShape(12.dp))
            .clickable { onLaunch() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon.toBitmap(46, 46).asImageBitmap(),
                        contentDescription = app.appName,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("?", color = TextMuted, fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(7.dp))

            // Name
            Text(
                app.appName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp,
            )

            // Container badge
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text("container", fontSize = 8.sp, color = Accent, fontWeight = FontWeight.Medium)
            }
        }

        // Remove button
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(30.dp),
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove",
                tint = TextMuted,
                modifier = Modifier.size(13.dp),
            )
        }

        // Launch strip
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Accent.copy(alpha = 0.10f),
                    RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                )
                .padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = Accent, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
            Text("Launch", fontSize = 10.sp, color = Accent, fontWeight = FontWeight.Medium)
        }
    }
}
