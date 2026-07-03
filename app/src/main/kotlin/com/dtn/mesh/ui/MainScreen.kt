package com.dtn.mesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
    val connectionState by viewModel.connectionState.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val bufferedCount by viewModel.bufferedCount.collectAsState()
    val activeStrategy by viewModel.activeStrategy.collectAsState()
    val messages by viewModel.receivedMessages.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            // ── Header + Status ──────────────────────────────────────
            Text("DTN Mesh Relay", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            ConnectionCard(connectionState, localNodeId, activeStrategy, bufferedCount)
            Spacer(Modifier.height(8.dp))

            // ── Controls ─────────────────────────────────────────────
            ControlRow(connectionState, viewModel)
            Spacer(Modifier.height(8.dp))

            // ── Tab selector ─────────────────────────────────────────
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("Messages", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("Log", modifier = Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.height(8.dp))

            when (selectedTab) {
                0 -> MessagesTab(viewModel, messages)
                1 -> LogTab(logEntries)
            }
        }
    }
}

// ── Messages Tab (chat-like) ─────────────────────────────────────────────

@Composable
private fun MessagesTab(viewModel: DtnViewModel, messages: List<ChatMessage>) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Destination selector
        DestinationSelector(viewModel)
        Spacer(Modifier.height(8.dp))

        // Message list
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
        ) {
            items(messages.reversed()) { msg ->
                MessageBubble(msg)
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        SendMessageRow(onSend = { viewModel.sendTestMessage(it) })
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isOutgoing = msg.isOutgoing
    val align = if (isOutgoing) Alignment.End else Alignment.Start
    val bgColor = if (isOutgoing) Color(0xFFDCF8C6) else Color(0xFFE8E8E8)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Column(modifier = Modifier.background(bgColor).padding(8.dp)) {
                if (!isOutgoing) {
                    Text(msg.from.takeLast(8), fontSize = 10.sp, color = Color.Gray)
                }
                Text(msg.text, fontSize = 14.sp)
                Text(
                    msg.status + " • " + msg.time,
                    fontSize = 9.sp, color = Color.Gray,
                )
            }
        }
    }
}

// ── Log Tab ──────────────────────────────────────────────────────────────

@Composable
private fun LogTab(logEntries: List<String>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(logEntries) { entry ->
            Text(entry, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
        }
    }
}

// ── Shared components ────────────────────────────────────────────────────

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
                Spacer(Modifier.size(12.dp).clip(CircleShape).background(statusColor))
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
                OutlinedButton(onClick = { viewModel.disconnect() }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)) { Text("Disconnect") }
            }
            OutlinedButton(onClick = { viewModel.switchStrategy() }) { Text("Switch") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.syncBuffer() }, modifier = Modifier.weight(1f)) { Text("Sync") }
            OutlinedButton(onClick = { viewModel.clearBuffer() }, modifier = Modifier.weight(1f)) { Text("Clear Buffer") }
            OutlinedButton(onClick = { viewModel.exportData() }, modifier = Modifier.weight(1f)) { Text("Export") }
        }
    }
}

@Composable
private fun DestinationSelector(viewModel: DtnViewModel) {
    val peers by viewModel.peerList.collectAsState()
    val selected by viewModel.selectedDestination.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // First row: broadcast + online peers (green)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { viewModel.selectDestination(null) },
                colors = if (selected == null) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
            ) { Text("All", fontSize = 11.sp) }

            for (peer in peers) {
                val isSelected = selected == peer.nodeId
                // Colors: online = green, offline = red, selected = solid highlight
                val bg = when {
                    isSelected -> ButtonDefaults.buttonColors()
                    peer.isOnline -> ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF2E7D32))
                    else -> ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
                }
                OutlinedButton(
                    onClick = { viewModel.selectDestination(peer.nodeId) },
                    colors = bg,
                ) {
                    val statusDot = if (peer.isOnline) "●" else "○"
                    Text("$statusDot ${peer.nodeId.takeLast(8)}", fontSize = 11.sp)
                }
            }
        }
        if (peers.isEmpty()) Text("No peers seen yet", fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable
private fun SendMessageRow(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = text, onValueChange = { text = it },
            modifier = Modifier.weight(1f), placeholder = { Text("Message...") }, singleLine = true)
        Button(onClick = { if (text.isNotBlank()) { onSend(text); text = "" } }) { Text("Send") }
    }
}
