package me.magnum.melonds.ui.localmultiplayer.model

enum class LocalMultiplayerTransport {
    WIFI_DIRECT,
    SHARED_WIFI,
}

enum class LocalMultiplayerRole {
    NONE,
    HOST,
    CLIENT,
}

enum class MultiplayerPlayerStatus {
    NONE, CLIENT, HOST, CONNECTING, DISCONNECTED;

    companion object {
        fun fromNative(value: Int) = entries.getOrElse(value) { NONE }
    }
}

data class MultiplayerPlayer(
    val id: Int,
    val name: String,
    val status: MultiplayerPlayerStatus,
    val address: String,
    val ping: Int,
    val isLocal: Boolean,
)

data class WifiDirectPeer(
    val name: String,
    val address: String,
    val isAvailable: Boolean,
)

data class DiscoveredSession(
    val address: String,
    val name: String,
    val numPlayers: Int,
    val maxPlayers: Int,
)

data class LocalMultiplayerUiState(
    val transport: LocalMultiplayerTransport = LocalMultiplayerTransport.WIFI_DIRECT,
    val role: LocalMultiplayerRole = LocalMultiplayerRole.NONE,
    val isWifiDirectSupported: Boolean = true,
    val isWifiDirectEnabled: Boolean = true,
    val isDiscovering: Boolean = false,
    val wifiDirectPeers: List<WifiDirectPeer> = emptyList(),
    val discoveredSessions: List<DiscoveredSession> = emptyList(),
    val players: List<MultiplayerPlayer> = emptyList(),
    val maxPlayers: Int = 0,
    val localAddress: String? = null,
    val status: String? = null,
    val error: String? = null,
)
