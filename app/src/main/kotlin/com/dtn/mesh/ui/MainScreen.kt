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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    val buffered by viewModel.bufferedMessages.collectAsState()
    val bufferedCount by viewModel.bufferedCount.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val routes by viewModel.messageRoutes.collectAsState()
    val pValues = viewModel.getProbabilities()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item { Spacer(Modifier.height(10.dp)) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    SectionTitle("Peers")
                    Text("Host ${localNodeId?.takeLast(8) ?: "—"}",
                        fontSize = 11.sp, color = Palette.TextMuted,
                        fontFamily = FontFamily.Monospace)
                }
                FilledTonalIconButton(
                    onClick = { viewModel.refreshPeerStatus() },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Palette.Primary.copy(alpha = 0.10f),
                        contentColor = Palette.Primary,
                    ),
                ) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
            }
        }
        if (peers.isEmpty()) {
            item { EmptyState("No peers seen yet", "Connect to start discovery") }
        } else {
            items(peers, key = { it.nodeId }) { peer ->
                NetworkPeerRow(peer, pValues[peer.nodeId])
            }
        }

        item {
            Spacer(Modifier.height(14.dp))
            SectionTitle("Message routes")
            Text("Each row is this node's view: source → previous hop → me → next hop",
                fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (routes.isEmpty()) {
            item { EmptyState("No routes yet", "Send a message to start tracking") }
        } else {
            items(routes, key = { it.msgId }) { r -> RouteRow(r) }
        }

        item {
            Spacer(Modifier.height(14.dp))
            SectionTitle("Buffer ($bufferedCount)")
        }
        if (buffered.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Palette.Accent.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "✓ Buffer empty",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 12.sp, color = Palette.Accent, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        } else {
            items(buffered, key = { it.msgId }) { msg -> BufferRow(msg) }
        }
        item { Spacer(Modifier.height(24.dp)) }
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
private fun RouteRow(r: com.dtn.mesh.service.MessageLifecycleLog.RouteRecord) {
    val statusColor = if (r.delivered) Palette.Accent else Palette.Warning
    val statusText = if (r.delivered) "delivered" else "in transit"
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("msg=${r.msgId}", fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    color = Palette.TextPrimary)
                Spacer(Modifier.width(6.dp))
                Text("${r.source} → ${r.destination}", fontSize = 11.sp,
                    color = Palette.TextSecondary, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.15f)) {
                    Text(statusText, fontSize = 9.sp, color = statusColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                r.asPath(),
                fontSize = 12.sp, color = Palette.Primary, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun BufferRow(msg: BufferedMsgInfo) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = Palette.Warning.copy(alpha = 0.06f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("msg=${msg.msgId}", fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    color = Palette.TextPrimary)
                Spacer(Modifier.width(6.dp))
                Text("${msg.origin} → ${msg.dest}", fontSize = 11.sp,
                    color = Palette.TextSecondary, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(8.dp),
                    color = Palette.Warning.copy(alpha = 0.2f)) {
                    Text(msg.status, fontSize = 9.sp, color = Palette.Warning,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "hop=${msg.hopCount} · fwd=${msg.forwardCount} · ttl=${msg.ttlMin}m · age=${msg.bufferedFor}s",
                fontSize = 10.sp, color = Palette.TextMuted, fontFamily = FontFamily.Monospace,
            )
        }
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

@Composable
private fun LifecycleTab(viewModel: DtnViewModel) {
    val entries by viewModel.lifecycleEntries.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(10.dp))
        SectionTitle("Message lifecycle")
        Text("Real-time state transitions for every message",
            fontSize = 11.sp, color = Palette.TextMuted,
            modifier = Modifier.padding(bottom = 8.dp))
        if (entries.isEmpty()) {
            EmptyState("No events yet", "Send a message to see lifecycle tracking")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries.size) { index -> LifecycleEntryRow(entries[index]) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LifecycleEntryRow(entry: com.dtn.mesh.service.LifecycleEntry) {
    val eventColor = when (entry.event) {
        com.dtn.mesh.service.LifecycleEvent.CREATED -> Palette.Primary
        com.dtn.mesh.service.LifecycleEvent.BUFFERED -> Palette.Warning
        com.dtn.mesh.service.LifecycleEvent.FORWARDED -> Palette.Accent
        com.dtn.mesh.service.LifecycleEvent.DELIVERED -> Palette.Accent
        com.dtn.mesh.service.LifecycleEvent.ACK_GENERATED -> Palette.Broadcast
        com.dtn.mesh.service.LifecycleEvent.ACK_RECEIVED -> Palette.Broadcast
        com.dtn.mesh.service.LifecycleEvent.BUFFER_CLEARED -> Palette.Accent
        com.dtn.mesh.service.LifecycleEvent.DUPLICATE -> Palette.Warning
        com.dtn.mesh.service.LifecycleEvent.IGNORED -> Palette.TextMuted
        com.dtn.mesh.service.LifecycleEvent.EXPIRED -> Palette.Error
        com.dtn.mesh.service.LifecycleEvent.HOP_LIMIT -> Palette.Error
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(4.dp).height(38.dp).background(eventColor),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.event.name, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = eventColor, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(6.dp))
                    Text(entry.timestamp, fontSize = 10.sp, color = Palette.TextMuted,
                        fontFamily = FontFamily.Monospace)
                }
                Text(
                    buildString {
                        append("msg=${entry.msgId}")
                        if (entry.origin.isNotEmpty()) append(" · from=${entry.origin}")
                        if (entry.dest.isNotEmpty()) append(" · to=${entry.dest}")
                        if (entry.hopCount >= 0) append(" · hop=${entry.hopCount}")
                        if (entry.ttlRemainingMin >= 0) append(" · ttl=${entry.ttlRemainingMin}m")
                    },
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = Palette.TextSecondary,
                )
                if (entry.extra.isNotEmpty()) {
                    Text(entry.extra, fontSize = 10.sp, color = Palette.TextMuted,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
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
