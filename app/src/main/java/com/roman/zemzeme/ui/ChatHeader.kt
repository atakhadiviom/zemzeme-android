package com.roman.zemzeme.ui


import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import com.roman.zemzeme.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roman.zemzeme.core.ui.utils.singleOrTripleClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Header components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */




@Composable
fun TorStatusDot(
    modifier: Modifier = Modifier
) {
    val torProvider = remember { com.roman.zemzeme.net.ArtiTorManager.getInstance() }
    val torStatus by torProvider.statusFlow.collectAsState()
    
    if (torStatus.mode != com.roman.zemzeme.net.TorMode.OFF) {
        val dotColor = when {
            torStatus.running && torStatus.bootstrapPercent < 100 -> Color(0xFFFF9500) // Orange - bootstrapping
            torStatus.running && torStatus.bootstrapPercent >= 100 -> Color(0xFF00F5FF) // Green - connected
            else -> Color.Red // Red - error/disconnected
        }
        Canvas(
            modifier = modifier
        ) {
            val radius = size.minDimension / 2
            drawCircle(
                color = dotColor,
                radius = radius,
                center = Offset(size.width / 2, size.height / 2)
            )
        }
    }
}

@Composable
fun NoiseSessionIcon(
    sessionState: String?,
    modifier: Modifier = Modifier
) {
    val (icon, color, contentDescription) = when (sessionState) {
        "uninitialized" -> Triple(
            Icons.Outlined.NoEncryption,
            Color(0x87878700), // Grey - ready to establish
            stringResource(R.string.cd_ready_for_handshake)
        )
        "handshaking" -> Triple(
            Icons.Outlined.Sync,
            Color(0x87878700), // Grey - in progress
            stringResource(R.string.cd_handshake_in_progress)
        )
        "established" -> Triple(
            Icons.Filled.Lock,
            Color(0xFFFF9500), // Orange - secure
            stringResource(R.string.cd_encrypted)
        )
        else -> { // "failed" or any other state
            Triple(
                Icons.Outlined.Warning,
                Color(0xFFFF4444), // Red - error
                stringResource(R.string.cd_handshake_failed)
            )
        }
    }
    
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = color
    )
}

@Composable
fun NicknameEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    
    // Auto-scroll to end when text changes (simulates cursor following)
    LaunchedEffect(value) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            text = stringResource(R.string.at_symbol),
            fontSize = 20.sp,
            color = colorScheme.primary.copy(alpha = 0.8f)
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.titleLarge.copy(
                color = colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier
                .widthIn(max = 160.dp)
                .horizontalScroll(scrollState)
        )
    }
}

/**
 * P2P connection status indicator dot.
 * Shows: Orange = connecting, Green = connected
 */
@Composable
fun P2PConnectionDot(
    connectionState: com.roman.zemzeme.p2p.TopicConnectionState,
    modifier: Modifier = Modifier
) {
    val dotColor = when (connectionState) {
        com.roman.zemzeme.p2p.TopicConnectionState.CONNECTING -> Color(0xFFFF9500) // Orange
        com.roman.zemzeme.p2p.TopicConnectionState.CONNECTED -> Color(0xFF00F5FF) // Green
        com.roman.zemzeme.p2p.TopicConnectionState.NO_PEERS -> Color(0xFFFF9500)
        com.roman.zemzeme.p2p.TopicConnectionState.ERROR -> Color.Red
    }
    
    Canvas(
        modifier = modifier.size(8.dp)
    ) {
        drawCircle(
            color = dotColor,
            radius = size.minDimension / 2,
            center = Offset(size.width / 2, size.height / 2)
        )
    }
}

