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

    /**
     * Access points whose name starts with this are offered by the picker.
     *
     * One of these adapters calls itself `DIRECT-SCANMATIK-#<serial>`: a Wi-Fi
     * Direct name, the make, and its serial number. Only the settings value is
     * ever used; this is the fallback for a caller that passes nothing.
     */
    const val DEFAULT_PREFIX = "DIRECT-SCANMATIK"

    /** Long enough to read the picker and pick, short enough to give up. */
    private const val JOIN_TIMEOUT_MS = 60_000

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
     * Why a list of networks came back empty. An empty list on its own is the
     * one answer this screen must never give: four different things produce
     * it, three of them fixable by the person holding the phone, and a button
     * that appears to do nothing is what they all looked like.
     */
    enum class Why { OK, NO_PERMISSION, LOCATION_OFF, WIFI_OFF, THROTTLED, NOTHING_IN_RANGE }

    class Scan(val names: List<String>, val why: Why) {
        val isEmpty: Boolean get() = names.isEmpty()
    }

    /**
     * The permission that lets this app read scan results on this phone.
     *
     * From Android 13 there is one that means "find devices near me" without
     * claiming to be about location, and it is the right one to ask for: the
     * app wants an access point name, not a position. Below that, Android
     * treats the list of networks in range as location data and there is only
     * the one permission to ask for.
     */
    val scanPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun granted(context: Context, permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Names of the access points in range, the ones matching [prefix] first.
     *
     * Sorted rather than filtered. An adapter whose access point has been
     * renamed would vanish from a filtered list and there would be no way to
     * tell that from it being out of range, which is the wrong failure for the
     * one screen whose job is to find it.
     *
     * A scan is asked for rather than assumed. `getScanResults` hands back
     * whatever the system last found, and on a phone already joined to a
     * network that can be nothing at all, which is an empty list that means
     * "nobody has looked" rather than "there is nothing there".
     *
     * Below Android 13 the location master switch has to be on as well as the
     * permission granted, or the results come back empty with no error. That
     * is a setting, not a refusal, so it is reported separately.
     */
    fun visible(context: Context, prefix: String = ""): Scan {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return Scan(emptyList(), Why.WIFI_OFF)
        if (!wifi.isWifiEnabled) return Scan(emptyList(), Why.WIFI_OFF)
        if (!granted(context, scanPermission)) return Scan(emptyList(), Why.NO_PERMISSION)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !locationOn(context)) {
            return Scan(emptyList(), Why.LOCATION_OFF)
        }
        @Suppress("DEPRECATION")
        val started = try {
            wifi.startScan()
        } catch (_: Exception) {
            false
        }
        val names = try {
            wifi.scanResults
                .mapNotNull { nameOf(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedWith(
                    compareByDescending<String> { it.startsWith(prefix, ignoreCase = true) }
                        .thenBy { it.lowercase() }
                )
        } catch (_: SecurityException) {
            return Scan(emptyList(), Why.NO_PERMISSION)
        }
        if (names.isNotEmpty()) return Scan(names, Why.OK)
        // An empty list because the system would not scan is a different thing
        // from an empty list because nothing is there, and the first one is not
        // the user's fault: Android allows an app four scans in two minutes and
        // refuses the rest without a word. Pressing the button again in a
        // moment fixes it, and telling someone that beats a button that looks
        // broken.
        return Scan(emptyList(), if (started) Why.NOTHING_IN_RANGE else Why.THROTTLED)
    }

    private fun locationOn(context: Context): Boolean {
        val lm = context.applicationContext
            .getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
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
        // With a deadline, so a picker the user walks away from ends as a
        // refusal rather than leaving the screen saying "joining" for ever.
        cm.requestNetwork(request, cb, JOIN_TIMEOUT_MS)
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
