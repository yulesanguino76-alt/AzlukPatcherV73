package com.azluk.patcher.ui.screens

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.azluk.patcher.core.*
import com.azluk.patcher.ui.theme.*
import com.azluk.patcher.viewmodel.PatchViewModel
import java.io.File
import java.io.FileInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchScreen(pkg: String, navController: NavController, vm: PatchViewModel = viewModel()) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val ctx      = LocalContext.current
    val logState = rememberLazyListState()

    // Auto-scroll log to bottom
    val logLines = when (val ps = state.patchState) {
        is PatchState.Running -> ps.log
        is PatchState.Success -> ps.log
        is PatchState.Failure -> ps.log
        else -> emptyList()
    }
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) logState.animateScrollToItem(logLines.size - 1)
    }

    // Install result receiver
    DisposableEffect(Unit) {
        val filter = IntentFilter("com.azluk.patcher.INSTALL_RESULT_LOCAL")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
                val msg    = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    confirm?.let { ctx.startActivity(it) }
                } else {
                    vm.onInstallResult(status, msg)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            ctx.registerReceiver(receiver, filter)
        onDispose { ctx.unregisterReceiver(receiver) }
    }

    Scaffold(
        containerColor = AzlukBg,
        topBar = {
            TopAppBar(
                title = { Text("Patch", color = AzlukOnBg, fontWeight = FontWeight.SemiBold) },
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
                .padding(horizontal = 14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(6.dp))

            // Patch type selection
            Text("Patch Types", color = AzlukOnBg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Surface(color = AzlukSurface, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(8.dp)) {
                    PatchType.values().forEach { type ->
                        val selected = type in state.selectedPatches
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.togglePatch(type) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked  = selected,
                                onCheckedChange = { vm.togglePatch(type) },
                                colors   = CheckboxDefaults.colors(
                                    checkedColor   = AzlukBlue,
                                    uncheckedColor = AzlukOnSurface
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(type.displayName, color = AzlukOnBg, fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium)
                                Text(type.description, color = AzlukOnSurface, fontSize = 11.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (type != PatchType.values().last()) {
                            Divider(color = AzlukSurfaceVar, thickness = .5.dp,
                                modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }
            }

            // Patch button
            val isIdle = state.patchState is PatchState.Idle || state.patchState is PatchState.Failure
            Button(
                onClick  = { if (isIdle) vm.patch(pkg) },
                enabled  = isIdle && state.selectedPatches.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = AzlukBlue),
                shape    = RoundedCornerShape(14.dp)
            ) {
                if (state.patchState is PatchState.Running) {
                    CircularProgressIndicator(
                        color    = Color.White,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Patching…", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Build, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start Patch", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            // Log output
            AnimatedVisibility(logLines.isNotEmpty()) {
                Surface(
                    color  = AzlukSurface,
                    shape  = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Log", color = AzlukOnSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.heightIn(max = 220.dp)) {
                            LazyColumn(state = logState) {
                                items(logLines) { line ->
                                    Text(
                                        line,
                                        color    = if (line.startsWith("✗")) AzlukError
                                                   else if (line.startsWith("Done")) AzlukSuccess
                                                   else AzlukOnSurface,
                                        fontSize = 11.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Success card + install button
            if (state.patchState is PatchState.Success) {
                val outputPath = (state.patchState as PatchState.Success).outputPath
                Surface(
                    color  = AzlukSuccess.copy(alpha = .08f),
                    shape  = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AzlukSuccess.copy(alpha = .3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("✅ Patch complete!", color = AzlukSuccess, fontWeight = FontWeight.Bold)
                        Text(outputPath, color = AzlukOnSurface, fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { installApk(ctx, outputPath) },
                                modifier = Modifier.weight(1f),
                                colors   = ButtonDefaults.buttonColors(containerColor = AzlukBlue),
                                shape    = RoundedCornerShape(10.dp)
                            ) { Text("Install", fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = { vm.resetPatch() },
                                modifier = Modifier.weight(1f),
                                shape    = RoundedCornerShape(10.dp),
                                border   = BorderStroke(1.dp, AzlukSurfaceVar)
                            ) { Text("Reset", color = AzlukOnSurface) }
                        }
                    }
                }
            }

            // Install result
            when (val ist = state.installState) {
                is InstallState.Success -> {
                    Surface(
                        color  = AzlukSuccess.copy(alpha = .08f),
                        shape  = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AzlukSuccess.copy(alpha = .3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("✅ Installed successfully!",
                            color    = AzlukSuccess,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(14.dp))
                    }
                }
                is InstallState.Failure -> {
                    Surface(
                        color  = AzlukError.copy(alpha = .08f),
                        shape  = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AzlukError.copy(alpha = .3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("❌ ${ist.code}", color = AzlukError, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(ist.message, color = AzlukOnBg, fontSize = 12.sp)
                            Text(ist.description, color = AzlukOnSurface, fontSize = 11.sp)
                        }
                    }
                }
                else -> {}
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun installApk(ctx: Context, path: String) {
    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(ctx, "APK not found", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val pi      = ctx.packageManager.packageInstaller
        val params  = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val session = pi.openSession(pi.createSession(params))
        FileInputStream(file).use { fis ->
            session.openWrite("base.apk", 0, file.length()).use { os ->
                fis.copyTo(os)
                session.fsync(os)
            }
        }
        val intent = Intent("com.azluk.patcher.INSTALL_RESULT")
        val flags  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi2 = PendingIntent.getBroadcast(ctx, 0, intent, flags)
        session.commit(pi2.intentSender)
        session.close()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
