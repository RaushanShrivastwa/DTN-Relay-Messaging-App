package com.dtn.mesh.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.receiver.MeshConnectionState

@Composable
fun MainScreen(viewModel: DtnViewModel) {
    val selectedChat by viewModel.selectedChat.collectAsState()

    // Two-screen navigation:
    // - null → chat list (Home)
    // - non-null → chat detail (peer or broadcast)
    if (selectedChat == null) {
        HomeScreen(viewModel)
    } else {
        ChatDetailScreen(viewModel, chatKey = selectedChat!!)
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Home screen: connection card, controls, chat list, log tab.
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun HomeScreen(viewModel: DtnViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val bufferedCount by viewModel.bufferedCount.collectAsState()
    val activeStrategy by viewModel.activeStrategy.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            Text("DTN Mesh Relay", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            ConnectionCard(connectionState, localNodeId, activeStrategy, bufferedCount)
            Spacer(Modifier.height(8.dp))
            ControlRow(connectionState, viewModel)
            Spacer(Modifier.height(8.dp))

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("Chats", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("Log", modifier = Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.height(8.dp))

            // Weighted box gives the tab body bounded height so its LazyColumn measures correctly.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    0 -> ChatListTab(viewModel)
                    1 -> LogTab(logEntries)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Chat list — WhatsApp-style contact cards.
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ChatListTab(viewModel: DtnViewModel) {
    val peers by viewModel.peerList.collectAsState()
    val messages by viewModel.receivedMessages.collectAsState()

    // Peer currently being renamed (drives the AlertDialog).
    var renameTarget by remember { mutableStateOf<PeerInfo?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Always-present broadcast entry at the top.
        item {
            ChatListCard(
                title = "Broadcast to All",
                subtitle = lastPreview(messages, chatKey = null) ?: "Send to every peer in range",
                statusColor = Color(0xFF2196F3),
                showEditIcon = false,
                onClick = { viewModel.openChat(DtnViewModel.BROADCAST_CHAT_ID) },
                onEdit = {},
            )
        }
        if (peers.isEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                Text("No peers seen yet. Connect and wait for discovery.",
                    fontSize = 12.sp, color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 8.dp))
            }
        } else {
            items(peers) { peer ->
                ChatListCard(
                    title = peer.displayName(),
                    subtitle = lastPreview(messages, chatKey = peer.nodeId) ?: peer.nodeId.takeLast(8),
                    statusColor = if (peer.isOnline) Color(0xFF2E7D32) else Color(0xFFC62828),
                    showEditIcon = true,
                    rssi = peer.lastRssi,
                    isOnline = peer.isOnline,
                    onClick = { viewModel.openChat(peer.nodeId) },
                    onEdit = { renameTarget = peer },
                )
            }
        }
    }

    renameTarget?.let { peer ->
        RenameDialog(
            peer = peer,
            onDismiss = { renameTarget = null },
            onSave = { newName ->
                viewModel.renamePeer(peer.nodeId, newName)
                renameTarget = null
            },
        )
    }
}

/** Preview text shown as the second line of a chat card — the most recent message in that chat. */
private fun lastPreview(all: List<ChatMessage>, chatKey: String?): String? {
    val filtered = when (chatKey) {
        null -> all.filter { it.toNodeId == null }        // broadcast card
        else -> all.filter { it.toNodeId == chatKey }     // 1:1: no broadcasts leaked in
    }
    val last = filtered.lastOrNull() ?: return null
    val prefix = if (last.isOutgoing) "You: " else ""
    return prefix + last.text.take(48)
}

@Composable
private fun ChatListCard(
    title: String,
    subtitle: String,
    statusColor: Color,
    showEditIcon: Boolean,
    rssi: Int? = null,
    isOnline: Boolean = false,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                Text(subtitle, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
            }
            if (rssi != null) {
                SignalBars(rssi = rssi, isOnline = isOnline)
                Spacer(Modifier.size(6.dp))
            }
            if (showEditIcon) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * WiFi-style signal indicator: four ascending bars filled based on RSSI (dBm).
 *
 * BLE RSSI thresholds (empirical, forgiving):
 *   ≥ -55  → 4 bars (excellent)
 *   ≥ -70  → 3 bars (good)
 *   ≥ -80  → 2 bars (fair)
 *   ≥ -90  → 1 bar  (weak)
 *   else   → 0 bars (very weak / unknown)
 * Offline peers show all bars greyed out.
 */
@Composable
private fun SignalBars(rssi: Int, isOnline: Boolean, modifier: Modifier = Modifier) {
    val level = when {
        !isOnline -> 0
        rssi == -200 -> 0            // sentinel = never measured
        rssi >= -55 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    val filledColor = when {
        !isOnline -> Color(0xFFBDBDBD)
        level >= 3 -> Color(0xFF2E7D32) // green
        level == 2 -> Color(0xFFF9A825) // amber
        else -> Color(0xFFC62828)       // red
    }
    val emptyColor = Color(0xFFE0E0E0)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 1..4) {
            val h = (4 + i * 3).dp // 7, 10, 13, 16
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .background(if (i <= level) filledColor else emptyColor),
            )
        }
    }
}

@Composable
private fun RenameDialog(
    peer: PeerInfo,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(peer.customName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${peer.nodeId.takeLast(8)}") },
        text = {
            Column {
                Text("Give this node a nickname. Leave blank to clear.",
                    fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("Nickname") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ─────────────────────────────────────────────────────────────────────────
// Chat detail — single-peer (or broadcast) message view.
// ─────────────────────────────────────────────────────────────────────────

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
    val statusText = when {
        isBroadcast -> "Sends to every peer in range"
        peer?.isOnline == true -> "Online"
        peer != null -> "Offline (message will be buffered)"
        else -> ""
    }
    val statusColor = when {
        isBroadcast -> Color(0xFF2196F3)
        peer?.isOnline == true -> Color(0xFF2E7D32)
        else -> Color(0xFFC62828)
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Header
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(0.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.openChat(null) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.size(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                        Text(statusText, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                    }
                    if (!isBroadcast && peer != null) {
                        SignalBars(rssi = peer.lastRssi, isOnline = peer.isOnline)
                        Spacer(Modifier.size(8.dp))
                    }
                }
            }

            // Messages
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                reverseLayout = true,
            ) {
                items(messages.reversed()) { msg ->
                    MessageBubble(msg)
                    Spacer(Modifier.height(4.dp))
                }
            }

            // Composer
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var text by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message...") },
                    singleLine = true,
                )
                Button(onClick = {
                    if (text.isNotBlank()) {
                        viewModel.sendTestMessage(text)
                        text = ""
                    }
                }) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isOutgoing = msg.isOutgoing
    val align = if (isOutgoing) Alignment.End else Alignment.Start
    val bgColor = if (isOutgoing) Color(0xFFDCF8C6) else Color(0xFFE8E8E8)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 280.dp)) {
            Column(modifier = Modifier.background(bgColor).padding(8.dp)) {
                if (!isOutgoing) {
                    Text(msg.from.takeLast(8), fontSize = 10.sp, color = Color.Gray)
                }
                Text(msg.text, fontSize = 14.sp)
                Text(msg.status + " • " + msg.time, fontSize = 9.sp, color = Color.Gray)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Shared components (unchanged).
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun LogTab(logEntries: List<String>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(logEntries) { entry ->
            Text(entry, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 1.dp))
        }
    }
}

@Composable
private fun ConnectionCard(state: MeshConnectionState, nodeId: String?, strategy: String, buffered: Int) {
    val (statusColor, statusText) = when (state) {
        MeshConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Connected"
        MeshConnectionState.CONNECTING -> Color(0xFFFFC107) to "Connecting..."
        MeshConnectionState.DEVICE_SLEEP -> Color(0xFFFF9800) to "Device Sleep"
        MeshConnectionState.DISCONNECTED -> Color(0xFFF44336) to "Disconnected"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(statusColor))
                Text("  $statusText", fontWeight = FontWeight.SemiBold)
            }
            Text("Node: ${nodeId ?: "—"}  |  Strategy: $strategy  |  Buffer: $buffered", fontSize = 11.sp)
        }
    }
}

@Composable
private fun ControlRow(state: MeshConnectionState, viewModel: DtnViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state == MeshConnectionState.DISCONNECTED) {
                Button(onClick = { viewModel.connect() }, modifier = Modifier.weight(1f)) { Text("Connect") }
            } else {
                OutlinedButton(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                ) { Text("Disconnect") }
            }
            OutlinedButton(onClick = { viewModel.switchStrategy() }) { Text("Switch") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.syncBuffer() }, modifier = Modifier.weight(1f)) { Text("Sync") }
            OutlinedButton(onClick = { viewModel.clearBuffer() }, modifier = Modifier.weight(1f)) { Text("Clear") }
            OutlinedButton(onClick = { viewModel.exportData() }, modifier = Modifier.weight(1f)) { Text("Export") }
        }
    }
}
