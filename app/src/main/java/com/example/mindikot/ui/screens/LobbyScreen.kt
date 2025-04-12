package com.example.mindikot.ui.screens

import android.Manifest // Required for NSD permissions
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.mindikot.core.model.GameMode
import com.example.mindikot.ui.GameViewModel

// Add required permissions to AndroidManifest.xml:
// <uses-permission android:name="android.permission.INTERNET" />
// <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
// <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

@Composable
fun LobbyScreen(
        navController: NavController,
        // Use Hilt or manual injection for ViewModel with Context if needed elsewhere
        // For simple cases, viewModel() works but lacks context injection here.
        // Consider passing context or using AndroidViewModel.
        viewModel: GameViewModel =
                viewModel(
                        factory =
                                GameViewModelFactory(
                                        LocalContext.current.applicationContext
                                ) // Need a Factory for context
                )
) {
    var playerName by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // --- Permission Handling for NSD (Android 12+) ---
    var hasNetworkPermission by remember {
        mutableStateOf(
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true // Assume granted below S
                else
                        ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    isGranted: Boolean ->
                hasNetworkPermission = isGranted
                if (!isGranted) {
                    Toast.makeText(
                                    context,
                                    "Network permission is required for finding games.",
                                    Toast.LENGTH_LONG
                            )
                            .show()
                }
            }
    LaunchedEffect(Unit) {
        if (!hasNetworkPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            // Note: ACCESS_COARSE_LOCATION might also work for NSD depending on exact usage.
            // Fine location is often needed for WiFi scanning involved in discovery.
        }
    }
    // --- End Permission Handling ---

    // Reset role if player name is cleared
    LaunchedEffect(playerName) { if (playerName.isBlank()) selectedRole = null }
    // Stop network activities when leaving lobby
    DisposableEffect(Unit) {
        onDispose {
            // Called when the Composable leaves the composition
            viewModel.stopServerAndDiscovery() // Stop hosting/discovery
            viewModel.disconnectFromServer() // Disconnect if client
            viewModel.stopNsdDiscovery() // Ensure discovery stops
        }
    }

    Column(
            modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Mindikot Lobby", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
                value = playerName,
                onValueChange = { playerName = it.trim() },
                label = { Text("Enter your name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
        )

        Text("Select your role:", style = MaterialTheme.typography.titleMedium)

        // Role Selection Buttons
        Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("Host", "Joiner").forEach { role ->
                Button(
                        onClick = {
                            if (playerName.isBlank()) {
                                Toast.makeText(
                                                context,
                                                "Please enter your name first",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            } else {
                                // Request permission if needed before proceeding
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                                !hasNetworkPermission
                                ) {
                                    permissionLauncher.launch(
                                            Manifest.permission.ACCESS_FINE_LOCATION
                                    )
                                } else {
                                    selectedRole = role
                                    // Stop other network activity when switching roles
                                    if (role == "Host") {
                                        viewModel.stopNsdDiscovery()
                                        viewModel.disconnectFromServer()
                                    } else {
                                        viewModel.stopServerAndDiscovery()
                                    }
                                }
                            }
                        },
                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor =
                                                if (selectedRole == role)
                                                        MaterialTheme.colorScheme.primary
                                                else Color.Gray
                                ),
                        enabled =
                                hasNetworkPermission ||
                                        Build.VERSION.SDK_INT <
                                                Build.VERSION_CODES
                                                        .S // Enable if permission granted or not
                        // needed
                        ) { Text(role) }
            }
        }

        // --- Conditional UI ---
        when (selectedRole) {
            "Host" -> {
                HostSection(navController, viewModel, playerName)
            }
            "Joiner" -> {
                if (hasNetworkPermission || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    JoinerSection(navController, viewModel, playerName)
                } else {
                    Text("Network permission required to find games.")
                }
            }
        }
    }
}

@Composable
fun HostSection(navController: NavController, viewModel: GameViewModel, hostPlayerName: String) {
    val hostIp by viewModel.hostIpAddress.collectAsState()

    Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Optionally display Host IP for manual connection fallback or info
        if (hostIp != null) {
            Text(
                    "Hosting on: $hostIp (Others on WiFi should find you)",
                    style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text("Configuring host settings...", style = MaterialTheme.typography.bodySmall)
        }
        // Show Host Configuration Card
        GameConfigCard(
                navController = navController,
                viewModel = viewModel,
                hostPlayerName = hostPlayerName // Pass name for initialization
        )
    }
}

@Composable
fun JoinerSection(
        navController: NavController,
        viewModel: GameViewModel,
        joinerPlayerName: String
) {
    val discoveredGames by viewModel.discoveredHosts.collectAsState()
    val context = LocalContext.current

    // Start discovery when entering Joiner section, stop when leaving
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

        // Optional: Button to manually refresh discovery
        Button(onClick = { viewModel.startNsdDiscovery() }) { Text("Refresh Game List") }

        if (discoveredGames.isEmpty()) {
            CircularProgressIndicator()
            Text(
                    "Searching for games on your network...",
                    style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text("Available Games:", style = MaterialTheme.typography.titleSmall)
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) { // Limit height
                items(discoveredGames, key = { it.serviceName }) { serviceInfo ->
                    Button(
                            onClick = {
                                Toast.makeText(
                                                context,
                                                "Connecting to ${serviceInfo.serviceName}...",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                                viewModel.connectToDiscoveredHost(serviceInfo, joinerPlayerName)
                                navController.navigate(
                                        "waiting_for_players"
                                ) // Navigate optimistically
                            },
                            modifier = Modifier.fillMaxWidth()
                    ) {
                        // Display service name (consider parsing host name from attributes if set)
                        Text(
                                "${serviceInfo.serviceName} (${serviceInfo.host?.hostAddress ?: "resolving..."})"
                        )
                    }
                }
            }
        }
    }
}

// GameConfigCard - Modified to call startServerAndDiscovery
@Composable
fun GameConfigCard(navController: NavController, viewModel: GameViewModel, hostPlayerName: String) {
    fun GameMode.displayName(): String =
            when (this) {
                GameMode.CHOOSE_WHEN_EMPTY -> "Choose When Empty"
                GameMode.FIRST_CARD_HIDDEN -> "First Card Hidden"
            }

    var numberOfPlayers by remember { mutableStateOf(4) }
    var gameMode by remember { mutableStateOf(GameMode.CHOOSE_WHEN_EMPTY) }

    Card(/* ... Card styling ... */ ) {
        Column(/* ... Column styling ... */ ) {
            Text("Host Game Configuration", style = MaterialTheme.typography.titleLarge)
            Divider()
            ConfigOptionRow("Number of Players:") { /* ... Player count buttons ... */}
            ConfigOptionRow("Game Mode:") { /* ... Game mode buttons ... */}
            Divider()

            Button(
                    onClick = {
                        // 1. Initialize the Game State with settings and host player
                        viewModel.initializeGameSettings(
                                playerName = hostPlayerName,
                                mode = gameMode,
                                host = true,
                                playersNeeded = numberOfPlayers
                        )
                        // 2. Start Server and NSD registration AFTER initializing state
                        viewModel.startServerAndDiscovery() // Let OS pick port
                        // 3. Navigate to the host waiting screen
                        navController.navigate("game_host")
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Create Game & Wait") }
        }
    }
}

// ConfigOptionRow and ConfigButton helpers remain the same

// --- ViewModel Factory for Context Injection ---
class GameViewModelFactory(private val context: Context) :
        androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GameViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GameViewModel(context.applicationContext) as T // Pass application context
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
