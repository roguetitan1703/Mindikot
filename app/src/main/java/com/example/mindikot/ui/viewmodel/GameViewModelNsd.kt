package com.example.mindikot.ui.viewmodel

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.lifecycle.viewModelScope // Needed for viewModelScope.launch
import com.example.mindikot.ui.viewmodel.utils.log
import com.example.mindikot.ui.viewmodel.utils.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ========================================================================// NSD FUNCTIONS (Implemented as Extension Functions on GameViewModel)
// ========================================================================
private const val SERVICE_TYPE = "_mindikot._tcp" // Define service type constant

/**
 * HOST: Registers the game service using NSD.
 * Returns true if registration request was successfully initiated, false otherwise.
 */
fun GameViewModel.registerNsdService(portToRegister: Int): Boolean {
    if (!isHost) return false // Only host registers

    // Unregister previous listener if exists
    if (registrationListener != null) {
        log("NSD registration already in progress or completed. Unregistering previous listener first.")
        unregisterNsdService() // Call the cleanup function
    }

    // Use applicationContext from the receiver ViewModel
    nsdManager = applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager?
    if (nsdManager == null) {
        logError("NSD Manager not available on this device.")
        viewModelScope.launch { _showError.emit("Network Service Discovery is not available.") }
        return false
    }

    // Create the listener *inside* this function scope
    val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
            // Store the successfully registered name
            // Use internal setter or make property internal/public
             setNsdServiceNameRegisteredInternal(nsdServiceInfo.serviceName)

            log("NSD Service registered: ${nsdServiceInfo.serviceName} on port ${nsdServiceInfo.port}")
             // Update host IP state just in case it wasn't ready before
             // This should likely be handled where the server starts, not here
             // viewModelScope.launch(Dispatchers.Main) {
             //     _hostIpAddress.value = getLocalIpAddress()?.hostAddress
             // }
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            logError("NSD registration failed for ${serviceInfo.serviceName}: Error $errorCode")
            setNsdServiceNameRegisteredInternal(null) // Clear name on failure
             // Notify UI about the failure
             viewModelScope.launch { _showError.emit("Failed to advertise game (NSD Error $errorCode)") }
            // Consider stopping the server if NSD fails critically? Or allow manual IP connect?
            // stopServerAndDiscovery()
        }

        override fun onServiceUnregistered(arg0: NsdServiceInfo) {
            log("NSD Service unregistered: ${arg0.serviceName}")
            // Clear the stored name if it matches the one being unregistered
            if (arg0.serviceName == nsdServiceNameRegistered) {
                setNsdServiceNameRegisteredInternal(null)
            }
             // Clear host IP display when unregistered? Maybe not, server might still be running.
             // viewModelScope.launch(Dispatchers.Main) { _hostIpAddress.value = null }
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // This means the service might still be lingering on the network
            logError("NSD unregistration failed for ${serviceInfo.serviceName}: Error $errorCode")
            // Consider retrying unregistration later?
        }
    }
    registrationListener = listener // Store the listener instance

    // Create unique service name
    val baseName = "Mindikot"
    // Attempt to use host player name if available and not default
    val hostNamePart = _state.value.players.find { it.id == 0 }?.name?.let {
         if (it != "Waiting..." && it.isNotBlank()) "_(${it.take(8)})" else ""
     } ?: ""
    val uniqueName = "${baseName}${hostNamePart}_${(1000..9999).random()}" // Simple uniqueness factor

    val serviceInfo = NsdServiceInfo().apply {
        serviceName = uniqueName
        serviceType = SERVICE_TYPE
        port = portToRegister
        // Add attributes if needed (e.g., player count, game mode)
        // setAttribute("players", requiredPlayerCount.toString())
        // setAttribute("mode", _state.value.gameMode.name)
    }

    log("Attempting to register NSD service: $uniqueName on port $portToRegister")
    try {
        // Use the stored listener
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener!!)
        return true // Registration initiated
    } catch (e: Exception) {
        logError("Exception during NSD service registration", e)
        registrationListener = null // Clear listener on exception
         viewModelScope.launch { _showError.emit("Error starting game advertisement.") }
        return false // Registration failed to initiate
    }
}

