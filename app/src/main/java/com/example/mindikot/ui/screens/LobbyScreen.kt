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
import androidx.core.app.ActivityCompat // Needed for rationale check
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.mindikot.core.model.GameMode
// Corrected ViewModel and Factory imports
import com.example.mindikot.ui.viewmodel.GameViewModel
import com.example.mindikot.ui.viewmodel.factory.GameViewModelFactory
import com.example.mindikot.ui.viewmodel.utils.log // Optional import for logging
import android.net.nsd.NsdServiceInfo // Keep NSD import
import com.example.mindikot.ui.viewmodel.utils.logError // Import logError
import com.example.mindikot.ui.viewmodel.stopServerAndDiscovery // From GameViewModelHost.kt
import com.example.mindikot.ui.viewmodel.stopNsdDiscovery     // From GameViewModelNsd.kt
import com.example.mindikot.ui.viewmodel.disconnectFromServer // From GameViewModelClient.kt
import com.example.mindikot.ui.viewmodel.connectToDiscoveredHost // From GameViewModelClient.kt
import com.example.mindikot.ui.viewmodel.startServerAndDiscovery // From GameViewModelHost.kt
import com.example.mindikot.ui.viewmodel.startNsdDiscovery // From GameViewModelNsd.kt
import com.example.mindikot.ui.viewmodel.registerNsdService // From GameViewModelNsd.kt
import com.example.mindikot.ui.viewmodel.unregisterNsdService // From GameViewModelNsd.kt

