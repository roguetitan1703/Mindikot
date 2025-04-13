package com.example.mindikot.ui.viewmodel.utils

import com.example.mindikot.ui.viewmodel.GameViewModel
import java.net.InetAddress
import java.net.NetworkInterface
import android.util.Log // Use Android's Log for better debugging/filtering

// --- Logging ---
fun GameViewModel.log(message: String, tag: String = "GameViewModel") {
    // println("[$tag] $message") // Use Android Log instead
    Log.d(tag, message)
}

fun GameViewModel.logError(message: String, error: Throwable? = null, tag: String = "GameViewModel") {
    // val errorMsg = error?.message?.let { ": $it" } ?: ""
    // println("[GameViewModel ERROR] $message$errorMsg") // Use Android Log instead
    Log.e(tag, message, error)
}


// --- Network Utility ---
/** Gets the local IP address (needs refinement for robustness) */
fun GameViewModel.getLocalIpAddress(): InetAddress? {
    // This is a basic implementation. Consider libraries or more checks for complex networks.
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList()
        interfaces
            ?.flatMap { intf ->
                intf.inetAddresses?.toList()?.filter { addr ->
                    !addr.isLoopbackAddress &&
                            addr is java.net.Inet4Address &&
                            // Prioritize common private ranges, adjust if needed for other network types
                            // Check for common private IPv4 ranges
                            (addr.isSiteLocalAddress || // Covers 10.x.x.x, 172.16-31.x.x, 192.168.x.x
                             addr.hostAddress?.startsWith("192.168.") == true) // Explicit check often reliable for WiFi
                } ?: emptyList()
            }
            // Prioritize addresses starting with 192.168. as they are most common for local WiFi
            ?.sortedByDescending { it.hostAddress?.startsWith("192.168.") == true }
            ?.firstOrNull()
    } catch (e: Exception) {
        logError("Could not determine local IP address", e)
        null
    }
}