/** HOST: Unregisters the NSD service if it's currently registered. */
fun GameViewModel.unregisterNsdService() {
    if (nsdManager != null && registrationListener != null) {
        val listenerToUnregister = registrationListener // Capture current listener
        registrationListener = null // Nullify immediately to prevent race conditions if called again quickly

        log("Unregistering NSD service: $nsdServiceNameRegistered")
        try {
            nsdManager?.unregisterService(listenerToUnregister)
            // Name cleared in the listener's onServiceUnregistered callback
        } catch (e: IllegalArgumentException) {
             log("Error unregistering NSD service (already unregistered?): ${e.message}")
        } catch (e: Exception) {
            logError("Exception during NSD service unregistration", e)
            // If unregistration fails, the listener might still be technically active
            // but we've nulled our reference. Might lead to leaks if NSD manager holds reference.
        }
    } else {
         log("NSD Service not registered or listener is null, skipping unregistration.")
    }
}


/** CLIENT: Starts NSD discovery */
fun GameViewModel.startNsdDiscovery() {
    if (isHost) return // Host doesn't discover
    if (discoveryListener != null) {
         log("Client: NSD Discovery already active. Restarting.")
         stopNsdDiscovery() // Stop existing discovery first
         // Add a small delay before restarting to allow resources to release
         viewModelScope.launch {
             delay(200)
             internalStartNsdDiscovery() // Call internal function after delay
         }
         return
    }
    internalStartNsdDiscovery() // Start immediately if not already active
}

// Internal function to perform the actual start logic
private fun GameViewModel.internalStartNsdDiscovery() {
     if (isHost || discoveryListener != null) return // Re-check conditions

    // Permission check should happen in the UI before calling this

    // Use applicationContext from receiver
    nsdManager = applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager?
    if (nsdManager == null) {
        logError("Client: NSD Manager not available.")
         viewModelScope.launch { _showError.emit("Network Service Discovery is not available.") }
        return
    }

    // Clear previous results and resolving tracker
     viewModelScope.launch(Dispatchers.Main) {
         _discoveredHosts.value = emptyList()
     }
    resolvingServices.clear()

     // Create the listener
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) { log("NSD discovery started for type: $regType") }

        override fun onServiceFound(service: NsdServiceInfo) {
             log("NSD service found raw: Name=${service.serviceName}, Type=${service.serviceType}, Host=${service.host}, Port=${service.port}") // Verbose
            // Filter for correct type, avoid self-discovery (if host name known), and check if already resolving
            if (
//                service.serviceName != nsdServiceNameRegistered && // Avoid self if name is known (mainly for testing)
                !resolvingServices.containsKey(service.serviceName) && // Check if already resolving
                 !_discoveredHosts.value.any { it.serviceName == service.serviceName } // Check if already discovered and resolved
            ) {
                log("Attempting to resolve service: ${service.serviceName}")
                resolvingServices[service.serviceName] = true // Mark as resolving
                resolveNsdService(service) // Trigger resolution
            } else {
                 $("Ignoring found service: Type mismatch (${service.serviceType}), self-discovery, already resolving, or already resolved.")
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            log("NSD service lost: ${service.serviceName}")
            // Update UI on Main thread
            viewModelScope.launch(Dispatchers.Main) {
                _discoveredHosts.update { list ->
                    list.filterNot { it.serviceName == service.serviceName }
                }
            }
            resolvingServices.remove(service.serviceName) // Remove from resolving tracker
        }

        override fun onDiscoveryStopped(serviceType: String) { log("NSD discovery stopped for type: $serviceType") }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            logError("NSD discovery start failed: Error code $errorCode")
            viewModelScope.launch { _showError.emit("Failed to search for games (NSD Error $errorCode)") }
            discoveryListener = null // Clear listener if start fails
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            logError("NSD discovery stop failed: Error code $errorCode")
            // Listener might still be attached? Try nullifying anyway.
             discoveryListener = null
        }
    }
    discoveryListener = listener // Store the listener

    log("Client: Starting NSD discovery for type: $SERVICE_TYPE")
    try {
        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener!!)
    } catch (e: Exception) {
        logError("Exception starting NSD discovery", e)
        discoveryListener = null // Clear listener on exception
         viewModelScope.launch { _showError.emit("Error starting network discovery.") }
    }
}