@Composable
fun PeerCounter(
    connectedPeers: List<String>,
    joinedChannels: Set<String>,
    hasUnreadChannels: Map<String, Int>,
    isConnected: Boolean,
    selectedLocationChannel: com.roman.zemzeme.geohash.ChannelID?,
    geohashPeople: List<GeoPerson>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    // Compute channel-aware people count, display text, and color
    val (displayText, countColor) = when (selectedLocationChannel) {
        is com.roman.zemzeme.geohash.ChannelID.Location -> {
            // Geohash channel: show geohash participants with P2P/Nostr split
            val nostrCount = geohashPeople.count { it.transport == TransportType.NOSTR }
            val p2pCount = geohashPeople.count { it.transport == TransportType.P2P }
            val totalCount = geohashPeople.size
            
            // Show format: "3" (total only) or "2|1" (nostr|p2p) if both active
            val text = if (p2pCount > 0 && nostrCount > 0) {
                "$nostrCount|$p2pCount"  // Purple|Green split
            } else {
                "$totalCount"
            }
            
            val green = Color(0xFF00F5FF)
            val purple = Color(0xFF9C27B0)
            // Color: green if P2P, purple if only Nostr, gray if none
            val color = when {
                p2pCount > 0 -> green
                nostrCount > 0 -> purple
                else -> Color.Gray
            }
            Pair(text, color)
        }
        is com.roman.zemzeme.geohash.ChannelID.Mesh,
        null -> {
            // Mesh channel: show Bluetooth-connected peers (excluding self)
            val count = connectedPeers.size
            val meshBlue = Color(0xFF007AFF) // iOS-style blue for mesh
            Pair("$count", if (isConnected && count > 0) meshBlue else Color.Gray)
        }
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onClick() }.padding(end = 8.dp) // Added right margin to match "zemzeme" logo spacing
    ) {
        Icon(
            imageVector = Icons.Default.Group,
            contentDescription = when (selectedLocationChannel) {
                is com.roman.zemzeme.geohash.ChannelID.Location -> stringResource(R.string.cd_geohash_participants)
                else -> stringResource(R.string.cd_connected_peers)
            },
            modifier = Modifier.size(24.dp),
            tint = countColor
        )
        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = displayText,
            fontSize = 20.sp,
            color = countColor,
            fontWeight = FontWeight.Medium
        )

        if (joinedChannels.isNotEmpty()) {
            Text(
                text = stringResource(R.string.channel_count_prefix) + "${joinedChannels.size}",
                fontSize = 20.sp,
                color = if (isConnected) Color(0xFF00F5FF) else Color.Red,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ChatHeaderContent(
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    viewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onBackToHome: () -> Unit,
    onSidebarClick: () -> Unit,
    onTripleClick: () -> Unit,
    onShowAppInfo: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    when {
        currentChannel != null -> {
            // Channel header
            ChannelHeader(
                channel = currentChannel,
                onBackClick = onBackClick,
                onLeaveChannel = { viewModel.leaveChannel(currentChannel) },
                onSidebarClick = onSidebarClick
            )
        }
        else -> {
            // Main header
            MainHeader(
                onBackToHome = onBackToHome,
                onSidebarClick = onSidebarClick,
                viewModel = viewModel
            )
        }
    }
}



@Composable
private fun ChannelHeader(
    channel: String,
    onBackClick: () -> Unit,
    onLeaveChannel: () -> Unit,
    onSidebarClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: back arrow + channel name
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.size(24.dp),
                    tint = colorScheme.primary
                )
            }

            Text(
                text = stringResource(R.string.chat_channel_prefix, channel),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Default,
                letterSpacing = 0.sp,
                color = Color.White,
                modifier = Modifier.clickable { onSidebarClick() }
            )
        }

        // Right: leave button
        TextButton(onClick = onLeaveChannel) {
            Text(
                text = stringResource(R.string.chat_leave),
                fontSize = 16.sp,
                color = Color.Red
            )
        }
    }
}

@Composable
private fun MainHeader(
    onBackToHome: () -> Unit,
    onSidebarClick: () -> Unit,
    viewModel: ChatViewModel
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val hasUnreadChannels by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: back button + channel name
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBackToHome,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.size(24.dp),
                    tint = colorScheme.primary
                )
            }

            LocationChannelsButton(
                viewModel = viewModel
            )
        }

        // Right: status indicators and actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Unread private messages badge
            if (hasUnreadPrivateMessages.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Email,
                    contentDescription = stringResource(R.string.cd_unread_private_messages),
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { viewModel.openLatestUnreadPrivateChat() },
                    tint = Color(0xFFFF9500)
                )
            }

            // Status dots: Tor + PoW
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TorStatusDot(modifier = Modifier.size(12.dp))
                PoWStatusIndicator(
                    modifier = Modifier,
                    style = PoWIndicatorStyle.COMPACT
                )
            }

            // Unread channel badge
            val totalUnread = hasUnreadChannels.values.sum()
            if (totalUnread > 0) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Red,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = if (totalUnread > 99) "99+" else "$totalUnread",
                                fontSize = 11.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Peer counter
            PeerCounter(
                connectedPeers = connectedPeers.filter { it != viewModel.meshService.myPeerID },
                joinedChannels = joinedChannels,
                hasUnreadChannels = hasUnreadChannels,
                isConnected = isConnected,
                selectedLocationChannel = selectedLocationChannel,
                geohashPeople = geohashPeople,
                onClick = onSidebarClick
            )
        }
    }
}

