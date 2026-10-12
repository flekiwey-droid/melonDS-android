package me.magnum.melonds.impl.localmultiplayer

import kotlinx.coroutines.flow.MutableStateFlow
import me.magnum.melonds.ui.localmultiplayer.model.LocalMultiplayerRole
import me.magnum.melonds.ui.localmultiplayer.model.LocalMultiplayerTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps track of the current local multiplayer session. The session must outlive the lobby screen, since the user
 * leaves it to launch the game.
 */
@Singleton
class LocalMultiplayerSession @Inject constructor() {
    val role = MutableStateFlow(LocalMultiplayerRole.NONE)
    val transport = MutableStateFlow(LocalMultiplayerTransport.WIFI_DIRECT)
    /** Whether the native LAN session (host or client) has been started */
    val nativeSessionStarted = MutableStateFlow(false)
}
