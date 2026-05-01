package com.greenart7c3.nip46tester.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.greenart7c3.nip46tester.nip46.Nip46Bunker
import com.greenart7c3.nip46tester.nip46.NostrConnectUrl
import kotlinx.coroutines.launch

@Composable
fun BunkerScreen() {
    val scope = rememberCoroutineScope()
    var relaysCsv by remember { mutableStateOf("wss://nos.lol") }
    var bunkerPriv by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("test-secret") }
    var bunker by remember { mutableStateOf<Nip46Bunker?>(null) }
    var bunkerUrl by remember { mutableStateOf("") }
    var nostrConnectInput by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<String>() }

    fun appendLog(line: String) {
        log.add(line)
        if (log.size > 500) log.removeAt(0)
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Bunker (remote signer) tester", style = MaterialTheme.typography.titleMedium)
        Text(
            "Starts a minimal NIP-46 remote signer. It listens for kind 24133 events on the configured " +
                "relays, decrypts them, and replies. Use the printed bunker:// URL in any NIP-46 client, " +
                "or paste a nostrconnect:// URL below to initiate the client-initiated handshake.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = relaysCsv,
            onValueChange = { relaysCsv = it },
            label = { Text("Relays (comma-separated, wss://…)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = bunkerPriv,
            onValueChange = { bunkerPriv = it },
            label = { Text("Bunker private key (hex, blank = random)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("Secret (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(
            onClick = {
                bunker?.close()
                val relays = relaysCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val b = Nip46Bunker(
                    relayUrls = relays,
                    privateKeyHex = bunkerPriv.takeIf { it.isNotBlank() },
                    secret = secret.takeIf { it.isNotBlank() },
                    onLog = ::appendLog,
                )
                bunker = b
                bunkerUrl = b.bunkerUrl()
                appendLog("[bunker] pubkey ${b.pubKey}")
                appendLog("[bunker] privkey ${b.privKey}")
                appendLog("[bunker] url $bunkerUrl")
                b.start()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start bunker") }

        if (bunkerUrl.isNotEmpty()) {
            HorizontalDivider()
            Text("Bunker URL (paste into the Client tab)", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(
                    bunkerUrl,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }

        HorizontalDivider()
        Text("Connect to nostrconnect:// URL", style = MaterialTheme.typography.titleSmall)
        Text(
            "Paste a client-initiated URL. The bunker will join its relays (if missing), publish a " +
                "connect ack to the client, and then respond to subsequent requests as usual.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = nostrConnectInput,
            onValueChange = { nostrConnectInput = it },
            label = { Text("nostrconnect:// URL") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val b = bunker
                if (b == null) {
                    appendLog("[bunker] start the bunker first")
                    return@Button
                }
                val url = runCatching { NostrConnectUrl.parse(nostrConnectInput) }
                    .onFailure { appendLog("[bunker] parse error: ${it.message}") }
                    .getOrNull() ?: return@Button
                appendLog("[bunker] client pubkey ${url.clientPubKey}")
                appendLog("[bunker] client relays ${url.relays.joinToString()}")
                if (url.name != null) appendLog("[bunker] client app name: ${url.name}")
                if (url.perms != null) appendLog("[bunker] client perms: ${url.perms}")
                scope.launch { b.connectToClient(url) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send connect ack to client") }

        OutlinedButton(
            onClick = { bunker?.close().also { appendLog("[bunker] closed") } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop bunker") }

        HorizontalDivider()
        Text("Log", style = MaterialTheme.typography.titleSmall)
        LogPane(log, Modifier.fillMaxWidth())
    }
}