@Composable
private fun LocationChannelsButton(
    viewModel: ChatViewModel
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current

    // Ensure transport toggle flow is initialized from persisted prefs.
    val transportConfig = remember { com.roman.zemzeme.p2p.P2PConfig(context) }
    val transportToggles by remember(transportConfig) {
        com.roman.zemzeme.p2p.P2PConfig.transportTogglesFlow
    }.collectAsStateWithLifecycle()
    val p2pEnabled = transportToggles.p2pEnabled
    val nostrEnabled = transportToggles.nostrEnabled

    val nostrRelayManager = remember { com.roman.zemzeme.nostr.NostrRelayManager.getInstance(context) }
    val nostrConnected by nostrRelayManager.isConnected.collectAsStateWithLifecycle()
    val nostrRelays by nostrRelayManager.relays.collectAsStateWithLifecycle()

    // Get current channel selection from location manager
    val selectedChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val teleported by viewModel.isTeleported.collectAsStateWithLifecycle()

    // Get P2P topic states for geohash channels
    val p2pTopicStates by viewModel.p2pTopicStates.collectAsStateWithLifecycle()

    // Get group nicknames and location names for display
    val groupNicknames by viewModel.groupNicknames.collectAsStateWithLifecycle()
    val locationChannelManager = remember {
        try { com.roman.zemzeme.geohash.LocationChannelManager.getInstance(context) } catch (_: Exception) { null }
    }
    val locationNames by locationChannelManager?.locationNames?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyMap()) }

    val (badgeText, badgeColor) = when (selectedChannel) {
        is com.roman.zemzeme.geohash.ChannelID.Mesh -> {
            "#mesh" to Color.White
        }
        is com.roman.zemzeme.geohash.ChannelID.Location -> {
            val geohash = (selectedChannel as com.roman.zemzeme.geohash.ChannelID.Location).channel.geohash
            val level = (selectedChannel as com.roman.zemzeme.geohash.ChannelID.Location).channel.level
            // Try group nickname first, then location name, then fallback to geohash
            val displayName = groupNicknames[geohash]
                ?: locationNames[level]
                ?: geohash
            "#$displayName" to Color.White
        }
        null -> "#mesh" to Color.White
    }

    // Get P2P connection state for current geohash channel
    val p2pConnectionState = when (val channel = selectedChannel) {
        is com.roman.zemzeme.geohash.ChannelID.Location -> {
            if (p2pEnabled) {
                val topicName = channel.channel.geohash
                p2pTopicStates[topicName]?.connectionState
            } else {
                null
            }
        }
        else -> null
    }

    val nostrConnectionState = when (selectedChannel) {
        is com.roman.zemzeme.geohash.ChannelID.Location -> {
            if (nostrEnabled) {
                when {
                    nostrConnected || nostrRelays.any { it.isConnected } -> com.roman.zemzeme.p2p.TopicConnectionState.CONNECTED
                    nostrRelays.any { it.lastError != null } -> com.roman.zemzeme.p2p.TopicConnectionState.ERROR
                    else -> com.roman.zemzeme.p2p.TopicConnectionState.CONNECTING
                }
            } else {
                null
            }
        }
        else -> null
    }

    val transportConnectionState = p2pConnectionState ?: nostrConnectionState
    val needsRefresh = p2pConnectionState == com.roman.zemzeme.p2p.TopicConnectionState.ERROR

    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = badgeText,
                fontSize = 22.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Bold,
                color = badgeColor,
                maxLines = 1
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Connection status dot
            if (transportConnectionState != null) {
                // Geohash channel: use P2P/Nostr transport state
                P2PConnectionDot(
                    connectionState = transportConnectionState,
                    modifier = Modifier.size(10.dp)
                )

                // P2P refresh button (visible only on error)
                if (needsRefresh) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh P2P connection",
                        modifier = Modifier
                            .size(22.dp)
                            .clickable {
                                Log.d("ChatHeader", "P2P refresh button clicked")
                                viewModel.refreshP2PConnection()
                            },
                        tint = Color(0xFFFF9500) // Orange to indicate action needed
                    )
                }
            } else {
                // Mesh channel: green when connected, red when disconnected
                Canvas(modifier = Modifier.size(10.dp)) {
                    drawCircle(
                        color = if (isConnected) Color(0xFF4CAF50) else Color.Red,
                        radius = size.minDimension / 2,
                        center = Offset(size.width / 2, size.height / 2)
                    )
                }
            }
        }

        // Nickname subtitle
        Text(
            text = "@$nickname",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.5f),
            maxLines = 1
        )
    }
}