/** CLIENT: Resolves a discovered service to get host and port */
private fun GameViewModel.resolveNsdService(serviceInfo: NsdServiceInfo) {
    if (nsdManager == null) {
        logError("Cannot resolve NSD service, NsdManager is null.")
        resolvingServices.remove(serviceInfo.serviceName)
        return
    }

    val serviceName = serviceInfo.serviceName // Capture name for logging in callbacks
    log("Resolving NSD service details for: $serviceName")

    // Use a try-catch block for the listener creation itself if needed, though less common
    val listener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(failedServiceInfo: NsdServiceInfo, errorCode: Int) {
            logError("NSD resolve failed for ${failedServiceInfo.serviceName}: Error code $errorCode")
            resolvingServices.remove(failedServiceInfo.serviceName) // Remove from tracker on failure
            // Optionally remove from discoveredHosts if it was added prematurely? (Shouldn't be)
        }

        // Suppress deprecation for host property if supporting older APIs
        @Suppress("DEPRECATION")
        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
             if (resolvedServiceInfo.host == null || resolvedServiceInfo.port <= 0) {
                  logError("NSD service resolved but host or port is invalid: ${resolvedServiceInfo.serviceName} - Host=${resolvedServiceInfo.host}, Port=${resolvedServiceInfo.port}")
                  resolvingServices.remove(resolvedServiceInfo.serviceName)
                  // Remove from discovered list if it somehow got added
                  viewModelScope.launch(Dispatchers.Main) {
                       _discoveredHosts.update { list -> list.filterNot { it.serviceName == resolvedServiceInfo.serviceName } }
                  }
                  return // Don't add invalid service
             }

            log("NSD service RESOLVED: ${resolvedServiceInfo.serviceName} at ${resolvedServiceInfo.host}:${resolvedServiceInfo.port}")
            // Update the list on the Main thread
            viewModelScope.launch(Dispatchers.Main) {
                _discoveredHosts.update { currentList ->
                    val existingIndex = currentList.indexOfFirst { it.serviceName == resolvedServiceInfo.serviceName }
                    if (existingIndex != -1) {
                        // Update existing entry with potentially more complete info
                        currentList.toMutableList().apply { set(existingIndex, resolvedServiceInfo) }
                    } else {
                        // Add new resolved entry
                        currentList + resolvedServiceInfo
                    }
                    // Sort list? Maybe alphabetically?
                    // .sortedBy { it.serviceName }
                }
            }
            resolvingServices.remove(resolvedServiceInfo.serviceName) // Remove from tracker on success
        }
    }

    try {
        // Call resolveService using the created listener
        nsdManager?.resolveService(serviceInfo, listener)
    } catch (e: Exception) {
        logError("Exception calling resolveService for $serviceName", e)
        resolvingServices.remove(serviceName) // Remove from tracker on exception during the call
    }
}


/** CLIENT: Stops NSD discovery */
fun GameViewModel.stopNsdDiscovery() {
    if (isHost || nsdManager == null || discoveryListener == null) {
        log("Client: Skipping NSD stop (not client, manager null, or listener null).")
        return
    }

    val listenerToStop = discoveryListener // Capture current listener
    discoveryListener = null // Nullify immediately

    log("Client: Stopping NSD discovery...")
    try {
        nsdManager?.stopServiceDiscovery(listenerToStop)
    } catch (e: IllegalArgumentException) {
        // This often means it was already stopped or never started successfully
        log("NSD Discovery likely already stopped: ${e.message}")
    } catch (e: Exception) {
        logError("Error stopping NSD discovery", e)
    } finally {
        // Clear list and tracker when explicitly stopping discovery
        viewModelScope.launch(Dispatchers.Main) {
            _discoveredHosts.value = emptyList()
        }
        resolvingServices.clear()
    }
}