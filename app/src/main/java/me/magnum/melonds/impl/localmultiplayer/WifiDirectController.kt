package me.magnum.melonds.impl.localmultiplayer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [WifiP2pManager]. The host creates an autonomous group (and therefore is always the group owner,
 * reachable at 192.168.49.1) and clients join it. Once the group is formed, the DS wireless traffic is carried by the
 * core's UDP transport over the P2P interface.
 *
 * Callers are responsible for holding the required runtime permission (NEARBY_WIFI_DEVICES on API 33+,
 * ACCESS_FINE_LOCATION below).
 */
@Singleton
@SuppressLint("MissingPermission")
class WifiDirectController @Inject constructor(@ApplicationContext private val context: Context) {

    data class ConnectionState(
        val groupFormed: Boolean = false,
        val isGroupOwner: Boolean = false,
        val groupOwnerAddress: String? = null,
    )

    sealed class Event {
        data class Failure(val operation: String, val reason: Int) : Event()
    }

    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _lastFailure = MutableStateFlow<Event.Failure?>(null)
    val lastFailure: StateFlow<Event.Failure?> = _lastFailure.asStateFlow()

    val isSupported: Boolean get() = manager != null && context.packageManager.hasSystemFeature("android.hardware.wifi.direct")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED)
                    _enabled.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    val list = getParcelable(intent, WifiP2pManager.EXTRA_P2P_DEVICE_LIST, WifiP2pDeviceList::class.java)
                    if (list != null) {
                        _peers.value = list.deviceList.toList()
                    } else {
                        channel?.let { manager?.requestPeers(it) { peers -> _peers.value = peers?.deviceList?.toList().orEmpty() } }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = getParcelable(intent, WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    @Suppress("DEPRECATION")
                    if (networkInfo?.isConnected == true) {
                        requestConnectionInfo()
                    } else {
                        _connection.value = ConnectionState()
                    }
                }
            }
        }
    }

    fun initialize() {
        if (manager == null) return
        if (channel == null) {
            channel = manager.initialize(context, Looper.getMainLooper()) {
                // Channel lost (framework restarted). Re-initialize on next use.
                channel = null
            }
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        requestConnectionInfo()
    }

    fun release() {
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        stopDiscovery()
        _peers.value = emptyList()
    }

    fun discoverPeers() {
        val ch = ensureChannel() ?: return
        manager?.discoverPeers(ch, actionListener("discoverPeers"))
    }

    fun stopDiscovery() {
        val ch = channel ?: return
        manager?.stopPeerDiscovery(ch, null)
    }

    /**
     * Creates an autonomous group. The device creating it is always the group owner.
     */
    fun createGroup(onCreated: () -> Unit = {}) {
        val ch = ensureChannel() ?: return
        // Remove any stale group first, then create a fresh one
        manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = doCreateGroup(ch, onCreated)
            override fun onFailure(reason: Int) = doCreateGroup(ch, onCreated)
        })
    }

    private fun doCreateGroup(ch: WifiP2pManager.Channel, onCreated: () -> Unit) {
        manager?.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onCreated()
                requestConnectionInfo()
            }
            override fun onFailure(reason: Int) {
                _lastFailure.value = Event.Failure("createGroup", reason)
            }
        })
    }

    fun connect(deviceAddress: String) {
        val ch = ensureChannel() ?: return
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            @Suppress("DEPRECATION")
            wps.setup = WpsInfo.PBC
            // We want the host (which already owns an autonomous group) to stay group owner
            groupOwnerIntent = 0
        }
        manager?.connect(ch, config, actionListener("connect"))
    }

    fun disconnect() {
        val ch = channel ?: return
        manager?.cancelConnect(ch, null)
        manager?.removeGroup(ch, null)
        _connection.value = ConnectionState()
    }

    private fun requestConnectionInfo() {
        val ch = channel ?: return
        manager?.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
            _connection.value = if (info != null && info.groupFormed) {
                ConnectionState(
                    groupFormed = true,
                    isGroupOwner = info.isGroupOwner,
                    groupOwnerAddress = info.groupOwnerAddress?.hostAddress,
                )
            } else {
                ConnectionState()
            }
        }
    }

    private fun ensureChannel(): WifiP2pManager.Channel? {
        if (channel == null) initialize()
        return channel
    }

    private fun actionListener(operation: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {}
        override fun onFailure(reason: Int) {
            _lastFailure.value = Event.Failure(operation, reason)
        }
    }

    private fun <T> getParcelable(intent: Intent, key: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, clazz)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }
    }
}
