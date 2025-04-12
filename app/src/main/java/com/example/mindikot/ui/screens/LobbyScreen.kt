package com.example.mindikot.ui.screens

// Required Android & Compose Imports
import android.Manifest
import android.content.Context // <-- Added Import
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel // <-- Added Import
import androidx.lifecycle.ViewModelProvider // <-- Added Import
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.mindikot.core.model.GameMode
import com.example.mindikot.ui.GameViewModel

// Add required permissions to AndroidManifest.xml:
// <uses-permission android:name="android.permission.INTERNET" />
// <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
// <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
// <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
// <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" /> (For Android 12+ NSD)

@Composable
fun LobbyScreen(
        navController: NavController,
        viewModel: GameViewModel =
                viewModel(factory = GameViewModelFactory(LocalContext.current.applicationContext))
) {
    var playerName by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // --- Permission Handling ---
    var hasNetworkPermission by remember {
        mutableStateOf(
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
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
                    Toast.makeText(context, "Network permission is required.", Toast.LENGTH_LONG)
                            .show()
                }
            }
    // Function to check and request permission
    val checkAndRequestPermission: () -> Boolean = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasNetworkPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            false // Permission not granted yet
        } else {
            true // Permission granted or not needed
        }
    }
    // --- End Permission Handling ---

    LaunchedEffect(playerName) { if (playerName.isBlank()) selectedRole = null }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopServerAndDiscovery()
            viewModel.disconnectFromServer()
            viewModel.stopNsdDiscovery()
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

        Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("Host", "Joiner").forEach { role ->
                Button(
                        onClick = {
                            if (playerName.isBlank()) {
                                Toast.makeText(context, "Please enter name", Toast.LENGTH_SHORT)
                                        .show()
                            } else {
                                if (checkAndRequestPermission()
                                ) { // Check permission before setting role
                                    selectedRole = role
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
                        // Disable button if permission is needed but not granted yet (on S+)
                        enabled =
                                hasNetworkPermission ||
                                        Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                ) { Text(role) }
            }
        }

        // Show permission rationale if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        !hasNetworkPermission &&
                        selectedRole != null
        ) {
            Text(
                    "Network discovery requires Location permission on this Android version.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
            )
            Button(
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
            ) { Text("Grant Permission") }
        }

        // --- Conditional UI ---
        when (selectedRole) {
            "Host" -> {
                // Only show config if permission granted (or not needed)
                if (hasNetworkPermission || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    HostSection(navController, viewModel, playerName)
                }
            }
            "Joiner" -> {
                if (hasNetworkPermission || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    JoinerSection(navController, viewModel, playerName)
                }
                // No need for the "permission required" text here as it's shown above
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
        if (hostIp != null) {
            Text(
                    "Hosting on: $hostIp (Others on WiFi should find you)",
                    style = MaterialTheme.typography.bodySmall
            )
        } else {
            // This might show briefly before IP is found
            Text("Advertising game on network...", style = MaterialTheme.typography.bodySmall)
        }
        GameConfigCard(
                navController = navController,
                viewModel = viewModel,
                hostPlayerName = hostPlayerName
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

    // Start/Stop discovery tied to this section being composed/disposed
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

        Button(
                onClick = {
                    viewModel.stopNsdDiscovery() // Stop previous discovery explicitly
                    viewModel.startNsdDiscovery() // Restart discovery
                    Toast.makeText(context, "Refreshing game list...", Toast.LENGTH_SHORT).show()
                }
        ) { Text("Refresh Game List") }

        Spacer(modifier = Modifier.height(8.dp))

        if (discoveredGames.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.size(30.dp))
            Text(
                    "Searching for games on your network...",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Text("Available Games:", style = MaterialTheme.typography.titleSmall)
            // Use LazyColumn for potentially many games
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(discoveredGames, key = { it.serviceName }) { serviceInfo ->
                    Button(
                            onClick = {
                                if (serviceInfo.host == null || serviceInfo.port <= 0) {
                                    Toast.makeText(
                                                    context,
                                                    "Game details not ready, please wait or refresh.",
                                                    Toast.LENGTH_SHORT
                                            )
                                            .show()
                                } else {
                                    Toast.makeText(
                                                    context,
                                                    "Connecting to ${serviceInfo.serviceName}...",
                                                    Toast.LENGTH_SHORT
                                            )
                                            .show()
                                    viewModel.connectToDiscoveredHost(serviceInfo, joinerPlayerName)
                                    navController.navigate("waiting_for_players")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                    ) {
                        @Suppress("DEPRECATION") // Suppress warning for serviceInfo.host
                        Text(
                            "${serviceInfo.serviceName} (${serviceInfo.host?.hostAddress ?: "resolving..."})"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GameConfigCard(navController: NavController, viewModel: GameViewModel, hostPlayerName: String) {
    fun GameMode.displayName(): String =
            when (this) {
                GameMode.CHOOSE_WHEN_EMPTY -> "Choose When Empty"
                GameMode.FIRST_CARD_HIDDEN -> "First Card Hidden"
            }

    var numberOfPlayers by remember { mutableStateOf(4) }
    var gameMode by remember { mutableStateOf(GameMode.CHOOSE_WHEN_EMPTY) }

    Card(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            shape = RoundedCornerShape(16.dp), // Slightly less rounded
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

// --- Helper Composables moved outside GameConfigCard ---
@Composable
private fun ConfigOptionRow(label: String, content: @Composable RowScope.() -> Unit) {
    Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp) // Add padding top to space from label
        ) { content() }
    }
}

@Composable
private fun ConfigButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Button(
            onClick = onClick,
            colors =
                    ButtonDefaults.buttonColors(
                            containerColor =
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor =
                                    if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSecondaryContainer
                    ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp) // Adjust padding
    ) { Text(text, textAlign = TextAlign.Center) }
}

// --- ViewModel Factory for Context Injection ---
class GameViewModelFactory(private val context: Context) :
        ViewModelProvider.Factory { // Removed erroneous Modifier param
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GameViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            // Use the injected context directly, assuming it's ApplicationContext
            return GameViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
