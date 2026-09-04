package com.dtn.mesh.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.receiver.MeshConnectionState

/* ==========================================================================
 *  Design palette
 * ========================================================================== */
private object Palette {
    val Primary = Color(0xFF0B7BC0)      // main brand blue
    val PrimaryDark = Color(0xFF075A8F)
    val Accent = Color(0xFF2E7D32)       // online / delivered / success
    val Warning = Color(0xFFF9A825)      // buffered / weak signal
    val Error = Color(0xFFC62828)        // offline / failed
    val Broadcast = Color(0xFF7E57C2)    // broadcast tint
    val BubbleOut = Color(0xFFDCF8C6)    // WhatsApp-like sent bubble
    val BubbleIn = Color(0xFFFFFFFF)     // received bubble
    val Surface = Color(0xFFF5F7FA)      // app background
    val CardSurface = Color(0xFFFFFFFF)  // card fill
    val Divider = Color(0xFFE1E4E8)
    val TextPrimary = Color(0xFF1B1F23)
    val TextSecondary = Color(0xFF6A737D)
    val TextMuted = Color(0xFF959DA5)

    // Deterministic per-nodeId avatar palette. Picks by hash of the id.
    private val avatarColors = listOf(
        Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFF81C784),
        Color(0xFFFFB74D), Color(0xFFBA68C8), Color(0xFF4DB6AC),
        Color(0xFF7986CB), Color(0xFFF06292), Color(0xFF4FC3F7),
        Color(0xFFAED581),
    )
    fun avatarFor(nodeId: String): Color =
        avatarColors[Math.floorMod(nodeId.hashCode(), avatarColors.size)]
}

/* ==========================================================================
 *  Root
 * ========================================================================== */

@Composable
fun MainScreen(viewModel: DtnViewModel) {
    val selectedChat by viewModel.selectedChat.collectAsState()
    if (selectedChat == null) {
        HomeScreen(viewModel)
    } else {
        // Route Android system-back to "go back to chat list" instead of exiting the app.
        BackHandler(enabled = true) { viewModel.openChat(null) }
        ChatDetailScreen(viewModel, chatKey = selectedChat!!)
    }
}

/* ==========================================================================
 *  Home screen — chats / network / lifecycle / log
 * ========================================================================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(viewModel: DtnViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val bufferedCount by viewModel.bufferedCount.collectAsState()
    val activeStrategy by viewModel.activeStrategy.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }

    // System back: if we're on a non-Chats tab, return to Chats. Only exit from Chats tab.
    BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

    Scaffold(
        containerColor = Palette.Surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "DTN Mesh Relay",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = Color.White,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Palette.Primary,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionCard(connectionState, localNodeId, activeStrategy, bufferedCount)
            ControlRow(connectionState, viewModel)

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Palette.CardSurface,
                contentColor = Palette.Primary,
            ) {
                TabItem("Chats", selectedTab == 0) { selectedTab = 0 }
                TabItem("Network", selectedTab == 1) { selectedTab = 1 }
                TabItem("Lifecycle", selectedTab == 2) { selectedTab = 2 }
                TabItem("Log", selectedTab == 3) { selectedTab = 3 }
            }
            HorizontalDivider(color = Palette.Divider)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    0 -> ChatListTab(viewModel)
                    1 -> NetworkTab(viewModel)
                    2 -> LifecycleTab(viewModel)
                    3 -> LogTab(logEntries)
                }
            }
        }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        selectedContentColor = Palette.Primary,
        unselectedContentColor = Palette.TextSecondary,
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 12.dp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontSize = 13.sp,
        )
    }
}

/* ==========================================================================
 *  Connection card + controls
 * ========================================================================== */

