package me.magnum.melonds

/**
 * Native bridge to the melonDS core LAN MPInterface, which implements the DS local wireless (Platform::MP_*) layer over
 * UDP (ENet). The transport network can be a Wi-Fi Direct group or a regular shared Wi-Fi network.
 */
object MelonLocalMultiplayer {
    external fun startHost(playerName: String, maxPlayers: Int): Boolean
    external fun startClient(playerName: String, hostAddress: String): Boolean
    external fun startDiscovery(): Boolean
    external fun endDiscovery()
    external fun endSession()
    /** Pumps network events while the emulator is not running. Ignored natively while a game is running. */
    external fun process()
    /** Entries formatted as "id|name|status|address|ping|isLocal". */
    external fun getPlayers(): Array<String>
    /** Entries formatted as "address|sessionName|numPlayers|maxPlayers". */
    external fun getDiscoveredSessions(): Array<String>
    external fun getMaxPlayers(): Int
}
