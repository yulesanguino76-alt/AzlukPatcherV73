package com.azluk.patcher.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.azluk.patcher.ui.screens.DetailScreen
import com.azluk.patcher.ui.screens.HomeScreen
import com.azluk.patcher.ui.screens.PatchScreen
import com.azluk.patcher.ui.screens.PatchedFilesScreen
import com.azluk.patcher.ui.screens.ToolsScreen
import com.azluk.patcher.ui.theme.AzlukTheme

class MainActivity : ComponentActivity() {

    private val permissionState = mutableStateOf(false)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionState.value = hasRequiredPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionState.value = hasRequiredPermission()

        setContent {
            AzlukTheme {
                val hasPermission by permissionState
                if (hasPermission) {
                    AzlukApplication()
                } else {
                    PermissionRequiredScreen(
                        onRequestPermission = { requestRequiredPermission() }
                    )
                }
            }
        }

        if (!hasRequiredPermission()) requestRequiredPermission()
    }

    override fun onResume() {
        super.onResume()
        permissionState.value = hasRequiredPermission()
    }

    private fun hasRequiredPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermission() {
        if (hasRequiredPermission()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (_: Exception) {}
            }
        } else {
            permLauncher.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }
    }
}

@Composable
private fun AzlukApplication() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen(navController) }
        composable(
            "detail/{pkg}",
            arguments = listOf(navArgument("pkg") { type = NavType.StringType })
        ) { back ->
            val pkg = back.arguments?.getString("pkg") ?: return@composable
            DetailScreen(pkg = pkg, navController = navController)
        }
        composable(
            "patch/{pkg}",
            arguments = listOf(navArgument("pkg") { type = NavType.StringType })
        ) { back ->
            val pkg = back.arguments?.getString("pkg") ?: return@composable
            PatchScreen(pkg = pkg, navController = navController)
        }
        composable("patched") { PatchedFilesScreen(navController) }
        composable("tools")   { ToolsScreen(navController) }
    }
}

@Composable
private fun PermissionRequiredScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Permiso necesario", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "AzlukPatcher necesita acceso completo al almacenamiento para parchear APKs.",
            modifier = Modifier.padding(bottom = 24.dp)
        )
        Button(onClick = onRequestPermission) { Text("Conceder permiso") }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val msg    = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val local  = Intent("com.azluk.patcher.INSTALL_RESULT_LOCAL").apply {
            putExtra(PackageInstaller.EXTRA_STATUS, status)
            putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, msg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(Intent.EXTRA_INTENT,
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            } else {
                @Suppress("DEPRECATION")
                putExtra(Intent.EXTRA_INTENT,
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            }
        }
        ctx.sendBroadcast(local)
    }
}
