package com.example.mindikot.ui.screens

// Required Android & Compose Imports
import android.Manifest
import android.app.Activity // Needed for shouldShowRequestPermissionRationale
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat // Needed
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.mindikot.core.model.GameMode
import com.example.mindikot.ui.GameViewModel
import com.example.mindikot.ui.GameViewModelFactory
import android.net.nsd.NsdServiceInfo

@Composable
fun LobbyScreen(
    navController: NavController,
    viewModel: GameViewModel = viewModel(
        factory = GameViewModelFactory(LocalContext.current.applicationContext)
    )
) {
    var playerName by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val activity = context as? Activity

    var showRationaleDialog by remember { mutableStateOf(false) }
    var showSettingsGuidance by remember { mutableStateOf(false) }

    // --- Permission Handling ---
    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.ACCESS_FINE_LOCATION
    } else {
        Manifest.permission.INTERNET // Or just check INTERNET
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasPermission = isGranted
        if (!isGranted) {
            if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, requiredPermission)) {
                showSettingsGuidance = true
            } else {
                Toast.makeText(context, "Permission denied.", Toast.LENGTH_LONG).show()
            }
            selectedRole = null
        } else {
            Toast.makeText(context, "Permission granted!", Toast.LENGTH_SHORT).show()
        }
    }
    // --- End Permission Handling ---

    LaunchedEffect(playerName) { if (playerName.isBlank()) selectedRole = null }

    // REMOVE OR COMMENT OUT the DisposableEffect that stops the server here
    // DisposableEffect(Unit) {
    //     onDispose {
    //         Log.d("LobbyScreen", "DisposableEffect onDispose triggered") // Add log
    //         viewModel.stopServerAndDiscovery() // DO NOT STOP HERE
    //         viewModel.disconnectFromServer()
    //         viewModel.stopNsdDiscovery()
    //     }
    // }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Mindikot Lobby", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(value = playerName,
            onValueChange = { playerName = it.trim() },
            label = { Text("Enter your name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text("Select your role:", style = MaterialTheme.typography.titleMedium)

        // --- Role Selection Logic ---
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("Host", "Joiner").forEach { role ->
                Button(
                    onClick = {
                        if (playerName.isBlank()) {
                            Toast.makeText(context, "Please enter name", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        // Check current permission status first
                        val currentPermissionStatus = ContextCompat.checkSelfPermission(context, requiredPermission)
                        hasPermission = (currentPermissionStatus == PackageManager.PERMISSION_GRANTED)

                        when {
                            hasPermission -> { // Permission OK
                                selectedRole = role
                                // Clear other network state ONLY IF switching roles
                                if (role == "Host") {
                                    viewModel.stopNsdDiscovery() // Stop searching if switching to host
                                    viewModel.disconnectFromServer() // Disconnect if switching to host
                                } else {
                                    viewModel.stopServerAndDiscovery() // Stop hosting if switching to joiner
                                }
                            }
                            // Need to show rationale?
                            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, requiredPermission) -> {
                                showRationaleDialog = true // Show explanation
                            }
                            // Need to request permission
                            else -> {
                                permissionLauncher.launch(requiredPermission) // Launch request
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedRole == role) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                ) { Text(if(role == "Joiner") "Randi" else "Host") } // Using "Randi" for Joiner button
            }
        }

        // Rationale Dialog
        if (showRationaleDialog) {
            AlertDialog(
                onDismissRequest = { showRationaleDialog = false },
                title = { Text("Permission Needed") },
                text = { Text("Finding games on the local network requires the Location permission on this version of Android.") },
                confirmButton = {
                    Button(onClick = {
                        permissionLauncher.launch(requiredPermission)
                        showRationaleDialog = false
                    }) { Text("Grant Permission") }
                },
                dismissButton = { Button(onClick = { showRationaleDialog = false }) { Text("Cancel") } }
            )
        }
        // Settings Guidance Dialog
        if (showSettingsGuidance) {
            AlertDialog(
                onDismissRequest = { showSettingsGuidance = false },
                title = { Text("Permission Required") },
                text = { Text("Network permission was denied. Please grant Location permission in App Settings to host or join games.") },
                confirmButton = {
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", context.packageName, null)
                        intent.data = uri
                        context.startActivity(intent)
                        showSettingsGuidance = false
                    }) { Text("Open Settings") }
                },
                dismissButton = { Button(onClick = { showSettingsGuidance = false }) { Text("Cancel") } }
            )
        }

        // --- Conditional UI ---
        val canProceed = hasPermission // Simplified check
        if (canProceed) {
            when (selectedRole) {
                "Host" -> HostSection(navController, viewModel, playerName)
                "Joiner" -> JoinerSection(navController, viewModel, playerName)
            }
        } else if (selectedRole != null) {
            // Show message if role selected but permission denied/pending
            Text(
                "Please grant Location permission to continue as $selectedRole.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }

    } // End Main Column
}


// --- HostSection Composable --- (No changes needed here)
@Composable
fun HostSection(navController: NavController, viewModel: GameViewModel, hostPlayerName: String) {
    // ... (Implementation remains the same)
    val hostIp by viewModel.hostIpAddress.collectAsState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth() // Allow column to take width
    ) {
        if (hostIp != null) {
            Text(
                "Hosting on: $hostIp",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Text(
                "(Others on the same WiFi should find your game)",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = Color.Gray
            )
        } else {
            Text("Advertising game on network...", style = MaterialTheme.typography.bodySmall)
        }
        GameConfigCard(
            navController = navController,
            viewModel = viewModel,
            hostPlayerName = hostPlayerName
        )
    }
}

// --- JoinerSection Composable --- (No changes needed here)
@Composable
fun JoinerSection(
    navController: NavController,
    viewModel: GameViewModel,
    joinerPlayerName: String
) {
    // ... (Implementation remains the same)
    val discoveredGames by viewModel.discoveredHosts.collectAsState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        viewModel.startNsdDiscovery()
        onDispose { viewModel.stopNsdDiscovery() }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Join a Game", style = MaterialTheme.typography.titleMedium)

        Button(onClick = {
            viewModel.stopNsdDiscovery() // Stop previous first
            viewModel.startNsdDiscovery() // Restart discovery
            Toast.makeText(context, "Refreshing game list...", Toast.LENGTH_SHORT).show()
        }) { Text("Refresh Game List") }

        Spacer(modifier = Modifier.height(8.dp))

        // Game List Area
        Box(modifier = Modifier.heightIn(min = 100.dp, max = 250.dp)) {
            if (discoveredGames.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(), // Fill the box
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp))
                    Text(
                        "Searching for games on network...",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item { // Header item
                        Text(
                            "Available Games:",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(discoveredGames, key = { it.serviceName }) { serviceInfo ->
                        Button(
                            onClick = {
                                @Suppress("DEPRECATION") // Suppress for serviceInfo.host
                                if (serviceInfo.host == null || serviceInfo.port <= 0) {
                                    Toast.makeText(context,"Game details not ready yet.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Connecting...", Toast.LENGTH_SHORT).show()
                                    viewModel.connectToDiscoveredHost(serviceInfo, joinerPlayerName)
                                    navController.navigate("waiting_for_players")
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp) // Add padding
                        ) {
                            @Suppress("DEPRECATION") // Suppress for serviceInfo.host
                            Text(
                                "${serviceInfo.serviceName} (${serviceInfo.host?.hostAddress ?: "IP resolving..."})"
                            )
                        }
                    }
                }
            }
        } // End Box
    } // End Column
}


// --- GameConfigCard Composable --- (No changes needed here)
@Composable
fun GameConfigCard(navController: NavController, viewModel: GameViewModel, hostPlayerName: String) {
    // ... (Implementation remains the same)
    fun GameMode.displayName(): String = when (this) {
        GameMode.CHOOSE_WHEN_EMPTY -> "Choose When Empty"
        GameMode.FIRST_CARD_HIDDEN -> "First Card Hidden"
    }

    var numberOfPlayers by remember { mutableStateOf(4) }
    var gameMode by remember { mutableStateOf(GameMode.CHOOSE_WHEN_EMPTY) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Host Game Configuration", style = MaterialTheme.typography.titleLarge)
            Divider()

            ConfigOptionRow(label = "Number of Players:") {
                listOf(4, 6).forEach { count ->
                    ConfigButton(
                        text = "$count Players",
                        isSelected = numberOfPlayers == count,
                        onClick = { numberOfPlayers = count }
                    )
                }
            }

            ConfigOptionRow(label = "Game Mode:") {
                GameMode.values().forEach { mode ->
                    ConfigButton(
                        text = mode.displayName(),
                        isSelected = gameMode == mode,
                        onClick = { gameMode = mode }
                    )
                }
            }

            Divider()

            Button(
                onClick = {
                    viewModel.initializeGameSettings(
                        playerName = hostPlayerName,
                        mode = gameMode,
                        host = true,
                        playersNeeded = numberOfPlayers
                    )
                    viewModel.startServerAndDiscovery() // Start server after setting state
                    navController.navigate("game_host")
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Create Game & Wait") }
        }
    }
}

// --- Helper Composables --- (Keep ConfigOptionRow, ConfigButton as before)
@Composable
private fun ConfigOptionRow(label: String, content: @Composable RowScope.() -> Unit) {
    // ... (Implementation from previous step)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth() // Allow row to take width
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), // Center buttons
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth() // Ensure row takes width
        ) { content() }
    }
}

@Composable
private fun ConfigButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    // ... (Implementation from previous step)
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp) // Adjust padding
    ) {
        Text(text, textAlign = TextAlign.Center)
    }
}


// --- ViewModel Factory --- (Keep as before)
class GameViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    // ... (Implementation from previous step)
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GameViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GameViewModel(context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}