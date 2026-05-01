package com.greenart7c3.nip46tester.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greenart7c3.nip46tester.nip46.Nip46RelayTester
import kotlinx.coroutines.launch

@Composable
fun RelayTestScreen() {
    val scope = rememberCoroutineScope()
    var relayUrl by remember { mutableStateOf("wss://relay.damus.io") }
    var running by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<String>() }

    fun appendLog(line: String) {
        log.add(line)
        if (log.size > 500) log.removeAt(0)
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Relay NIP-46 support test", style = MaterialTheme.typography.titleMedium)
        Text(
            "Probes a relay by publishing a real kind 24133 (NIP-46) event from one keypair " +
                "and listening for it on a second keypair. A relay supports NIP-46 if it accepts " +
                "the event (OK true) AND routes it to the matching subscription.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = relayUrl,
            onValueChange = { relayUrl = it },
            label = { Text("Relay URL (wss://...)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(
            onClick = {
                if (running) return@Button
                running = true
                verdict = ""
                log.clear()
                scope.launch {
                    val tester = Nip46RelayTester(relayUrl.trim(), ::appendLog)
                    val result = tester.run()
                    verdict = buildString {
                        append(if (result.accepted && result.routedToSubscriber) "✅ supports NIP-46" else "❌ does NOT support NIP-46")
                        append("\n  accepted=${result.accepted}")
                        append("\n  routed=${result.routedToSubscriber}")
                        append("\n  ok message: ${result.okMessage}")
                        if (result.notes.isNotEmpty()) {
                            append("\n  notes:")
                            result.notes.forEach { append("\n   - $it") }
                        }
                    }
                    appendLog("[verdict] ${verdict.lines().first()}")
                    running = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
        ) { Text(if (running) "Testing…" else "Run NIP-46 probe") }

        if (verdict.isNotBlank()) {
            HorizontalDivider()
            Text(verdict, style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider()
        Text("Wire log", style = MaterialTheme.typography.titleSmall)
        LogPane(log, Modifier.fillMaxWidth())
    }
}
