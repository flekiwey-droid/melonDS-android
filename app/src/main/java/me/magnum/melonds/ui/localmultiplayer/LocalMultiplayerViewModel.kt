package me.magnum.melonds.ui.localmultiplayer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.MelonLocalMultiplayer
import me.magnum.melonds.impl.localmultiplayer.LocalMultiplayerSession
import me.magnum.melonds.impl.localmultiplayer.WifiDirectController
import me.magnum.melonds.ui.localmultiplayer.model.DiscoveredSession
import me.magnum.melonds.ui.localmultiplayer.model.LocalMultiplayerRole
import me.magnum.melonds.ui.localmultiplayer.model.LocalMultiplayerTransport
import me.magnum.melonds.ui.localmultiplayer.model.LocalMultiplayerUiState
import me.magnum.melonds.ui.localmultiplayer.model.MultiplayerPlayer
import me.magnum.melonds.ui.localmultiplayer.model.MultiplayerPlayerStatus
import me.magnum.melonds.ui.localmultiplayer.model.WifiDirectPeer
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalMultiplayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDirectController: WifiDirectController,
    private val session: LocalMultiplayerSession,
) : ViewModel() {

    companion object {
        private const val MAX_PLAYERS = 16
        private const val POLL_INTERVAL_MS = 16L
        private const val UI_REFRESH_EVERY_N_POLLS = 15
    }

    private val _state = MutableStateFlow(
        LocalMultiplayerUiState(
            transport = session.transport.value,
            role = session.role.value,
            isWifiDirectSupported = wifiDirectController.isSupported,
        )
    )
    val state: StateFlow<LocalMultiplayerUiState> = _state.asStateFlow()

    // Single thread so that all lobby-side native calls are serialized
    private val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var pollJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val playerName = (Build.MODEL ?: "Android").take(31)

    init {
        viewModelScope.launch {
            wifiDirectController.peers.collect { peers ->
                _state.update { it.copy(wifiDirectPeers = peers.map { p -> WifiDirectPeer(p.deviceName.ifBlank { p.deviceAddress }, p.deviceAddress, p.status == android.net.wifi.p2p.WifiP2pDevice.AVAILABLE) }) }
            }
        }
        viewModelScope.launch {
            wifiDirectController.enabled.collect { enabled -> _state.update { it.copy(isWifiDirectEnabled = enabled) } }
        }
        viewModelScope.launch {
            wifiDirectController.lastFailure.collect { failure ->
                if (failure != null) {
                    _state.update { it.copy(error = "Wi-Fi Direct ${failure.operation} failed (reason ${failure.reason})") }
                }
            }
        }
        viewModelScope.launch {
            wifiDirectController.connection.collect { connection ->
                // Wi-Fi Direct client: once we have joined the host's group, connect to the host over UDP
                if (session.transport.value == LocalMultiplayerTransport.WIFI_DIRECT && session.role.value == LocalMultiplayerRole.CLIENT &&
                    connection.groupFormed && !connection.isGroupOwner && !session.nativeSessionStarted.value) {
                    val hostAddress = connection.groupOwnerAddress ?: return@collect
                    startNativeClient(hostAddress)
                }
                _state.update { it.copy(localAddress = findLocalAddress()) }
            }
        }
    }

    /** Called when the permissions are granted and the screen becomes visible */
    fun onStart() {
        if (wifiDirectController.isSupported) {
            wifiDirectController.initialize()
        }
        acquireMulticastLock()
        _state.update { it.copy(localAddress = findLocalAddress()) }
        startPolling()
    }

    fun onStop() {
        stopPolling()
        wifiDirectController.stopDiscovery()
        _state.update { it.copy(isDiscovering = false) }
        // Keep the multicast lock and the session alive only if a session is running (the user is going to play)
        if (!session.nativeSessionStarted.value) {
            releaseMulticastLock()
        }
    }

    fun setTransport(transport: LocalMultiplayerTransport) {
        if (session.role.value != LocalMultiplayerRole.NONE) return
        session.transport.value = transport
        _state.update { it.copy(transport = transport, error = null) }
    }

    fun host() {
        if (session.role.value != LocalMultiplayerRole.NONE) return
        session.role.value = LocalMultiplayerRole.HOST
        _state.update { it.copy(role = LocalMultiplayerRole.HOST, error = null) }

        viewModelScope.launch {
            val started = withContext(nativeDispatcher) { MelonLocalMultiplayer.startHost(playerName, MAX_PLAYERS) }
            if (!started) {
                fail("Could not start the host (UDP port 7064 busy or a game is running)")
                return@launch
            }
            session.nativeSessionStarted.value = true
            if (session.transport.value == LocalMultiplayerTransport.WIFI_DIRECT) {
                // The host is always the group owner of an autonomous group (192.168.49.1)
                wifiDirectController.createGroup {
                    // Keep discovery running so that clients can find this device
                    wifiDirectController.discoverPeers()
                }
                _state.update { it.copy(status = "Creating Wi-Fi Direct group…") }
            } else {
                _state.update { it.copy(status = "Hosting on the current Wi-Fi network") }
            }
        }
    }

    fun startSearching() {
        if (session.role.value == LocalMultiplayerRole.HOST) return
        _state.update { it.copy(isDiscovering = true, error = null) }
        if (session.transport.value == LocalMultiplayerTransport.WIFI_DIRECT) {
            wifiDirectController.discoverPeers()
        } else {
            viewModelScope.launch(nativeDispatcher) { MelonLocalMultiplayer.startDiscovery() }
        }
    }

    fun joinWifiDirectPeer(peer: WifiDirectPeer) {
        if (session.role.value != LocalMultiplayerRole.NONE) return
        session.role.value = LocalMultiplayerRole.CLIENT
        _state.update { it.copy(role = LocalMultiplayerRole.CLIENT, status = "Connecting to ${peer.name}…", error = null) }
        wifiDirectController.connect(peer.address)
    }

    fun joinAddress(address: String) {
        if (session.role.value != LocalMultiplayerRole.NONE) return
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return
        session.role.value = LocalMultiplayerRole.CLIENT
        _state.update { it.copy(role = LocalMultiplayerRole.CLIENT, error = null) }
        startNativeClient(trimmed)
    }

    fun leave() {
        viewModelScope.launch {
            withContext(nativeDispatcher) {
                MelonLocalMultiplayer.endDiscovery()
                MelonLocalMultiplayer.endSession()
            }
            if (session.transport.value == LocalMultiplayerTransport.WIFI_DIRECT) {
                wifiDirectController.disconnect()
            }
            session.role.value = LocalMultiplayerRole.NONE
            session.nativeSessionStarted.value = false
            _state.update { it.copy(role = LocalMultiplayerRole.NONE, players = emptyList(), maxPlayers = 0, status = null, error = null, isDiscovering = false) }
        }
    }

    private fun startNativeClient(hostAddress: String) {
        _state.update { it.copy(status = "Connecting to $hostAddress…") }
        viewModelScope.launch {
            withContext(nativeDispatcher) { MelonLocalMultiplayer.endDiscovery() }
            val started = withContext(nativeDispatcher) { MelonLocalMultiplayer.startClient(playerName, hostAddress) }
            if (started) {
                session.nativeSessionStarted.value = true
                _state.update { it.copy(status = "Connected to $hostAddress", isDiscovering = false) }
            } else {
                fail("Could not connect to $hostAddress")
            }
        }
    }

    private fun fail(message: String) {
        session.role.value = LocalMultiplayerRole.NONE
        session.nativeSessionStarted.value = false
        _state.update { it.copy(role = LocalMultiplayerRole.NONE, status = null, error = message) }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch(nativeDispatcher) {
            var counter = 0
            while (isActive) {
                MelonLocalMultiplayer.process()
                if (counter++ % UI_REFRESH_EVERY_N_POLLS == 0) {
                    val players = MelonLocalMultiplayer.getPlayers().mapNotNull(::parsePlayer)
                    val sessions = MelonLocalMultiplayer.getDiscoveredSessions().mapNotNull(::parseSession)
                    val maxPlayers = MelonLocalMultiplayer.getMaxPlayers()
                    _state.update { it.copy(players = players, discoveredSessions = sessions, maxPlayers = maxPlayers) }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun parsePlayer(raw: String): MultiplayerPlayer? {
        val parts = raw.split('|')
        if (parts.size < 6) return null
        return MultiplayerPlayer(
            id = parts[0].toIntOrNull() ?: return null,
            name = parts[1],
            status = MultiplayerPlayerStatus.fromNative(parts[2].toIntOrNull() ?: 0),
            address = parts[3],
            ping = parts[4].toIntOrNull() ?: 0,
            isLocal = parts[5] == "1",
        )
    }

    private fun parseSession(raw: String): DiscoveredSession? {
        val parts = raw.split('|')
        if (parts.size < 4) return null
        return DiscoveredSession(parts[0], parts[1], parts[2].toIntOrNull() ?: 0, parts[3].toIntOrNull() ?: 0)
    }

    private fun findLocalAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface -> iface.inetAddresses.toList().filterIsInstance<Inet4Address>().map { iface.name to it.hostAddress } }
                .sortedBy { (name, _) -> if (name.startsWith("p2p")) 0 else if (name.startsWith("wlan")) 1 else 2 }
                .joinToString(", ") { (name, address) -> "$address ($name)" }
                .ifEmpty { null }
        }.getOrNull()
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        // Needed to receive the LAN discovery broadcasts on many devices
        multicastLock = wifiManager.createMulticastLock("melonDS-localmp").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        wifiDirectController.release()
        if (!session.nativeSessionStarted.value) {
            releaseMulticastLock()
        }
    }
}
