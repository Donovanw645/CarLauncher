package com.donovan.carlauncher.system

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class NetType { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

data class CarStatus(
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    val btAudioDevice: String? = null,
    val netType: NetType = NetType.NONE,
    val wifiSsid: String? = null,
)

/**
 * Bluetooth / network / battery status for the top strip. Everything here is
 * best-effort: a missing permission or an OEM quirk degrades to "unknown" rather
 * than crashing the launcher.
 */
class CarStatusRepo(private val context: Context) {

    private val _state = MutableStateFlow(CarStatus())
    val state: StateFlow<CarStatus> = _state.asStateFlow()

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            _state.update { it.copy(batteryPct = pct, charging = charging) }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            refreshBluetooth()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetwork()
        override fun onLost(network: Network) = refreshNetwork()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            refreshNetwork()
    }

    fun start() {
        if (registered) return
        registered = true

        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }
        runCatching { context.registerReceiver(bluetoothReceiver, btFilter) }

        runCatching { cm.registerDefaultNetworkCallback(networkCallback) }

        refreshBluetooth()
        refreshNetwork()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { context.unregisterReceiver(bluetoothReceiver) }
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Reports the name of the A2DP sink we are streaming to - i.e. the car stereo.
     */
    @SuppressLint("MissingPermission")
    fun refreshBluetooth() {
        if (!hasBtPermission()) {
            _state.update { it.copy(btAudioDevice = null) }
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.update { it.copy(btAudioDevice = null) }
            return
        }
        val ok = runCatching {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val name = runCatching {
                        proxy.connectedDevices.firstOrNull()?.name
                    }.getOrNull()
                    _state.update { it.copy(btAudioDevice = name) }
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                }

                override fun onServiceDisconnected(profile: Int) {
                    _state.update { it.copy(btAudioDevice = null) }
                }
            }, BluetoothProfile.A2DP)
        }.getOrDefault(false)
        if (!ok) _state.update { it.copy(btAudioDevice = null) }
    }

    @Suppress("DEPRECATION")
    private fun refreshNetwork() {
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val type = when {
            caps == null -> NetType.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetType.ETHERNET
            else -> NetType.OTHER
        }
        var ssid: String? = null
        if (type == NetType.WIFI) {
            ssid = runCatching {
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                wm.connectionInfo?.ssid?.trim('"')?.takeIf {
                    it.isNotBlank() && !it.contains("unknown", ignoreCase = true)
                }
            }.getOrNull()
        }
        _state.update { it.copy(netType = type, wifiSsid = ssid) }
    }
}
