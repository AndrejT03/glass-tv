package com.andrej.glasstv.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

enum class Link { None, Wifi, Ethernet }

/** How the TV is connected; [bars] is the Wi-Fi signal, 0..3, as in the iOS status bar. */
@Immutable
data class NetworkStatus(val link: Link, val bars: Int = 0) {
    companion object {
        val Offline = NetworkStatus(Link.None)
    }
}

/** The default network, updated as Wi-Fi comes and goes or its signal changes. */
@Composable
fun rememberNetwork(): State<NetworkStatus> {
    val context = LocalContext.current
    val cm = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    val status = remember {
        mutableStateOf(cm?.getNetworkCapabilities(cm.activeNetwork)?.let { statusOf(context, it) } ?: NetworkStatus.Offline)
    }
    DisposableEffect(cm) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                status.value = statusOf(context, caps)
            }

            override fun onLost(network: Network) {
                status.value = NetworkStatus.Offline
            }
        }
        // Delivered on the main thread, like every other change to the UI's state.
        val registered = cm != null && runCatching { cm.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper())) }.isSuccess
        onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return status
}

private fun statusOf(context: Context, caps: NetworkCapabilities): NetworkStatus = when {
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus(Link.Wifi, barsOf(wifiRssi(context, caps)))
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkStatus(Link.Ethernet)
    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkStatus(Link.Wifi, 3)
    else -> NetworkStatus.Offline
}

private fun wifiRssi(context: Context, caps: NetworkCapabilities): Int? {
    if (Build.VERSION.SDK_INT >= 29) {
        caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }?.let { return it }
    }
    @Suppress("DEPRECATION")
    return context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.rssi?.takeIf { it in -126..0 }
}

/** Three bars down to about -67 dBm (a good link), two to -78, one while it still holds. */
private fun barsOf(rssi: Int?): Int = when {
    rssi == null -> 3
    rssi >= -67 -> 3
    rssi >= -78 -> 2
    rssi >= -89 -> 1
    else -> 0
}