@Composable
fun LobbyScreen(
    navController: NavController,
    // Use the factory to get the ViewModel instance
    viewModel: GameViewModel = viewModel(
        factory = GameViewModelFactory(LocalContext.current.applicationContext)
    )
) {
    var playerName by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf<String?>(null) } // "Host" or "Joiner"
    val context = LocalContext.current
    val activity = context as? Activity // Needed for permission rationale check

    // State for permission dialogs
    var showRationaleDialog by remember { mutableStateOf(false) }
    var showSettingsGuidance by remember { mutableStateOf(false) }

    // --- Permission Handling ---
    // Define required permission based on Android version for NSD
    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Android 12+ requires FINE_LOCATION for NSD discovery/advertising involving IP addresses
        Manifest.permission.ACCESS_FINE_LOCATION
    } else {
        // Older versions might implicitly allow NSD with just INTERNET,
        // but CHANGE_WIFI_MULTICAST_STATE is often needed too.
        // For simplicity, we can check INTERNET or just assume granted on older APIs if needed.
        // Let's stick with FINE_LOCATION check for S+ as it's the stricter requirement.
        // On older versions, we might assume it works or handle potential failures.
        // For this example, let's enforce the check mainly for S+ where it's mandatory.
        // If targeting older APIs heavily, add more specific checks.
        // If we don't need location on older APIs, maybe return null or a basic permission like INTERNET.
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
              // Basic network permissions likely suffice here, but FINE_LOCATION check below handles S+
              null // Let the hasPermission check handle this
         } else {
              null // Very old API, assume OK or handle error
         }
    }

    // Track current permission status
    var hasPermission by remember {
        mutableStateOf(
            requiredPermission == null || // Assume granted if no specific permission needed for this OS version
            ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Permission Request Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasPermission = isGranted
        if (!isGranted) {
            // Check if user permanently denied (don't show rationale again)
            if (activity != null && requiredPermission != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, requiredPermission)) {
                // User selected "Don't ask again" or permission is disabled by policy
                showSettingsGuidance = true // Guide user to settings
            } else {
                // Permission denied, but can ask again (or first denial)
                Toast.makeText(context, "Permission denied. Cannot host or join.", Toast.LENGTH_LONG).show()
            }
            selectedRole = null // Reset role choice if permission denied
        } else {
            // Permission granted! User can proceed with the selected role.
            Toast.makeText(context, "Permission granted!", Toast.LENGTH_SHORT).show()
             // Re-trigger the action associated with the selected role if needed
             // (Usually handled by the button's onClick re-evaluation)
        }
    }
    // --- End Permission Handling ---

    // Reset role if player name becomes blank
    LaunchedEffect(playerName) {
        if (playerName.isBlank()) {
             selectedRole = null
         }
    }

    // Cleanup network state when leaving the Lobby screen scope (important!)
    // This ensures that if the user navigates away or presses back,
    // the server/client connections and NSD are stopped correctly.
    DisposableEffect(Unit) {
        onDispose {
            println("[UI - LobbyScreen] Disposing LobbyScreen. Cleaning up network state.")
            // Ensure all potential network activities are stopped
            if (viewModel.isHost) {
                 viewModel.stopServerAndDiscovery()
            } else {
                 viewModel.stopNsdDiscovery()
                 viewModel.disconnectFromServer()
            }
        }
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            // Use verticalScroll for potentially long content (Joiner list)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Mindikot Lobby", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = playerName,
            onValueChange = { playerName = it.filter { c -> c.isLetterOrDigit() || c == ' ' }.take(16) }, // Limit name length/chars
            label = { Text("Enter your name (max 16)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Text("Select your role:", style = MaterialTheme.typography.titleMedium)

        // --- Role Selection Logic ---
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth() // Center the row content
        ) {
            listOf("Host", "Joiner").forEach { role ->
                Button(
                    onClick = {
                        if (playerName.isBlank()) {
                            Toast.makeText(context, "Please enter your name first.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        // Check current permission status *before* selecting role
                        val currentPermissionStatus = if (requiredPermission != null) {
                             ContextCompat.checkSelfPermission(context, requiredPermission)
                        } else {
                             PackageManager.PERMISSION_GRANTED // Assume granted if no specific permission needed
                        }
                        hasPermission = (currentPermissionStatus == PackageManager.PERMISSION_GRANTED)

                        if (hasPermission) { // Permission OK
                            // Only change role if different, or first selection
                            if (selectedRole != role) {
                                 selectedRole = role
                                 // Clean up previous network state when switching roles
                                 if (role == "Host") {
                                     viewModel.stopNsdDiscovery()
                                     viewModel.disconnectFromServer()
                                     // Initialize settings for host (will be done again in HostSection, but good practice)
                                     // viewModel.initializeGameSettings(playerName, GameMode.CHOOSE_WHEN_EMPTY, true, 4) // Default init?
                                 } else { // Switching to Joiner
                                     viewModel.stopServerAndDiscovery()
                                     // No need to init settings for joiner here
                                 }
                            }
                        } else if (requiredPermission != null) { // Permission NOT granted and is required
                            // Need to request or show rationale
                            if (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, requiredPermission)) {
                                showRationaleDialog = true // Show explanation dialog
                            } else {
                                // Request permission directly (first time or rationale not needed)
                                permissionLauncher.launch(requiredPermission)
                            }
                        } else {
                             // Should not happen if requiredPermission is null and hasPermission is true
                            viewModel.logError("LobbyScreen: Inconsistent permission state.")
                        }
                    },
                    enabled = playerName.isNotBlank(), // Enable only if name entered
                    colors = ButtonDefaults.buttonColors(
                        // Highlight selected role, otherwise use default secondary
                        containerColor = if (selectedRole == role) MaterialTheme.colorScheme.primary
                                         else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) { Text(if(role == "Joiner") "Randi" else "Host") } // Using "Randi" for Joiner button
            }
        }

        // Rationale Dialog (Explain why permission is needed)
        if (showRationaleDialog && requiredPermission != null) {
            AlertDialog(
                onDismissRequest = { showRationaleDialog = false },
                title = { Text("Permission Needed") },
                text = { Text("Finding and hosting games on the local network requires the Location permission on this version of Android to access network information.") },
                confirmButton = {
                    Button(onClick = {
                        permissionLauncher.launch(requiredPermission) // Request after showing rationale
                        showRationaleDialog = false
                    }) { Text("Grant Permission") }
                },
                dismissButton = { Button(onClick = { showRationaleDialog = false }) { Text("Cancel") } }
            )
        }
        // Settings Guidance Dialog (If permission permanently denied)
        if (showSettingsGuidance) {
            AlertDialog(
                onDismissRequest = { showSettingsGuidance = false },
                title = { Text("Permission Required") },
                text = { Text("Network permission was denied. Please grant Location permission manually in App Settings to host or join games.") },
                confirmButton = {
                    Button(onClick = {
                        // Intent to open app-specific settings screen
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", context.packageName, null)
                        intent.data = uri
                        context.startActivity(intent)
                        showSettingsGuidance = false
                    }) { Text("Open Settings") }
                },
                dismissButton = { Button(onClick = { showSettingsGuidance = false }) { Text("Cancel") } }            )
        }

        // --- Conditional UI based on Selected Role and Permissions ---
        // Only show Host/Joiner section if role selected AND permission granted
        if (selectedRole != null && hasPermission) {
            when (selectedRole) {
                "Host" -> HostSection(navController, viewModel, playerName)
                "Joiner" -> JoinerSection(navController, viewModel, playerName)
            }
        } else if (selectedRole != null && !hasPermission) {
            // Show message if role selected but permission denied/pending
            Text(
                "Please grant the required Location permission to continue as $selectedRole.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

    } // End Main Column
}


// --- HostSection Composable ---
@Composable
fun HostSection(navController: NavController, viewModel: GameViewModel, hostPlayerName: String) {
    // This section appears when "Host" is selected and permissions are granted.
    // It allows configuring the game.

    // Game Config state (remembered within this section's scope)
    var numberOfPlayers by remember { mutableStateOf(4) }
    var gameMode by remember { mutableStateOf(GameMode.CHOOSE_WHEN_EMPTY) } // Default game mode

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Host Game Configuration", style = MaterialTheme.typography.titleLarge)
            Divider()

            // Player Count Selection
            ConfigOptionRow(label = "Number of Players:") {
                listOf(2,4, 6).forEach { count ->
                    ConfigButton(
                        text = "$count Players",
                        isSelected = numberOfPlayers == count,
                        onClick = { numberOfPlayers = count }
                    )
                }
            }

            // Game Mode Selection
            ConfigOptionRow(label = "Game Mode:") {
                GameMode.values().forEach { mode ->
                    ConfigButton(
                        text = mode.displayName(), // Use helper for display name
                        isSelected = gameMode == mode,
                        onClick = { gameMode = mode }
                    )
                }
            }

            Divider()

            // Button to Create Game
            Button(
                onClick = {
                    // 1. Initialize ViewModel state with chosen settings
                    viewModel.initializeGameSettings(
                        playerName = hostPlayerName,
                        mode = gameMode,
                        host = true, // Explicitly set as host
                        playersNeeded = numberOfPlayers
                    )
                    // 2. Start the server and NSD advertising (ViewModel handles this)
                    viewModel.startServerAndDiscovery() // Use host extension method
                    // 3. Navigate to the host waiting screen
                    navController.navigate("game_host")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) { Text("Create Game & Wait for Players") }
        }
    }
}

// --- JoinerSection Composable ---
@Composable
fun JoinerSection(
    navController: NavController,
    viewModel: GameViewModel,
    joinerPlayerName: String
) {
    // This section appears when "Joiner" is selected and permissions are granted.
    // It handles discovering and joining games.
    val discoveredGames by viewModel.discoveredHosts.collectAsState()
    val context = LocalContext.current
    var isConnecting by remember { mutableStateOf(false) } // Track connection attempt

    // Start NSD discovery when this section becomes visible
    // Stop discovery when it leaves composition
    DisposableEffect(Unit) {
         println("[UI - JoinerSection] Starting NSD discovery.")
        viewModel.startNsdDiscovery() // Use NSD extension method
        onDispose {
             println("[UI - JoinerSection] Stopping NSD discovery.")
            viewModel.stopNsdDiscovery() // Use NSD extension method
             isConnecting = false // Reset connecting state on dispose
        }
    }

    // Navigate to waiting screen on successful connection attempt initiation
    // Note: Actual connection success is handled by ViewModel state changes
    LaunchedEffect(viewModel.isConnectedToServer) {
        if (viewModel.isConnectedToServer) {
             println("[UI - JoinerSection] isConnectedToServer = true, navigating to waiting_for_players")
             if (navController.currentDestination?.route != "waiting_for_players") {
                  navController.navigate("waiting_for_players")
             }
             isConnecting = false // Reset connecting flag
        } else {
             // If connection failed after attempting, reset flag
             if (isConnecting) { // Only reset if we were the ones trying
                  isConnecting = false
             }
        }
    }
    // Handle errors shown by ViewModel (e.g., connection failed)
    LaunchedEffect(viewModel.showError) {
         viewModel.showError.collect {
              // If an error occurs during connection attempt, reset the flag
              isConnecting = false
         }
    }


    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Join a Game", style = MaterialTheme.typography.titleMedium)

        Button(
            onClick = {
                viewModel.startNsdDiscovery() // Restart discovery (stops previous first)
                Toast.makeText(context, "Refreshing game list...", Toast.LENGTH_SHORT).show()
            },
            enabled = !isConnecting // Disable refresh while connecting
        ) { Text("Refresh Game List") }

        Spacer(modifier = Modifier.height(8.dp))

        // Game List Area
        Box(modifier = Modifier.heightIn(min = 100.dp, max = 250.dp)) {
            if (discoveredGames.isEmpty() && !isConnecting) { // Show loading only if not empty and not connecting
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
            } else if (isConnecting) {
                 Column(
                    modifier = Modifier.fillMaxSize(), // Fill the box
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                 ) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp))
                    Text(
                        "Connecting...",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                        textAlign = TextAlign.Center
                    )
                 }
            } else { // Show discovered games
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item { // Header item
                        Text(
                            "Available Games:",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(discoveredGames, key = { it.serviceName }) { serviceInfo ->
                        // Use Button for each game, disable if connection in progress
                        Button(
                            onClick = {
                                @Suppress("DEPRECATION") // Suppress for serviceInfo.host if needed
                                if (serviceInfo.host == null || serviceInfo.port <= 0) {
                                    Toast.makeText(context, "Game details not ready yet. Refreshing...", Toast.LENGTH_SHORT).show()
                                     viewModel.startNsdDiscovery() // Trigger refresh automatically
                                } else {
                                    isConnecting = true // Set connecting flag
                                    Toast.makeText(context, "Connecting to \${serviceInfo.serviceName}...", Toast.LENGTH_SHORT).show()
                                    // Call VM connect method using client extension
                                    viewModel.connectToDiscoveredHost(serviceInfo, joinerPlayerName)
                                    // Navigation handled by LaunchedEffect on isConnectedToServer
                                }
                            },
                            enabled = !isConnecting, // Disable button while connecting
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            @Suppress("DEPRECATION") // Suppress for serviceInfo.host if needed
                            val hostIpDisplay = serviceInfo.host?.hostAddress ?: "resolving..."
                            Text("\${serviceInfo.serviceName} (\$hostIpDisplay)")
                        }
                    }
                }
            }
        } // End Box for game list / loading indicator
    } // End Column for Joiner section
}


// --- Helper Composables ---

// Helper for configuration rows (Label + Buttons)
@Composable
private fun ConfigOptionRow(label: String, content: @Composable RowScope.() -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth() // Allow row to take width
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), // Center buttons
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth() // Ensure row takes width for centering
        ) { content() } // Place the provided buttons here
    }
}

// Helper for configuration buttons (Selected/Unselected state)
@Composable
private fun ConfigButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
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

// Helper to get display name for GameMode enum
private fun GameMode.displayName(): String = when (this) {
    GameMode.CHOOSE_WHEN_EMPTY -> "Choose When Empty"
    GameMode.FIRST_CARD_HIDDEN -> "First Card Hidden"
}