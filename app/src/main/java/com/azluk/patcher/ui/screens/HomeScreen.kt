package com.azluk.patcher.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.azluk.patcher.R
import com.azluk.patcher.core.AppInfo
import com.azluk.patcher.core.PatchStatus
import com.azluk.patcher.ui.theme.*
import com.azluk.patcher.viewmodel.MainViewModel
import com.google.accompanist.drawablepainter.DrawablePainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    vm: MainViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.load()
    }

    Scaffold(
        containerColor = AzlukBg,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(AzlukSurface2, AzlukBg)
                        )
                    )
                    .padding(
                        horizontal = 16.dp,
                        vertical = 12.dp
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.mipmap.ic_launcher),
                            contentDescription = "AzlukPatcher",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(
                                    RoundedCornerShape(10.dp)
                                )
                        )

                        Spacer(
                            Modifier.width(10.dp)
                        )

                        Column {
                            Text(
                                "AzlukPatcher",
                                color = AzlukOnBg,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "V7",
                                color = AzlukBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Row {
                        IconButton(
                            onClick = {
                                navController.navigate("tools")
                            }
                        ) {
                            Icon(
                                Icons.Default.Build,
                                "Tools",
                                tint = AzlukOnSurface
                            )
                        }

                        IconButton(
                            onClick = {
                                navController.navigate("patched")
                            }
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                "Patched files",
                                tint = AzlukOnSurface
                            )
                        }

                        IconButton(
                            onClick = {
                                vm.refresh()
                            }
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                "Refresh",
                                tint = AzlukOnSurface
                            )
                        }
                    }
                }

                Spacer(
                    Modifier.height(10.dp)
                )

                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Search apps…",
                            color = AzlukOnSurface.copy(
                                alpha = .5f
                            )
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            null,
                            tint = AzlukOnSurface
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AzlukBlue,
                        unfocusedBorderColor = AzlukSurfaceVar,
                        cursorColor = AzlukBlue,
                        focusedTextColor = AzlukOnBg,
                        unfocusedTextColor = AzlukOnBg
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(
                    Modifier.height(8.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "User",
                        "System",
                        "All"
                    ).forEachIndexed { i, label ->

                        FilterChip(
                            selected = state.filter == i,
                            onClick = {
                                vm.setFilter(i)
                            },
                            label = {
                                Text(
                                    label,
                                    fontSize = 12.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AzlukBlue,
                                selectedLabelColor = Color.White,
                                containerColor = AzlukSurface,
                                labelColor = AzlukOnSurface
                            )
                        )
                    }

                    Spacer(
                        Modifier.weight(1f)
                    )

                    Text(
                        "${state.filteredApps.size} apps",
                        color = AzlukOnSurface,
                        fontSize = 12.sp,
                        modifier = Modifier.align(
                            Alignment.CenterVertically
                        )
                    )
                }
            }
        }
    ) { padding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            if (state.isLoading) {

                CircularProgressIndicator(
                    modifier = Modifier.align(
                        Alignment.Center
                    ),
                    color = AzlukBlue
                )

            } else {

                LazyColumn(
                    contentPadding = PaddingValues(
                        horizontal = 12.dp,
                        vertical = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(
                        6.dp
                    )
                ) {
                    items(
                        state.filteredApps,
                        key = {
                            it.packageName
                        }
                    ) { app ->

                        AppCard(
                            app = app,
                            onClick = {
                                navController.navigate(
                                    "detail/${app.packageName}"
                                )
                            }
                        )
                    }
                }
            }

            if (state.isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(
                            Alignment.BottomCenter
                        ),
                    color = AzlukBlue,
                    trackColor = AzlukSurface
                )
            }
        }
    }
}

@Composable
fun AppCard(
    app: AppInfo,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick
            ),
        color = AzlukSurface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(
                        RoundedCornerShape(10.dp)
                    )
                    .background(
                        AzlukSurface2
                    ),
                contentAlignment = Alignment.Center
            ) {

                if (app.icon != null) {

                    Image(
                        painter = rememberDrawablePainter(
                            app.icon
                        ),
                        contentDescription = app.appName,
                        modifier = Modifier.size(44.dp)
                    )

                } else {

                    Icon(
                        Icons.Default.Android,
                        null,
                        tint = AzlukOnSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(
                Modifier.width(12.dp)
            )

            Column(
                Modifier.weight(1f)
            ) {

                Text(
                    app.appName,
                    color = AzlukOnBg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    app.packageName,
                    color = AzlukOnSurface,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(
                    Modifier.height(4.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(
                        6.dp
                    )
                ) {

                    StatusChip(
                        app.patchStatus,
                        app.opportunityCount
                    )

                    if (app.isSystemApp) {

                        Text(
                            "SYS",
                            color = AzlukWarning,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .background(
                                    AzlukWarning.copy(
                                        alpha = .1f
                                    ),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(
                                    horizontal = 4.dp,
                                    vertical = 1.dp
                                )
                        )
                    }
                }
            }

            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = AzlukOnSurface.copy(
                    alpha = .4f
                )
            )
        }
    }
}

@Composable
fun StatusChip(
    status: PatchStatus,
    count: Int
) {
    val (color, label) = when (status) {

        PatchStatus.PATCHABLE ->
            AzlukSuccess to "Patchable ($count)"

        PatchStatus.LIKELY ->
            AzlukBlue to "Likely"

        PatchStatus.COMPLEX ->
            AzlukWarning to "Complex"

        PatchStatus.UNKNOWN ->
            AzlukOnSurface.copy(
                alpha = .4f
            ) to "Scanning…"
    }

    Text(
        label,
        color = color,
        fontSize = 10.sp,
        modifier = Modifier
            .background(
                color.copy(
                    alpha = .12f
                ),
                RoundedCornerShape(4.dp)
            )
            .padding(
                horizontal = 6.dp,
                vertical = 2.dp
            )
    )
}

@Composable
fun rememberDrawablePainter(
    drawable: Drawable
): androidx.compose.ui.graphics.painter.Painter {

    return remember(drawable) {

        object :
            androidx.compose.ui.graphics.painter.Painter() {

            override val intrinsicSize = Size(
                drawable.intrinsicWidth.toFloat(),
                drawable.intrinsicHeight.toFloat()
            )

            override fun DrawScope.onDraw() {

                drawIntoCanvas { canvas ->

                    drawable.setBounds(
                        0,
                        0,
                        size.width.toInt(),
                        size.height.toInt()
                    )

                    drawable.draw(
                        canvas.nativeCanvas
                    )
                }
            }
        }
    }
}