@Composable
private fun ConnectionCard(
    state: MeshConnectionState, nodeId: String?, strategy: String, buffered: Int,
) {
    val (statusColor, statusText) = when (state) {
        MeshConnectionState.CONNECTED -> Palette.Accent to "Connected"
        MeshConnectionState.CONNECTING -> Palette.Warning to "Connecting…"
        MeshConnectionState.DEVICE_SLEEP -> Palette.Warning to "Device sleep"
        MeshConnectionState.DISCONNECTED -> Palette.Error to "Disconnected"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(12.dp).clip(CircleShape).background(statusColor),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(statusText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    color = Palette.TextPrimary)
                Text(
                    "Node ${nodeId?.takeLast(8) ?: "—"}   ·   $strategy   ·   Buffer $buffered",
                    fontSize = 11.sp, color = Palette.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ControlRow(state: MeshConnectionState, viewModel: DtnViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == MeshConnectionState.DISCONNECTED) {
            Button(
                onClick = { viewModel.connect() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Primary),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Connect", fontWeight = FontWeight.SemiBold) }
        } else {
            OutlinedButton(
                onClick = { viewModel.disconnect() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Error),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Disconnect", fontWeight = FontWeight.SemiBold) }
        }
        FilledTonalButton(onClick = { viewModel.syncBuffer() },
            shape = RoundedCornerShape(10.dp)) { Text("Sync") }
        FilledTonalButton(onClick = { viewModel.clearBuffer() },
            shape = RoundedCornerShape(10.dp)) { Text("Clear") }
    }
}

/* ==========================================================================
 *  Chats tab
 * ========================================================================== */

@Composable
private fun ChatListTab(viewModel: DtnViewModel) {
    val peers by viewModel.peerList.collectAsState()
    val messages by viewModel.receivedMessages.collectAsState()
    var renameTarget by remember { mutableStateOf<PeerInfo?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            BroadcastCard(
                lastPreview = lastPreview(messages, chatKey = null),
                onClick = { viewModel.openChat(DtnViewModel.BROADCAST_CHAT_ID) },
            )
        }
        item { Spacer(Modifier.height(4.dp)) }
        if (peers.isEmpty()) {
            item { EmptyState("No peers seen yet.", "Connect and wait for discovery.") }
        } else {
            items(peers, key = { it.nodeId }) { peer ->
                PeerCard(
                    peer = peer,
                    subtitle = lastPreview(messages, chatKey = peer.nodeId)
                        ?: peer.nodeId.takeLast(8),
                    onClick = { viewModel.openChat(peer.nodeId) },
                    onEdit = { renameTarget = peer },
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    renameTarget?.let { peer ->
        RenameDialog(peer = peer, onDismiss = { renameTarget = null },
            onSave = { name -> viewModel.renamePeer(peer.nodeId, name); renameTarget = null })
    }
}

private fun lastPreview(all: List<ChatMessage>, chatKey: String?): String? {
    val filtered = when (chatKey) {
        null -> all.filter { it.toNodeId == null }
        else -> all.filter { it.toNodeId == chatKey }
    }
    val last = filtered.lastOrNull() ?: return null
    val prefix = if (last.isOutgoing) "You: " else ""
    return prefix + last.text.take(50)
}

@Composable
private fun BroadcastCard(lastPreview: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(CircleShape)
                    .background(Palette.Broadcast),
                contentAlignment = Alignment.Center,
            ) {
                Text("📢", fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Broadcast to All", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    color = Palette.TextPrimary)
                Text(
                    lastPreview ?: "Send to every peer in range",
                    fontSize = 12.sp, color = Palette.TextSecondary, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PeerCard(
    peer: PeerInfo,
    subtitle: String,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(peer = peer, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.displayName(), fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    color = Palette.TextPrimary, maxLines = 1)
                Text(subtitle, fontSize = 12.sp, color = Palette.TextSecondary, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                SignalBars(rssi = peer.lastRssi, isOnline = peer.isOnline)
                Spacer(Modifier.height(2.dp))
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.Edit, contentDescription = "Rename",
                        modifier = Modifier.size(16.dp),
                        tint = Palette.TextMuted,
                    )
                }
            }
        }
    }
}

/** Circular colored avatar with the first letter of the display name. Online peers get
 *  a subtle green ring; offline peers a red ring. */
@Composable
private fun PeerAvatar(peer: PeerInfo, size: androidx.compose.ui.unit.Dp) {
    val name = peer.displayName()
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val ringColor = if (peer.isOnline) Palette.Accent else Palette.Error
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Palette.avatarFor(peer.nodeId))
            .border(2.dp, ringColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

@Composable
private fun SignalBars(rssi: Int, isOnline: Boolean, modifier: Modifier = Modifier) {
    val level = when {
        !isOnline -> 0
        rssi == -200 || rssi == 0 -> 0
        rssi >= -55 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    val filledColor = when {
        !isOnline -> Color(0xFFBDBDBD)
        level >= 3 -> Palette.Accent
        level == 2 -> Palette.Warning
        else -> Palette.Error
    }
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            val h = (4 + i * 3).dp
            Box(
                modifier = Modifier.width(3.dp).height(h)
                    .background(if (i <= level) filledColor else Color(0xFFE0E0E0)),
            )
        }
    }
}

@Composable
private fun RenameDialog(peer: PeerInfo, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(peer.customName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename peer") },
        text = {
            Column {
                Text("${peer.nodeId.takeLast(8)}", fontSize = 12.sp, color = Palette.TextSecondary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("Nickname (leave blank to clear)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = Palette.TextSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 12.sp, color = Palette.TextMuted, textAlign = TextAlign.Center)
    }
}

/* ==========================================================================
 *  Chat detail
 * ========================================================================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDetailScreen(viewModel: DtnViewModel, chatKey: String) {
    val messages by viewModel.filteredMessages.collectAsState()
    val peers by viewModel.peerList.collectAsState()
    val isBroadcast = chatKey == DtnViewModel.BROADCAST_CHAT_ID
    val peer = peers.firstOrNull { it.nodeId == chatKey }

    val title = when {
        isBroadcast -> "Broadcast to All"
        peer != null -> peer.displayName()
        else -> chatKey.takeLast(8)
    }
    val subtitle = when {
        isBroadcast -> "Fans out to every peer in range"
        peer?.isOnline == true -> "Online"
        peer != null -> "Offline · message will be buffered"
        else -> ""
    }

    Scaffold(
        containerColor = Palette.Surface,
        topBar = {
            Surface(
                color = Palette.Primary,
                shadowElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.openChat(null) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                            tint = Color.White)
                    }
                    if (isBroadcast) {
                        Box(
                            modifier = Modifier.size(38.dp).clip(CircleShape)
                                .background(Palette.Broadcast),
                            contentAlignment = Alignment.Center,
                        ) { Text("📢", fontSize = 18.sp) }
                    } else if (peer != null) {
                        PeerAvatar(peer = peer, size = 38.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp, maxLines = 1)
                        Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp,
                            maxLines = 1)
                    }
                    if (!isBroadcast && peer != null) {
                        SignalBars(rssi = peer.lastRssi, isOnline = peer.isOnline)
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
                reverseLayout = true,
            ) {
                items(messages.reversed(), key = { it.msgId }) { msg ->
                    MessageBubble(msg)
                    Spacer(Modifier.height(4.dp))
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            MessageComposer(onSend = { viewModel.sendTestMessage(it) })
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isOutgoing = msg.isOutgoing
    val align = if (isOutgoing) Alignment.End else Alignment.Start
    val bgColor = if (isOutgoing) Palette.BubbleOut else Palette.BubbleIn
    val shape = if (isOutgoing) {
        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 4.dp, bottomEnd = 14.dp)
    }
    val statusColor = when {
        msg.status.contains("delivered") -> Palette.Accent
        msg.status.contains("received") -> Palette.Accent
        msg.status.contains("buffered") -> Palette.Warning
        msg.status.contains("expired") || msg.status.contains("dropped") -> Palette.Error
        else -> Palette.TextMuted
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Surface(
            shape = shape,
            color = bgColor,
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp).padding(vertical = 2.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!isOutgoing) {
                    Text(msg.from.takeLast(8), fontSize = 10.sp, color = Palette.TextMuted,
                        fontWeight = FontWeight.SemiBold)
                }
                Text(msg.text, fontSize = 14.sp, color = Palette.TextPrimary)
                Row(modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(msg.time, fontSize = 9.sp, color = Palette.TextMuted)
                    Spacer(Modifier.width(6.dp))
                    Text(msg.status, fontSize = 9.sp, color = statusColor,
                        fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun MessageComposer(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(
        color = Palette.CardSurface,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message…", fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Palette.Surface,
                    unfocusedContainerColor = Palette.Surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick = {
                    if (text.isNotBlank()) { onSend(text); text = "" }
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Palette.Primary, contentColor = Color.White,
                ),
                modifier = Modifier.size(46.dp),
            ) { Icon(Icons.Default.Send, contentDescription = "Send") }
        }
    }
}

/* ==========================================================================
 *  Network tab — refresh, peers+P, routes, buffer
 * ========================================================================== */

@Composable
private fun NetworkTab(viewModel: DtnViewModel) {
    val peers by viewModel.peerList.collectAsState()
    val messages by viewModel.receivedMessages.collectAsState()
    val buffered by viewModel.bufferedMessages.collectAsState()
    val bufferedCount by viewModel.bufferedCount.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val routes by viewModel.messageRoutes.collectAsState()
    val activeStrategy by viewModel.activeStrategy.collectAsState()
    val pValues = viewModel.getProbabilities()
    val onlinePeers = peers.count { it.isOnline }
    val myId8 = localNodeId?.takeLast(8)
    // A resolved route reached its destination. Split by whether WE were that destination
    // (received) or we handed it onward to the final destination (delivered).
    val resolvedRoutes = routes.count { it.delivered }
    val receivedRoutes = routes.count { it.delivered && it.destination == myId8 }
    val deliveredRoutes = resolvedRoutes - receivedRoutes
    var selectedRoute by remember {
        mutableStateOf<com.dtn.mesh.service.MessageLifecycleLog.RouteRecord?>(null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item { Spacer(Modifier.height(10.dp)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("NETWORK OVERVIEW", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = Palette.Primary, letterSpacing = 1.sp)
                        Text("This node · ${localNodeId?.takeLast(8) ?: "—"}", fontSize = 11.sp,
                            color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
                    }
                    FilledTonalIconButton(
                        onClick = { viewModel.refreshPeerStatus() },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Palette.Primary.copy(alpha = 0.10f),
                            contentColor = Palette.Primary,
                        ),
                    ) { Icon(Icons.Default.Refresh, contentDescription = "Refresh peer status") }
                }
            }
            item {
                NetworkSummaryCard(
                    onlinePeers = onlinePeers,
                    totalPeers = peers.size,
                    bufferedCount = bufferedCount,
                    deliveredRoutes = deliveredRoutes,
                    receivedRoutes = receivedRoutes,
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
                StrategySwitchRow(
                    active = activeStrategy,
                    onSelect = { viewModel.setStrategy(it) },
                )
            }
            item {
                Spacer(Modifier.height(14.dp))
                AnalyticsSectionHeader("Peers", "${peers.size} known · $onlinePeers active")
            }
            if (peers.isEmpty()) {
                item { EmptyState("No peers seen yet", "Connect to start discovery") }
            } else {
                items(peers, key = { it.nodeId }) { peer -> NetworkPeerRow(peer, pValues[peer.nodeId]) }
            }

            item {
                Spacer(Modifier.height(14.dp))
                AnalyticsSectionHeader("Local route observations", "$resolvedRoutes resolved · ${routes.size - resolvedRoutes} in transit")
                Text("Observed at this phone — tap any route for message details.",
                    fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
            }
            if (routes.isEmpty()) {
                item { EmptyState("No route observations yet", "Send a message to start tracking") }
            } else {
                // distinctBy guards the LazyColumn against duplicate keys even if the data
                // layer ever emits two records with the same msgId (defence in depth).
                items(routes.distinctBy { it.msgId }, key = { it.msgId }) { r ->
                    RouteRow(r, peers, myId8) { selectedRoute = r }
                }
            }

            item {
                Spacer(Modifier.height(14.dp))
                AnalyticsSectionHeader("Buffered messages", "$bufferedCount awaiting a delivery opportunity")
            }
            if (buffered.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Palette.Accent.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("✓ No messages are waiting to be forwarded", modifier = Modifier.padding(12.dp),
                            fontSize = 12.sp, color = Palette.Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                items(buffered, key = { it.msgId }) { msg -> BufferRow(msg) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        selectedRoute?.let { route ->
            val chat = messages.firstOrNull { it.msgId.take(8) == route.msgId }
            // Resolve the full destination node id from the peer list (route stores only the
            // last 8 chars) or from the matched chat message, then ask the ViewModel for the
            // routing-math breakdown toward it.
            val fullDest = peers.firstOrNull { it.nodeId.takeLast(8) == route.destination }?.nodeId
                ?: chat?.toNodeId
            val explanation = fullDest?.let { viewModel.explainRoute(it) }
            RouteDetailDialog(
                route = route,
                chat = chat,
                peers = peers,
                myId8 = myId8,
                explanation = explanation,
                onDismiss = { selectedRoute = null },
            )
        }
    }
}

@Composable
private fun StrategySwitchRow(active: String, onSelect: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("ROUTING PROTOCOL", fontSize = 9.sp, color = Palette.TextMuted, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrategyChip("PROPHET", "Probabilistic", active == "PROPHET") { onSelect("PROPHET") }
                StrategyChip("MAXPROP", "Path-cost", active == "MAXPROP") { onSelect("MAXPROP") }
            }
        }
    }
}

@Composable
private fun RowScope.StrategyChip(name: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Palette.Primary else Palette.Surface
    val fg = if (selected) Color.White else Palette.TextSecondary
    Surface(
        modifier = Modifier.weight(1f).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = bg,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp)) {
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg)
            Text(subtitle, fontSize = 9.sp, color = if (selected) Color.White.copy(alpha = 0.8f) else Palette.TextMuted)
        }
    }
}

/** Tappable header that reveals/hides [content]. Local, transient expand state. */
@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Primary)
                if (subtitle != null) Text(subtitle, fontSize = 10.sp, color = Palette.TextMuted)
            }
            Text(if (expanded) "▾" else "▸", fontSize = 14.sp, color = Palette.Primary)
        }
        if (expanded) content()
    }
}

@Composable
private fun NetworkSummaryCard(
    onlinePeers: Int,
    totalPeers: Int,
    bufferedCount: Int,
    deliveredRoutes: Int,
    receivedRoutes: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.PrimaryDark),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SummaryMetric("ACTIVE", "$onlinePeers/$totalPeers", "peers", Color.White)
            SummaryMetric("BUFFER", "$bufferedCount", "waiting", if (bufferedCount == 0) Color.White else Color(0xFFFFE082))
            SummaryMetric("DELIVERED", "$deliveredRoutes", "relayed on", Color.White)
            SummaryMetric("RECEIVED", "$receivedRoutes", "for me", Color.White)
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, detail: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, color = Color.White.copy(alpha = 0.72f), letterSpacing = 0.8.sp)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        Text(detail, fontSize = 10.sp, color = Color.White.copy(alpha = 0.72f))
    }
}

@Composable
private fun AnalyticsSectionHeader(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.Primary,
            letterSpacing = 1.sp)
        Text(detail, fontSize = 10.sp, color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.Primary,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
    )
}

