package com.azluk.patcher.ui.screens

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.navigation.NavController
import com.azluk.patcher.ui.theme.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(pkg: String, navController: NavController) {
    val ctx = LocalContext.current
    val pm  = ctx.packageManager

    val (appName, apkSize, version, isSystem) = remember(pkg) {
        try {
            val ai = pm.getApplicationInfo(pkg, 0)
            val pi = pm.getPackageInfo(pkg, 0)
            val size = File(ai.sourceDir).length() / (1024f * 1024f)
            val sys  = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            arrayOf(pm.getApplicationLabel(ai).toString(), size, pi.versionName ?: "?", sys)
        } catch (e: Exception) {
            arrayOf(pkg, 0f, "?", false)
        }
    }

    Scaffold(
        containerColor = AzlukBg,
        topBar = {
            TopAppBar(
                title = { Text(appName as String, color = AzlukOnBg, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, null, tint = AzlukOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AzlukSurface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Info card
            Surface(
                color  = AzlukSurface,
                shape  = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Package", pkg)
                    InfoRow("Version", version as String)
                    InfoRow("APK Size", String.format("%.1f MB", apkSize as Float))
                    InfoRow("Type", if (isSystem as Boolean) "System App" else "User App")
                }
            }

            // Big Patch button
            Button(
                onClick  = { navController.navigate("patch/$pkg") },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = AzlukBlue),
                shape    = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Build, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Patch This App", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AzlukOnSurface, fontSize = 13.sp)
        Text(value, color = AzlukOnBg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
