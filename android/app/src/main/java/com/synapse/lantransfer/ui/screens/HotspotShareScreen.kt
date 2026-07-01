package com.synapse.lantransfer.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synapse.lantransfer.data.model.SelectedFile
import com.synapse.lantransfer.data.service.TransferManager
import com.synapse.lantransfer.ui.components.GlassCard
import com.synapse.lantransfer.ui.theme.*
import com.synapse.lantransfer.util.HotspotManager
import com.synapse.lantransfer.util.QRCodeGenerator
import com.synapse.lantransfer.util.SimpleHttpFileServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HotspotShareScreen(
    onBack: () -> Unit,
    isDarkTheme: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transferManager = remember { TransferManager.getInstance(context) }
    val hotspotManager = remember { HotspotManager(context) }

    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf<List<SelectedFile>>(emptyList()) }
    
    var isHosting by remember { mutableStateOf(false) }
    var hotspotSsid by remember { mutableStateOf<String?>(null) }
    var hotspotPassword by remember { mutableStateOf<String?>(null) }
    
    var activePort by remember { mutableStateOf<Int?>(null) }
    var localIp by remember { mutableStateOf<String?>(null) }
    
    var httpFileServer by remember { mutableStateOf<SimpleHttpFileServer?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showWifiQrDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris = selectedUris + uris
            // Resolve selected files info
            scope.launch(Dispatchers.IO) {
                val resolved = uris.map { uri ->
                    var name = "File"
                    var size = 0L
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex != -1) name = cursor.getString(nameIndex)
                            if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                        }
                    }
                    SelectedFile(name = name, size = size, uri = uri.toString())
                }
                withContext(Dispatchers.Main) {
                    selectedFiles = selectedFiles + resolved
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineLocationGranted) {
            // Re-run host session start
            isHosting = true
        } else {
            errorMessage = "Location permission is required to create a hotspot"
        }
    }

    // Hotspot hosting worker
    LaunchedEffect(isHosting) {
        if (isHosting) {
            errorMessage = null
            // Check location permission
            val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasLocation) {
                isHosting = false
                permissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                return@LaunchedEffect
            }

            // Start programmatically created local hotspot
            hotspotManager.startHotspot(
                onStarted = { ssid, key ->
                    hotspotSsid = ssid
                    hotspotPassword = key
                    
                    scope.launch(Dispatchers.IO) {
                        try {
                            // Wait for network interfaces to configure
                            delay(2000)
                            
                            // 1. Resolve host IP on hotspot subnet
                            val ip = HotspotManager.getLocalIpAddress() ?: "192.168.43.1"
                            localIp = ip

                            // 2. Start fast Synapse TCP socket server
                            val port = transferManager.startSending(selectedUris, "Synapse Hotspot")
                            activePort = port

                            // 3. Start standard Web browser file server on port 8080
                            val httpServer = SimpleHttpFileServer(context, selectedUris, 8080)
                            httpServer.start()
                            httpFileServer = httpServer

                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                errorMessage = "Failed starting server: ${e.message}"
                                isHosting = false
                                hotspotManager.stopHotspot()
                            }
                        }
                    }
                },
                onFailed = { err ->
                    errorMessage = err
                    isHosting = false
                }
            )
        } else {
            // Clean up session
            hotspotManager.stopHotspot()
            transferManager.stopSending()
            httpFileServer?.stop()
            httpFileServer = null
            hotspotSsid = null
            hotspotPassword = null
            activePort = null
            localIp = null
            showWifiQrDialog = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            hotspotManager.stopHotspot()
            transferManager.stopSending()
            httpFileServer?.stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(20.dp)
    ) {
        // ── Top Title Bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (isHosting) {
                        isHosting = false
                    }
                    onBack()
                },
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = if (isDarkTheme) TextPrimaryDark else TextPrimary
                )
            }

            Text(
                text = "Hotspot Direct Share",
                style = SynapseTypography.displayMedium.copy(fontSize = 24.sp),
                color = if (isDarkTheme) TextPrimaryDark else TextPrimary,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        if (errorMessage != null) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Error, contentDescription = null, tint = Danger)
                    Text(
                        text = errorMessage!!,
                        style = SynapseTypography.bodyMedium,
                        color = Danger
                    )
                }
            }
        }

        // ── Screen Body Content ──
        Box(modifier = Modifier.weight(1f)) {
            if (!isHosting) {
                // state: Select files to host
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "Step 1: Select files to host offline",
                        style = SynapseTypography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Selected files list
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                    ) {
                        if (selectedFiles.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { filePicker.launch("*/*") },
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudUpload,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "No files selected",
                                    style = SynapseTypography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Tap to browse and select files to share",
                                    style = SynapseTypography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(selectedFiles) { file ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.InsertDriveFile,
                                            contentDescription = null,
                                            tint = Accent1,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 12.dp)
                                        ) {
                                            Text(
                                                text = file.name,
                                                style = SynapseTypography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = com.synapse.lantransfer.util.formatBytes(file.size),
                                                style = SynapseTypography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                val idx = selectedFiles.indexOf(file)
                                                if (idx != -1) {
                                                    selectedFiles = selectedFiles.filterIndexed { i, _ -> i != idx }
                                                    selectedUris = selectedUris.filterIndexed { i, _ -> i != idx }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Remove",
                                                tint = Danger,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { filePicker.launch("*/*") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            Text("Select Files", style = SynapseTypography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = { isHosting = true },
                            enabled = selectedFiles.isNotEmpty(),
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent1,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Wifi,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("Start Hosting", style = SynapseTypography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            } else {
                // state: Hosting is active
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (hotspotSsid == null || localIp == null) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = Accent1)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Configuring offline hotspot...",
                                style = SynapseTypography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // Display wifi hotspot credentials and IP downloads
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            item {
                                Text(
                                    text = "Step 2: Connect receiver to this Wi-Fi Network",
                                    style = SynapseTypography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Wifi details card
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                                        .clickable { showWifiQrDialog = true }
                                        .padding(20.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Wi-Fi SSID:", style = SynapseTypography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(hotspotSsid!!, style = SynapseTypography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Password:", style = SynapseTypography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(hotspotPassword!!, style = SynapseTypography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 4.dp),
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.QrCode,
                                                contentDescription = "QR Code",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Tap to show Wi-Fi QR Code",
                                                style = SynapseTypography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }

                                if (showWifiQrDialog) {
                                    val wifiQrContent = remember(hotspotSsid, hotspotPassword) {
                                        val ssid = hotspotSsid ?: ""
                                        val password = hotspotPassword ?: ""
                                        fun escape(s: String) = s.replace("\\", "\\\\")
                                                                 .replace(";", "\\;")
                                                                 .replace(",", "\\,")
                                                                 .replace(":", "\\:")
                                                                 .replace("\"", "\\\"")
                                        "WIFI:S:${escape(ssid)};T:WPA;P:${escape(password)};;"
                                    }
                                    val qrBitmap = remember(wifiQrContent) { QRCodeGenerator.generate(wifiQrContent) }

                                    AlertDialog(
                                        onDismissRequest = { showWifiQrDialog = false },
                                        title = {
                                            Text(
                                                text = "Wi-Fi Hotspot Connection",
                                                style = SynapseTypography.displayMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        text = {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = "Scan this QR code with your other device's camera or system Wi-Fi scanner to connect automatically.",
                                                    style = SynapseTypography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(bottom = 16.dp),
                                                    textAlign = TextAlign.Center
                                                )
                                                if (qrBitmap != null) {
                                                    androidx.compose.foundation.Image(
                                                        bitmap = qrBitmap.asImageBitmap(),
                                                        contentDescription = "Wi-Fi QR Code",
                                                        modifier = Modifier
                                                            .size(240.dp)
                                                            .clip(RoundedCornerShape(16.dp))
                                                            .background(Color.White)
                                                            .padding(12.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(16.dp))
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                                                        .padding(12.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        text = "SSID: ${hotspotSsid}",
                                                        style = SynapseTypography.labelMedium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "Password: ${hotspotPassword}",
                                                        style = SynapseTypography.labelMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            TextButton(onClick = { showWifiQrDialog = false }) {
                                                Text("Close", color = Accent1)
                                            }
                                        },
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                }
                            }

                            item {
                                Text(
                                    text = "Step 3: Receiver accesses shared files",
                                    style = SynapseTypography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                            }

                            // Dynamic QR code card
                            item {
                                val synapseUri = "synapse://$localIp:${activePort ?: 40889}"
                                val qrBitmap = remember(synapseUri) { QRCodeGenerator.generate(synapseUri) }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                                        .padding(20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "Scan from Synapse app receiver:",
                                            style = SynapseTypography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(bottom = 12.dp)
                                        )
                                        if (qrBitmap != null) {
                                            androidx.compose.foundation.Image(
                                                bitmap = qrBitmap.asImageBitmap(),
                                                contentDescription = "QR Code",
                                                modifier = Modifier
                                                    .size(200.dp)
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .background(Color.White)
                                                    .padding(10.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Browser download address card
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                                        .padding(20.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "Or visit in ANY browser (iPhone, PC, etc):",
                                            style = SynapseTypography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                        Text(
                                            text = "http://$localIp:8080",
                                            style = SynapseTypography.titleMedium.copy(fontSize = 18.sp),
                                            fontWeight = FontWeight.Bold,
                                            color = Accent1,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            item {
                                Button(
                                    onClick = { isHosting = false },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Danger,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(vertical = 14.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.WifiOff,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text("Stop Hosting", style = SynapseTypography.bodyMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