@Composable
private fun NetworkPeerRow(peer: PeerInfo, p: Double?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(peer = peer, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.displayName(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = Palette.TextPrimary)
                Text(
                    "${peer.nodeId.takeLast(8)} · RSSI ${if (peer.lastRssi == -200) "—" else peer.lastRssi}",
                    fontSize = 11.sp, color = Palette.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    if (peer.isOnline) "active now" else "last seen ${relativeSince(peer.lastSeenMs)}",
                    fontSize = 10.sp,
                    color = if (peer.isOnline) Palette.Accent else Palette.TextMuted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (peer.isOnline) "ACTIVE" else "INACTIVE", fontSize = 10.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (peer.isOnline)
                            Palette.Accent.copy(alpha = 0.15f)
                        else Palette.Error.copy(alpha = 0.15f),
                        labelColor = if (peer.isOnline) Palette.Accent else Palette.Error,
                    ),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "P = ${p?.let { "%.2f".format(it) } ?: "—"}   ${estimateDistStr(peer.lastRssi)}",
                    fontSize = 10.sp, color = Palette.TextMuted,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun RouteRow(
    r: com.dtn.mesh.service.MessageLifecycleLog.RouteRecord,
    peers: List<PeerInfo>,
    myId8: String?,
    onClick: () -> Unit,
) {
    val statusColor = if (r.delivered) Palette.Accent else Palette.Warning
    val statusText = if (r.delivered) "RESOLVED" else "IN TRANSIT"
    val fromLabel = peerLabelFor(r.source, peers, myId8)
    val toLabel = peerLabelFor(r.destination, peers, myId8)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Colored status rail — instant delivered/in-transit read.
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(statusColor))
            Column(modifier = Modifier.weight(1f).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MSG ${r.msgId}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, color = Palette.TextPrimary)
                    Spacer(Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(8.dp), color = statusColor.copy(alpha = 0.15f)) {
                        Text(statusText, fontSize = 9.sp, color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Highlight the human-readable endpoints first, node ids second.
                Text(
                    "${fromLabel ?: r.source}  →  ${toLabel ?: r.destination}",
                    fontSize = 13.sp, color = Palette.TextPrimary, fontWeight = FontWeight.SemiBold,
                )
                Text("${r.source} → ${r.destination}", fontSize = 9.sp,
                    color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
                Text("OBSERVED PATH", fontSize = 8.sp, color = Palette.TextMuted,
                    letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 6.dp))
                Text(pathWithYou(r.asPath(), myId8), fontSize = 12.sp, color = Palette.Primary,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                Text("Tap for details", fontSize = 9.sp, color = Palette.Primary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * Map a truncated (last-8) node id — as stored on route/lifecycle records — back to a
 * peer's saved nickname / long name for display. Returns null when there's no match so
 * callers can fall back to the raw id.
 */
private fun peerLabelFor(idSuffix: String?, peers: List<PeerInfo>, myId8: String? = null): String? {
    if (idSuffix.isNullOrBlank()) return null
    if (idSuffix == "BROADCAST") return "Broadcast"
    if (myId8 != null && idSuffix == myId8) return "YOU"
    val match = peers.firstOrNull { it.nodeId.takeLast(8) == idSuffix } ?: return null
    val label = match.displayName()
    // displayName() falls back to the last-8 id; if that's all we have it isn't a real
    // nickname, so treat it as "no friendly name".
    return if (label == idSuffix) null else label
}

/** Replace the local node's id in an observed-path string with "YOU" for readability. */
private fun pathWithYou(path: String, myId8: String?): String =
    if (myId8.isNullOrBlank()) path else path.replace(myId8, "YOU")

/** Compact relative "time since" label for a last-seen timestamp. */
private fun relativeSince(ms: Long): String {
    if (ms <= 0L) return "never"
    val delta = System.currentTimeMillis() - ms
    return when {
        delta < 10_000L -> "just now"
        delta < 60_000L -> "${delta / 1000}s ago"
        delta < 3_600_000L -> "${delta / 60_000}m ago"
        delta < 86_400_000L -> "${delta / 3_600_000}h ago"
        else -> "${delta / 86_400_000}d ago"
    }
}

@Composable
private fun RouteDetailDialog(
    route: com.dtn.mesh.service.MessageLifecycleLog.RouteRecord,
    chat: ChatMessage?,
    peers: List<PeerInfo>,
    myId8: String?,
    explanation: RoutingExplanation?,
    onDismiss: () -> Unit,
) {
    val fromLabel = peerLabelFor(route.source, peers, myId8)
    val toLabel = peerLabelFor(route.destination, peers, myId8)
    val prevLabel = peerLabelFor(route.prevHop, peers, myId8)
    val nextLabel = peerLabelFor(route.nextHop, peers, myId8)
    val time = java.text.SimpleDateFormat("MMM d, HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(route.timestampMs))
    val statusColor = if (route.delivered) Palette.Accent else Palette.Warning

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Column {
                Text("Message details", fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    color = Palette.TextPrimary)
                Text("MSG ${route.msgId}", fontSize = 11.sp, color = Palette.TextMuted,
                    fontFamily = FontFamily.Monospace)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Surface(shape = RoundedCornerShape(8.dp), color = statusColor.copy(alpha = 0.15f)) {
                    Text(if (route.delivered) "DELIVERED" else "IN TRANSIT",
                        fontSize = 10.sp, color = statusColor, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Spacer(Modifier.height(10.dp))

                DetailRow("Content", chat?.text ?: "Not available on this device")
                DetailRow("From", labelWithId(fromLabel, route.source))
                DetailRow("To", labelWithId(toLabel, route.destination))
                if (!route.prevHop.isNullOrBlank())
                    DetailRow("Received from (prev hop)", labelWithId(prevLabel, route.prevHop))
                DetailRow("This node", if (route.me == myId8) "YOU (${route.me})" else route.me)
                if (!route.nextHop.isNullOrBlank())
                    DetailRow("Forwarded to (next hop)", labelWithId(nextLabel, route.nextHop))
                DetailRow("Observed path", pathWithYou(route.asPath(), myId8), mono = true)
                if (route.strategy.isNotBlank()) DetailRow("Routed by", route.strategy)
                DetailRow("Last update", time)
                if (chat != null) {
                    DetailRow("Direction", if (chat.isOutgoing) "Outgoing" else "Incoming")
                    DetailRow("Chat status", chat.status)
                }

                if (explanation != null) {
                    HorizontalDivider(color = Palette.Divider, modifier = Modifier.padding(vertical = 6.dp))
                    RoutingMathSection(route, explanation)
                }
            }
        },
    )
}

/**
 * Expandable probability-math breakdown for how the active strategy scores routes toward the
 * message's destination. Shows my own belief, each candidate carrier's belief, the 2-hop path
 * likelihood, and the transitive gain — annotating the previous/next hop on this route.
 */
@Composable
private fun RoutingMathSection(
    route: com.dtn.mesh.service.MessageLifecycleLog.RouteRecord,
    exp: RoutingExplanation,
) {
    ExpandableSection(
        title = "Routing math · ${exp.strategy}",
        subtitle = "How the path toward ${route.destination} is scored — tap to expand",
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            // ── Headline scores in two highlighted stat tiles ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = exp.myScoreLabel.uppercase(),
                    value = "%.3f".format(exp.myScore),
                    tint = Palette.Primary,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = "BEST NEXT HOP",
                    value = exp.bestNextHop?.let { exp.bestNextHopName ?: it.takeLast(8) } ?: "none",
                    subValue = exp.bestNextHop?.let { "${exp.bestScoreLabel} ${"%.3f".format(exp.bestScore)}" },
                    tint = if (exp.bestNextHop != null) Palette.Accent else Palette.TextMuted,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("CANDIDATE CARRIERS", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = Palette.TextSecondary, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(6.dp))
            if (exp.beliefs.isEmpty()) {
                Text("No candidate carriers known yet.", fontSize = 12.sp, color = Palette.TextMuted,
                    modifier = Modifier.padding(vertical = 6.dp))
            } else {
                exp.beliefs.take(8).forEachIndexed { i, b ->
                    val role = when (b.nodeId.takeLast(8)) {
                        route.prevHop -> "prev hop"
                        route.nextHop -> "next hop"
                        else -> null
                    }
                    CarrierBeliefRow(b, role, exp.bestScoreLabel, isBest = i == 0 && b.score > 0.0)
                }
            }

            Spacer(Modifier.height(14.dp))
            ExpandableSection(title = "Formulas & constants") {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    exp.formulas.forEach { FormulaLine(it.label, it.formula) }
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = Palette.Surface,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(exp.constantsLine, fontSize = 11.sp, color = Palette.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    subValue: String? = null,
    tint: Color,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.10f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(label, fontSize = 9.sp, color = Palette.TextMuted, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
            if (subValue != null) {
                Text(subValue, fontSize = 10.sp, color = Palette.TextSecondary,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun CarrierBeliefRow(
    b: RoutingNodeBelief,
    role: String?,
    scoreLabel: String,
    isBest: Boolean,
) {
    val dot = if (b.isOnline) Palette.Accent else Palette.Error
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (isBest) Palette.Accent.copy(alpha = 0.08f) else Palette.Surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(b.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = Palette.TextPrimary)
                if (role != null) {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = Palette.Primary.copy(alpha = 0.12f)) {
                        Text(role, fontSize = 8.sp, color = Palette.Primary, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("$scoreLabel ${"%.3f".format(b.score)}", fontSize = 12.sp, color = Palette.Primary,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(b.detail, fontSize = 10.sp, color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun FormulaLine(label: String, formula: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(label, fontSize = 10.sp, color = Palette.TextSecondary, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(formula, fontSize = 12.sp, color = Palette.TextPrimary, fontFamily = FontFamily.Monospace)
    }
}

/** Prefer the friendly label, then append the raw id in parentheses for traceability. */
private fun labelWithId(label: String?, id: String?): String = when {
    id.isNullOrBlank() -> "—"
    label == null -> id
    else -> "$label ($id)"
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(label.uppercase(), fontSize = 9.sp, color = Palette.TextMuted, letterSpacing = 0.6.sp)
        Text(
            value,
            fontSize = 13.sp,
            color = Palette.TextPrimary,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun BufferRow(msg: BufferedMsgInfo) {
    val urgencyColor = when {
        msg.ttlMin <= 5L -> Palette.Error
        msg.ttlMin <= 30L -> Palette.Warning
        else -> Palette.Primary
    }
    val urgencyText = when {
        msg.ttlMin <= 5L -> "EXPIRING SOON"
        msg.ttlMin <= 30L -> "TIME SENSITIVE"
        else -> "QUEUED"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = urgencyColor.copy(alpha = 0.06f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MSG ${msg.msgId}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, color = Palette.TextPrimary)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(8.dp), color = urgencyColor.copy(alpha = 0.16f)) {
                    Text(urgencyText, fontSize = 9.sp, color = urgencyColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("${msg.origin}  →  ${msg.dest}", fontSize = 10.sp, color = Palette.TextSecondary,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BufferMetric("TTL", "${msg.ttlMin}m", urgencyColor)
                BufferMetric("AGE", "${msg.bufferedFor}s", Palette.TextSecondary)
                BufferMetric("HOPS", "${msg.hopCount}", Palette.TextSecondary)
                BufferMetric("FORWARDS", "${msg.forwardCount}", Palette.TextSecondary)
            }
        }
    }
}

@Composable
private fun BufferMetric(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = 8.sp, color = Palette.TextMuted, letterSpacing = 0.5.sp)
        Text(value, fontSize = 11.sp, color = color, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold)
    }
}

private fun estimateDistStr(rssi: Int): String {
    if (rssi == -200 || rssi == 0) return ""
    val d = Math.pow(10.0, ((-59.0) - rssi.coerceIn(-120, -20).toDouble()) / 25.0)
    return "· %.1fm".format(d.coerceIn(0.5, 500.0))
}

/* ==========================================================================
 *  Lifecycle tab — color-coded state feed
 * ========================================================================== */

private enum class LifecycleFilter(val label: String) {
    ALL("All"), FLOW("Flow"), DELIVERY("Delivery"), ACK("ACK"), ISSUES("Issues"),
}

@Composable
private fun LifecycleFilterChip(
    option: LifecycleFilter,
    selectedFilter: LifecycleFilter,
    onClick: () -> Unit,
) {
    val selected = selectedFilter == option
    AssistChip(
        onClick = onClick,
        label = { Text(option.label, fontSize = 10.sp) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) Palette.Primary.copy(alpha = 0.14f) else Palette.CardSurface,
            labelColor = if (selected) Palette.Primary else Palette.TextSecondary,
        ),
    )
}

@Composable
private fun LifecycleTab(viewModel: DtnViewModel) {
    val entries by viewModel.lifecycleEntries.collectAsState()
    var filter by remember { mutableStateOf(LifecycleFilter.ALL) }
    val visibleEntries = entries.filter { entry ->
        when (filter) {
            LifecycleFilter.ALL -> true
            LifecycleFilter.FLOW -> entry.event in setOf(
                com.dtn.mesh.service.LifecycleEvent.CREATED,
                com.dtn.mesh.service.LifecycleEvent.BUFFERED,
                com.dtn.mesh.service.LifecycleEvent.FORWARDED,
                com.dtn.mesh.service.LifecycleEvent.BUFFER_CLEARED,
            )
            LifecycleFilter.DELIVERY -> entry.event == com.dtn.mesh.service.LifecycleEvent.DELIVERED
            LifecycleFilter.ACK -> entry.event in setOf(
                com.dtn.mesh.service.LifecycleEvent.ACK_GENERATED,
                com.dtn.mesh.service.LifecycleEvent.ACK_RECEIVED,
            )
            LifecycleFilter.ISSUES -> entry.event in setOf(
                com.dtn.mesh.service.LifecycleEvent.DUPLICATE,
                com.dtn.mesh.service.LifecycleEvent.IGNORED,
                com.dtn.mesh.service.LifecycleEvent.EXPIRED,
                com.dtn.mesh.service.LifecycleEvent.HOP_LIMIT,
                com.dtn.mesh.service.LifecycleEvent.DROPPED,
            )
        }
    }
    val bufferHistory = deriveBufferHistory(entries)

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // ── Buffer history: store-and-forward outcomes ──
        item {
            Spacer(Modifier.height(10.dp))
            AnalyticsSectionHeader("Buffer history", "${bufferHistory.size} store-and-forward records")
            Text("Messages that entered the buffer and how each one left it.",
                fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (bufferHistory.isEmpty()) {
            item { EmptyState("No buffered messages yet", "Carried messages will be tracked here") }
        } else {
            items(bufferHistory, key = { "buf-${it.msgId}" }) { BufferHistoryRow(it) }
        }

        // ── Live event log with filters ──
        item {
            Spacer(Modifier.height(16.dp))
            AnalyticsSectionHeader("Event log", "${entries.size} recent events")
            Text("Filter the live event stream to follow progress or investigate exceptions.",
                fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    LifecycleFilter.values().take(3).forEach { option -> LifecycleFilterChip(option, filter) { filter = option } }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    LifecycleFilter.values().drop(3).forEach { option -> LifecycleFilterChip(option, filter) { filter = option } }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (visibleEntries.isEmpty()) {
            item {
                EmptyState(if (entries.isEmpty()) "No events yet" else "No matching events",
                    "Send a message to see lifecycle tracking")
            }
        } else {
            // Index in the key guarantees uniqueness even if two events share the same
            // timestamp/msg/event triple, which the string-only key could otherwise collide on.
            itemsIndexed(
                visibleEntries,
                key = { i, e -> "$i-${e.timestamp}-${e.msgId}-${e.event}" },
            ) { _, e -> LifecycleEntryRow(e) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** One message's journey through the buffer, reconstructed from the lifecycle event feed. */
private data class BufferHistoryItem(
    val msgId: String,
    val origin: String,
    val dest: String,
    val bufferedAt: String,
    val clearedAt: String?,
    val clearEvent: com.dtn.mesh.service.LifecycleEvent?,
    val note: String,
)

/**
 * Reconstruct per-message buffer history from the (newest-first) lifecycle feed. A message
 * qualifies once it has a BUFFERED event; its disposition is the most recent terminal event
 * that removes it from the buffer. FORWARDED is intentionally NOT terminal — relaying to a
 * mule does not clear our buffer, only a receipt / direct delivery / expiry does.
 */
private fun deriveBufferHistory(
    entries: List<com.dtn.mesh.service.LifecycleEntry>,
): List<BufferHistoryItem> {
    val clearing = setOf(
        com.dtn.mesh.service.LifecycleEvent.DELIVERED,
        com.dtn.mesh.service.LifecycleEvent.BUFFER_CLEARED,
        com.dtn.mesh.service.LifecycleEvent.EXPIRED,
        com.dtn.mesh.service.LifecycleEvent.HOP_LIMIT,
        com.dtn.mesh.service.LifecycleEvent.DROPPED,
    )
    val byId = LinkedHashMap<String, MutableList<com.dtn.mesh.service.LifecycleEntry>>()
    for (e in entries) byId.getOrPut(e.msgId) { mutableListOf() }.add(e)
    val out = mutableListOf<BufferHistoryItem>()
    for ((id, list) in byId) {
        // list is newest-first: lastOrNull match = earliest BUFFERED, firstOrNull = latest clear.
        val buffered = list.lastOrNull { it.event == com.dtn.mesh.service.LifecycleEvent.BUFFERED } ?: continue
        val cleared = list.firstOrNull { it.event in clearing }
        out.add(BufferHistoryItem(
            msgId = id,
            origin = buffered.origin,
            dest = buffered.dest,
            bufferedAt = buffered.timestamp,
            clearedAt = cleared?.timestamp,
            clearEvent = cleared?.event,
            note = cleared?.extra.orEmpty(),
        ))
    }
    return out
}

@Composable
private fun BufferHistoryRow(item: BufferHistoryItem) {
    val (accent, disposition) = when (item.clearEvent) {
        com.dtn.mesh.service.LifecycleEvent.DELIVERED -> Palette.Accent to "Delivered to destination"
        com.dtn.mesh.service.LifecycleEvent.BUFFER_CLEARED -> Palette.Accent to "Cleared by delivery receipt"
        com.dtn.mesh.service.LifecycleEvent.EXPIRED -> Palette.Error to "Expired before delivery"
        com.dtn.mesh.service.LifecycleEvent.HOP_LIMIT -> Palette.Error to "Dropped — hop limit"
        com.dtn.mesh.service.LifecycleEvent.DROPPED -> Palette.Error to "Dropped — low reachability"
        else -> Palette.Warning to "Still buffered"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(accent))
            Column(modifier = Modifier.weight(1f).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MSG ${item.msgId}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, color = Palette.TextPrimary)
                    Spacer(Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = 0.15f)) {
                        Text(disposition, fontSize = 9.sp, color = accent, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                }
                if (item.origin.isNotEmpty() || item.dest.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("${item.origin.ifEmpty { "—" }}  →  ${item.dest.ifEmpty { "—" }}",
                        fontSize = 10.sp, color = Palette.TextSecondary, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BufferMetric("BUFFERED", item.bufferedAt, Palette.TextSecondary)
                    BufferMetric("CLEARED", item.clearedAt ?: "—", accent)
                }
                if (item.note.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(item.note, fontSize = 10.sp, color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun LifecycleEntryRow(entry: com.dtn.mesh.service.LifecycleEntry) {
    val eventColor = lifecycleColor(entry.event)
    var expanded by remember(entry) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(eventColor))
            Column(modifier = Modifier.weight(1f).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lifecycleLabel(entry.event), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = eventColor)
                    Spacer(Modifier.weight(1f))
                    Text(entry.timestamp, fontSize = 10.sp, color = Palette.TextMuted,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(6.dp))
                    Text(if (expanded) "▾" else "▸", fontSize = 12.sp, color = Palette.TextMuted)
                }
                Text(buildString {
                    append("MSG ${entry.msgId}")
                    if (entry.origin.isNotEmpty()) append(" · FROM ${entry.origin}")
                    if (entry.dest.isNotEmpty()) append(" · TO ${entry.dest}")
                }, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Palette.TextSecondary)

                if (!expanded) {
                    // Collapsed: compact one-liner of the key metrics.
                    val metrics = buildString {
                        if (entry.hopCount >= 0) append("Hops ${entry.hopCount}")
                        if (entry.forwardCount >= 0) append(if (isNotEmpty()) " · " else "").append("Fwd ${entry.forwardCount}")
                        if (entry.ttlRemainingMin >= 0) append(if (isNotEmpty()) " · " else "").append("TTL ${entry.ttlRemainingMin}m")
                    }
                    if (metrics.isNotEmpty()) Text(metrics, fontSize = 10.sp, color = Palette.TextMuted,
                        fontFamily = FontFamily.Monospace, maxLines = 1)
                } else {
                    // Expanded: full labeled breakdown of every populated field.
                    Spacer(Modifier.height(6.dp))
                    LifecycleDetail("Event", lifecycleLabel(entry.event))
                    LifecycleDetail("Time", entry.timestamp)
                    LifecycleDetail("Message", entry.msgId)
                    if (entry.origin.isNotEmpty()) LifecycleDetail("From", entry.origin)
                    if (entry.dest.isNotEmpty()) LifecycleDetail("To", entry.dest)
                    if (entry.hopCount >= 0) LifecycleDetail("Hop count", entry.hopCount.toString())
                    if (entry.forwardCount >= 0) LifecycleDetail("Forward count", entry.forwardCount.toString())
                    if (entry.ttlRemainingMin >= 0) LifecycleDetail("TTL remaining", "${entry.ttlRemainingMin} min")
                    if (entry.extra.isNotEmpty()) LifecycleDetail("Detail", entry.extra)
                }
            }
        }
    }
}

@Composable
private fun LifecycleDetail(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = 10.sp, color = Palette.TextMuted, modifier = Modifier.width(96.dp))
        Text(value, fontSize = 10.sp, color = Palette.TextSecondary, fontFamily = FontFamily.Monospace)
    }
}

private fun lifecycleColor(event: com.dtn.mesh.service.LifecycleEvent): Color = when (event) {
    com.dtn.mesh.service.LifecycleEvent.CREATED -> Palette.Primary
    com.dtn.mesh.service.LifecycleEvent.BUFFERED, com.dtn.mesh.service.LifecycleEvent.DUPLICATE -> Palette.Warning
    com.dtn.mesh.service.LifecycleEvent.FORWARDED, com.dtn.mesh.service.LifecycleEvent.DELIVERED,
    com.dtn.mesh.service.LifecycleEvent.BUFFER_CLEARED -> Palette.Accent
    com.dtn.mesh.service.LifecycleEvent.ACK_GENERATED, com.dtn.mesh.service.LifecycleEvent.ACK_RECEIVED -> Palette.Broadcast
    com.dtn.mesh.service.LifecycleEvent.IGNORED -> Palette.TextMuted
    com.dtn.mesh.service.LifecycleEvent.EXPIRED, com.dtn.mesh.service.LifecycleEvent.HOP_LIMIT,
    com.dtn.mesh.service.LifecycleEvent.DROPPED -> Palette.Error
}

private fun lifecycleLabel(event: com.dtn.mesh.service.LifecycleEvent): String = when (event) {
    com.dtn.mesh.service.LifecycleEvent.CREATED -> "Created"
    com.dtn.mesh.service.LifecycleEvent.BUFFERED -> "Buffered"
    com.dtn.mesh.service.LifecycleEvent.DUPLICATE -> "Duplicate received"
    com.dtn.mesh.service.LifecycleEvent.FORWARDED -> "Forwarded"
    com.dtn.mesh.service.LifecycleEvent.IGNORED -> "Ignored"
    com.dtn.mesh.service.LifecycleEvent.ACK_GENERATED -> "Acknowledgement generated"
    com.dtn.mesh.service.LifecycleEvent.ACK_RECEIVED -> "Acknowledgement received"
    com.dtn.mesh.service.LifecycleEvent.BUFFER_CLEARED -> "Buffer cleared"
    com.dtn.mesh.service.LifecycleEvent.DELIVERED -> "Delivered"
    com.dtn.mesh.service.LifecycleEvent.EXPIRED -> "Expired"
    com.dtn.mesh.service.LifecycleEvent.HOP_LIMIT -> "Hop limit reached"
    com.dtn.mesh.service.LifecycleEvent.DROPPED -> "Dropped (housekeeping)"
}

/* ==========================================================================
 *  Log tab
 * ========================================================================== */

@Composable
private fun LogTab(logEntries: List<String>) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        SectionTitle("Console")
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logEntries) { entry ->
                Text(entry, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = Palette.TextPrimary,
                    modifier = Modifier.padding(vertical = 1.dp))
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
