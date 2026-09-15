package com.azluk.patcher.ui.screens

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.navigation.NavController
import com.azluk.patcher.ui.theme.*
import com.azluk.patcher.utils.StorageUtils
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchedFilesScreen(navController: NavController) {
    val ctx   = LocalContext.current
    var files by remember { mutableStateOf(StorageUtils.getPatchedFiles()) }
    val fmt   = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    fun refresh() { files = StorageUtils.getPatchedFiles() }

    Scaffold(
        containerColor = AzlukBg,
        topBar = {
            TopAppBar(
                title = { Text("Patched Files", color = AzlukOnBg, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, null, tint = AzlukOnSurface)
                    }
                },
                actions = {
                    IconButton(onClick = ::refresh) {
                        Icon(Icons.Default.Refresh, null, tint = AzlukOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AzlukSurface)
            )
        }
    ) { padding ->
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No patched files yet", color = AzlukOnSurface)
            }
        } else {
            LazyColumn(
                modifier       = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files, key = { it.absolutePath }) { file ->
                    PatchedFileCard(
                        file      = file,
                        date      = fmt.format(Date(file.lastModified())),
                        onInstall = { installApk(ctx, file) },
                        onDelete  = { file.delete(); refresh() }
                    )
                }
            }
        }
    }
}

@Composable
fun PatchedFileCard(file: File, date: String, onInstall: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color  = AzlukSurface,
        shape  = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    color      = AzlukOnBg,
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${String.format("%.1f", file.length() / 1048576f)} MB · $date",
                    color    = AzlukOnSurface,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick  = onInstall,
                    shape    = RoundedCornerShape(8.dp),
                    border   = BorderStroke(1.dp, AzlukBlue),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("Install", color = AzlukBlue, fontSize = 12.sp) }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, null, tint = AzlukError, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun installApk(ctx: Context, file: File) {
    val pi     = ctx.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val id     = pi.createSession(params)
    val session = pi.openSession(id)
    FileInputStream(file).use { fis ->
        session.openWrite("base.apk", 0, file.length()).use { os ->
            fis.copyTo(os); session.fsync(os)
        }
    }
    val i    = Intent("com.azluk.patcher.INSTALL_RESULT")
    val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    session.commit(PendingIntent.getBroadcast(ctx, id, i, flag).intentSender)
    session.close()
}
