package me.magnum.melonds.ui.localmultiplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.magnum.melonds.R
import me.magnum.melonds.ui.common.melonButtonColors
import me.magnum.melonds.ui.localmultiplayer.LocalMultiplayerViewModel
import me.magnum.melonds.ui.localmultiplayer.model.LocalMultiplayerRole
import me.magnum.melonds.ui.localmultiplayer.model.LocalMultiplayerTransport
import me.magnum.melonds.ui.localmultiplayer.model.LocalMultiplayerUiState

@Composable
fun LocalMultiplayerScreen(
    viewModel: LocalMultiplayerViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onBackClick: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            Box(Modifier.background(MaterialTheme.colors.primaryVariant).statusBarsPadding()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.local_multiplayer)) },
                    backgroundColor = MaterialTheme.colors.primary,
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(painter = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack), contentDescription = null)
                        }
                    },
                    windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets(bottom = Int.MAX_VALUE)),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (!permissionsGranted) {
                Text(stringResource(R.string.local_multiplayer_permission_required))
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRequestPermissions, colors = melonButtonColors()) {
                    Text(stringResource(R.string.local_multiplayer_grant_permission))
                }
                return@Column
            }

            LocalMultiplayerContent(
                state = state,
                onTransportChange = viewModel::setTransport,
                onHost = viewModel::host,
                onSearch = viewModel::startSearching,
                onJoinPeer = viewModel::joinWifiDirectPeer,
                onJoinAddress = viewModel::joinAddress,
                onLeave = viewModel::leave,
            )
        }
    }
}

@Composable
private fun ColumnScope.LocalMultiplayerContent(
    state: LocalMultiplayerUiState,
    onTransportChange: (LocalMultiplayerTransport) -> Unit,
    onHost: () -> Unit,
    onSearch: () -> Unit,
    onJoinPeer: (me.magnum.melonds.ui.localmultiplayer.model.WifiDirectPeer) -> Unit,
    onJoinAddress: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val idle = state.role == LocalMultiplayerRole.NONE

    Text(stringResource(R.string.local_multiplayer_transport), style = MaterialTheme.typography.subtitle2)
    TransportOption(
        text = stringResource(R.string.local_multiplayer_transport_wifi_direct),
        selected = state.transport == LocalMultiplayerTransport.WIFI_DIRECT,
        enabled = idle && state.isWifiDirectSupported,
        onClick = { onTransportChange(LocalMultiplayerTransport.WIFI_DIRECT) },
    )
    TransportOption(
        text = stringResource(R.string.local_multiplayer_transport_shared_wifi),
        selected = state.transport == LocalMultiplayerTransport.SHARED_WIFI,
        enabled = idle,
        onClick = { onTransportChange(LocalMultiplayerTransport.SHARED_WIFI) },
    )
    if (!state.isWifiDirectSupported) {
        Text(stringResource(R.string.local_multiplayer_wifi_direct_unsupported), color = MaterialTheme.colors.error)
    } else if (state.transport == LocalMultiplayerTransport.WIFI_DIRECT && !state.isWifiDirectEnabled) {
        Text(stringResource(R.string.local_multiplayer_wifi_disabled), color = MaterialTheme.colors.error)
    }

    Spacer(Modifier.height(8.dp))
    state.localAddress?.let { Text(stringResource(R.string.local_multiplayer_local_address, it), style = MaterialTheme.typography.caption) }
    state.status?.let { Text(it) }
    state.error?.let { Text(it, color = MaterialTheme.colors.error) }
    Spacer(Modifier.height(8.dp))

    if (idle) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onHost, colors = melonButtonColors()) { Text(stringResource(R.string.local_multiplayer_host)) }
            OutlinedButton(onClick = onSearch) {
                Text(stringResource(if (state.isDiscovering) R.string.local_multiplayer_searching else R.string.local_multiplayer_search))
            }
        }

        if (state.transport == LocalMultiplayerTransport.SHARED_WIFI) {
            var address by remember { mutableStateOf("") }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.local_multiplayer_host_address)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onJoinAddress(address) }, colors = melonButtonColors()) { Text(stringResource(R.string.local_multiplayer_join)) }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.local_multiplayer_available_games), style = MaterialTheme.typography.subtitle2)
        Divider()
        LazyColumn(Modifier.fillMaxWidth()) {
            if (state.transport == LocalMultiplayerTransport.WIFI_DIRECT) {
                items(state.wifiDirectPeers) { peer ->
                    ListEntry(
                        title = peer.name,
                        subtitle = peer.address,
                        onClick = { onJoinPeer(peer) },
                    )
                }
            } else {
                items(state.discoveredSessions) { session ->
                    ListEntry(
                        title = session.name,
                        subtitle = "${session.address} · ${session.numPlayers}/${session.maxPlayers}",
                        onClick = { onJoinAddress(session.address) },
                    )
                }
            }
        }
    } else {
        Text(
            stringResource(if (state.role == LocalMultiplayerRole.HOST) R.string.local_multiplayer_role_host else R.string.local_multiplayer_role_client),
            style = MaterialTheme.typography.subtitle1,
        )
        Text(stringResource(R.string.local_multiplayer_instructions), style = MaterialTheme.typography.caption)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.local_multiplayer_players, state.players.size, state.maxPlayers), style = MaterialTheme.typography.subtitle2)
        Divider()
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            items(state.players) { player ->
                ListEntry(
                    title = "${player.id + 1}. ${player.name}" + if (player.isLocal) " (you)" else "",
                    subtitle = "${player.status.name.lowercase()} · ${player.address} · ${player.ping} ms",
                    onClick = null,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onLeave) { Text(stringResource(R.string.local_multiplayer_leave)) }
    }
}

@Composable
private fun TransportOption(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(text)
    }
}

@Composable
private fun ListEntry(title: String, subtitle: String, onClick: (() -> Unit)?) {
    val modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.body1)
        Text(subtitle, style = MaterialTheme.typography.caption)
    }
}
