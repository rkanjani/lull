package com.rohankanjani.lull

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * The Wi-Fi networks around the phone, to offer one to a BedJet. Listing them, and seeing which one the phone is on,
 * needs location access, which Lull asks for only on the Wi-Fi screen and uses for nothing else.
 */
class WiFiNetworks(private val context: Context) {
    /** One network as offered: [current] when it's the one this phone is on. */
    data class Choice(val name: String, val current: Boolean)

    /** One network heard in a scan, at one frequency in MHz and one signal level in dBm. */
    data class Heard(val name: String, val frequency: Int, val level: Int)

    var choices by mutableStateOf<List<Choice>>(emptyList()); private set

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var heard: List<Heard> = emptyList()
    private var current: String? = null

    val allowed: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Allowed only approximate location, which hides the networks' names. */
    val approximate: Boolean
        get() = !allowed &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Starts listening for scans and for the phone's own network; returns how to stop. */
    fun start(): () -> Unit {
        if (!allowed || wifi == null) return {}
        val scans = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = readScans()
        }
        ContextCompat.registerReceiver(context, scans, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        // The network's name is only included when asked for, as location information.
        val own = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val ssid = (capabilities.transportInfo as? WifiInfo)?.ssid
                current = ssid?.removeSurrounding("\"")?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isEmpty() }
                update()
            }

            override fun onLost(network: Network) {
                current = null
                update()
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        // On the main thread, like the scans, so the two never update the list at once.
        connectivity?.registerNetworkCallback(request, own, Handler(Looper.getMainLooper()))
        readScans()
        // Android limits how often apps scan; when it says no, the last scan's results are shown.
        @Suppress("DEPRECATION")
        wifi.startScan()
        return {
            context.unregisterReceiver(scans)
            connectivity?.unregisterNetworkCallback(own)
        }
    }

    private fun readScans() {
        if (!allowed) return
        heard = try {
            @Suppress("DEPRECATION")
            wifi?.scanResults.orEmpty().map { Heard(it.SSID.orEmpty(), it.frequency, it.level) }
        } catch (_: SecurityException) {
            emptyList()
        }
        update()
    }

    private fun update() {
        choices = choose(heard, current)
    }

    companion object {
        /** A demo BedJet's phone is on a pretend network, with a neighbour's nearby. */
        val demo = listOf(Choice("Home", current = true), Choice("Neighbour", current = false))

        /**
         * Each network once, at its strongest. A BedJet 3 only joins 2.4 GHz networks, so networks heard only at
         * 5 or 6 GHz are left out, and so are hidden ones, which have no name. The phone's own network comes first.
         */
        fun choose(heard: List<Heard>, current: String?): List<Choice> {
            val strongest = heard
                .filter { it.name.isNotEmpty() && it.frequency in 2400..2500 }
                .groupBy { it.name }
                .mapValues { (_, all) -> all.maxOf { it.level } }
                .entries
                .sortedByDescending { it.value }
                .map { Choice(it.key, current = it.key == current) }
            // A scan can miss the phone's own network; offer it anyway unless it was heard only on 5 or 6 GHz.
            if (current != null && heard.none { it.name == current }) return listOf(Choice(current, current = true)) + strongest
            return strongest.sortedByDescending { it.current }
        }
    }
}
