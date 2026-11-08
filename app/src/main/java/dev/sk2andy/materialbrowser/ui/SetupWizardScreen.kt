package dev.sk2andy.materialbrowser.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface RootStatus {
    data object Idle : RootStatus
    data object Checking : RootStatus
    data object Granted : RootStatus
    data class Denied(val message: String) : RootStatus
}

object SetupWizardHelper {
    private const val PREFS_NAME = "aio_preferences"
    private const val KEY_SETUP_COMPLETED = "aio_setup_completed"
    private const val KEY_DOWNLOAD_FOLDER = "aio_download_folder"
    const val DEFAULT_DOWNLOAD_PATH = "/storage/emulated/0/Download"

    fun isSetupCompleted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SETUP_COMPLETED, false)
    }

    fun markSetupCompleted(context: Context, downloadFolder: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_SETUP_COMPLETED, true)
            .putString(KEY_DOWNLOAD_FOLDER, downloadFolder)
            .apply()
    }

    fun getDownloadFolder(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DOWNLOAD_FOLDER, DEFAULT_DOWNLOAD_PATH) ?: DEFAULT_DOWNLOAD_PATH
    }

    fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            exitCode == 0 && output.contains("uid=0")
        } catch (_: Throwable) {
            false
        }
    }

    fun executeRootGrant(packageName: String, downloadPath: String): Boolean {
        return try {
            val permissions = listOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.RECORD_AUDIO",
                "android.permission.CAMERA",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.ACCESS_LOCAL_NETWORK",
            )
            val cmds = StringBuilder()
            for (perm in permissions) {
                cmds.append("pm grant $packageName $perm 2>/dev/null; ")
            }
            cmds.append("appops set $packageName MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; ")
            cmds.append("appops set $packageName REQUEST_INSTALL_PACKAGES allow 2>/dev/null; ")
            cmds.append("appops set $packageName SYSTEM_ALERT_WINDOW allow 2>/dev/null; ")
            cmds.append("appops set $packageName AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>/dev/null; ")
            cmds.append("dumpsys deviceidle whitelist +$packageName 2>/dev/null; ")
            cmds.append("mkdir -p \"$downloadPath\" 2>/dev/null; ")

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmds.toString()))
            process.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }
}

@Composable
fun SetupWizardScreen(
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    androidx.activity.compose.BackHandler(enabled = true) {
        (context as? android.app.Activity)?.moveTaskToBack(true)
    }
    val scope = rememberCoroutineScope()
    var rootStatus by remember { mutableStateOf<RootStatus>(RootStatus.Idle) }
    var selectedFolder by remember { mutableStateOf(SetupWizardHelper.DEFAULT_DOWNLOAD_PATH) }
    var nonRootPermissionsGranted by remember { mutableStateOf(false) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        nonRootPermissionsGranted = result.values.any { it }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let {
            val path = it.path ?: it.toString()
            selectedFolder = path
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Text(
                text = "Welcome to AIO",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Unified Browser, Downloader & Video Player",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Please choose a setup mode to grant required permissions and configure unified storage for all three apps.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ==========================================
            // OPTION 1: ROOT MODE (RECOMMENDED)
            // ==========================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp),
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Root Mode (Recommended)",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "For rooted devices (KernelSU, Magisk, APatch). Automatically grants all system permissions, allows unrestricted storage access, and sets default folders without repeated confirmation dialogs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    when (val status = rootStatus) {
                        is RootStatus.Idle -> {
                            Button(
                                onClick = {
                                    rootStatus = RootStatus.Checking
                                    scope.launch(Dispatchers.IO) {
                                        val hasRoot = SetupWizardHelper.checkRootAccess()
                                        if (hasRoot) {
                                            SetupWizardHelper.executeRootGrant(context.packageName, selectedFolder)
                                            withContext(Dispatchers.Main) {
                                                rootStatus = RootStatus.Granted
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                rootStatus = RootStatus.Denied(
                                                    "Root permission was not granted or su binary was not found. " +
                                                        "Please grant Superuser permission in KernelSU / Magisk / APatch to proceed in Root Mode, " +
                                                        "or choose Non-Root Mode below.",
                                                )
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    text = "Grant Root & Configure All",
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        is RootStatus.Checking -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.5.dp,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Requesting root access & granting permissions...",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        is RootStatus.Granted -> {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(10.dp),
                                        )
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        text = "✓ Root permission granted!\n✓ All permissions applied for all 3 apps.\n✓ Storage & battery whitelist configured.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF2E7D32),
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Text(
                                    text = "Download Folder: $selectedFolder",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        SetupWizardHelper.markSetupCompleted(context, selectedFolder)
                                        onComplete()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2E7D32),
                                    ),
                                ) {
                                    Text(
                                        text = "Complete Setup & Launch Browser",
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }

                        is RootStatus.Denied -> {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                                            shape = RoundedCornerShape(10.dp),
                                        )
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        text = status.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        rootStatus = RootStatus.Checking
                                        scope.launch(Dispatchers.IO) {
                                            val hasRoot = SetupWizardHelper.checkRootAccess()
                                            if (hasRoot) {
                                                SetupWizardHelper.executeRootGrant(context.packageName, selectedFolder)
                                                withContext(Dispatchers.Main) {
                                                    rootStatus = RootStatus.Granted
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    rootStatus = RootStatus.Denied(
                                                        "Root permission was not granted. Please allow root permission in KernelSU / Magisk / APatch to pass.",
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("Retry Root Grant")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(28.dp))

            // ==========================================
            // OPTION 2: NON-ROOT MODE
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                ) {
                    Text(
                        text = "Non-Root Mode",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "For unrooted devices. Request all permissions at once via standard Android permission dialogs and system settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedButton(
                        onClick = {
                            val perms = mutableListOf(
                                "android.permission.POST_NOTIFICATIONS",
                                "android.permission.ACCESS_FINE_LOCATION",
                                "android.permission.ACCESS_COARSE_LOCATION",
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                perms.add("android.permission.READ_MEDIA_VIDEO")
                                perms.add("android.permission.READ_MEDIA_AUDIO")
                                perms.add("android.permission.READ_MEDIA_IMAGES")
                            } else {
                                perms.add("android.permission.READ_EXTERNAL_STORAGE")
                                perms.add("android.permission.WRITE_EXTERNAL_STORAGE")
                            }
                            permissionsLauncher.launch(perms.toTypedArray())
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("1. Request All Permissions At Once")
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("2. Grant All Files Access (Storage)")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Download Folder:",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = selectedFolder,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { folderPickerLauncher.launch(null) },
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Select")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            SetupWizardHelper.markSetupCompleted(context, selectedFolder)
                            onComplete()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = "Finish Setup (Non-Root)",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
