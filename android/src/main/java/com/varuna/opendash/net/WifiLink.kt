package com.varuna.opendash.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.Socket

/**
 * Joining the adapter's own Wi-Fi, from inside the app.
 *
 * Two problems, one answer.
 *
 * The first is that the access point has no internet. A phone with mobile data
 * on keeps sending packets out over the mobile network — that is the whole
 * point of how Android chooses a route — so the socket to 192.168.81.1 never
 * arrives and the only workaround is turning mobile data off by hand. The fix
 * is not to change the phone's default route but to pin this one socket to this
 * one network, which is what [bind] does. Everything else in the app, plugin
 * downloads included, keeps using whatever route the phone would have chosen.
 *
 * The second is having to leave the app to join the network at all. From
 * Android 10 an app can ask for a network by name and passphrase, and the
 * system shows its own picker with the matching access points. Asked with a
 * name prefix rather than a full name, that picker **is** the list of what is
 * in range, which is what makes choosing possible without the location
 * permission that scanning would need.
 *
 * The network is deliberately requested without `NET_CAPABILITY_INTERNET`.
 * Asking for internet on a network that has none gets the request dropped after
 * the system validates it.
 */
object WifiLink {

    enum class State { OFF, JOINING, JOINED, FAILED }

    var state by mutableStateOf(State.OFF)
        private set

    /** What was asked for, shown back so the screen says something true. */
    var target by mutableStateOf("")
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    /** Access points whose name starts with this are offered by the picker. */
    const val DEFAULT_PREFIX = "SM"

    /** Joining by name needs Android 10; below that the settings panel is it. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @Volatile
    private var network: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Pin a socket to the adapter's network, if one is held.
     *
     * Called before the socket connects, because a socket cannot be moved to
     * another network once it has. Doing nothing when no network is held is
     * correct: the adapter may be reachable over the phone's normal Wi-Fi, and
     * on a tablet wired to it there is nothing to pin.
     */
    fun bind(socket: Socket) {
        network?.bindSocket(socket)
    }

    /**
     * Names of the access points in range, the ones matching [prefix] first.
     *
     * Sorted rather than filtered. An adapter whose access point has been
     * renamed would vanish from a filtered list and there would be no way to
     * tell that from it being out of range, which is the wrong failure for the
     * one screen whose job is to find it.
     *
     * Empty unless the location permission is granted, which is the rule
     * Android applies to scan results — a list of nearby networks says where
     * the phone is. The screen offers the system picker instead when this comes
     * back empty, so the permission is a convenience and never a requirement.
     */
    fun visible(context: Context, prefix: String = ""): List<String> {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        return try {
            wifi.scanResults
                .mapNotNull { nameOf(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedWith(
                    compareByDescending<String> { it.startsWith(prefix, ignoreCase = true) }
                        .thenBy { it.lowercase() }
                )
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun nameOf(result: android.net.wifi.ScanResult): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.wifiSsid?.toString()?.trim('"')
        } else {
            @Suppress("DEPRECATION")
            result.SSID
        }

    /**
     * Ask the system to join an access point and hold it while the app runs.
     *
     * [ssid] empty means "offer everything starting with [prefix]", which is
     * how the picker becomes a list. The system always asks the user to
     * confirm; an app cannot join a network silently, and should not be able
     * to.
     */
    fun join(context: Context, ssid: String, passphrase: String, prefix: String = DEFAULT_PREFIX) {
        if (!supported) {
            state = State.FAILED
            lastError = "joining a network from the app needs Android 10 or newer"
            return
        }
        leave(context)
        state = State.JOINING
        target = ssid.ifBlank { "$prefix…" }
        lastError = null
        try {
            requestQ(context, ssid, passphrase, prefix)
        } catch (e: Exception) {
            state = State.FAILED
            lastError = e.message ?: e.javaClass.simpleName
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestQ(context: Context, ssid: String, passphrase: String, prefix: String) {
        val specifier = WifiNetworkSpecifier.Builder().apply {
            if (ssid.isBlank()) setSsidPattern(PatternMatcher(prefix, PatternMatcher.PATTERN_PREFIX))
            else setSsid(ssid)
            if (passphrase.isNotBlank()) setWpa2Passphrase(passphrase)
        }.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cm = manager(context)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                network = net
                state = State.JOINED
                lastError = null
            }

            override fun onUnavailable() {
                network = null
                state = State.FAILED
                lastError = "no access point matched, or the request was refused"
            }

            override fun onLost(net: Network) {
                network = null
                if (state == State.JOINED) state = State.OFF
            }
        }
        callback = cb
        cm.requestNetwork(request, cb)
    }

    /** Give the network back. The phone returns to its usual route at once. */
    fun leave(context: Context) {
        callback?.let {
            try {
                manager(context).unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        network = null
        if (state != State.FAILED) {
            state = State.OFF
            target = ""
        }
    }

    private fun manager(context: Context): ConnectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
}
