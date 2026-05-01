package com.greenart7c3.nip46tester.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import com.greenart7c3.nip46tester.nip46.BunkerUrl
import com.greenart7c3.nip46tester.nip46.Nip46Client
import kotlinx.coroutines.launch
import kotlin.random.Random

private enum class ClientMode { Bunker, NostrConnect }

@Composable
fun ClientScreen() {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ClientMode.Bunker) }

    var bunkerInput by remember { mutableStateOf("") }
    var clientPriv by remember { mutableStateOf("") }
    var thirdPartyPub by remember { mutableStateOf("") }
    var encMsg by remember { mutableStateOf("hello from nip-46 tester") }

    var ncRelaysCsv by remember { mutableStateOf("wss://nos.lol") }
    var ncSecret by remember { mutableStateOf(randomSecret()) }
    var ncName by remember { mutableStateOf("NIP-46 Tester") }
    var ncPerms by remember { mutableStateOf("") }
    var ncUrl by remember { mutableStateOf("") }

    var customMethod by remember { mutableStateOf("") }
    var customParams by remember { mutableStateOf("") }

    val log = remember { mutableStateListOf<String>() }
    var client by remember { mutableStateOf<Nip46Client?>(null) }

    fun appendLog(line: String) {
        log.add(line)
        if (log.size > 500) log.removeAt(0)
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Client tester", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ClientMode.Bunker,
                onClick = { mode = ClientMode.Bunker },
                label = { Text("bunker://") },
            )
            FilterChip(
                selected = mode == ClientMode.NostrConnect,
                onClick = { mode = ClientMode.NostrConnect },
                label = { Text("nostrconnect://") },
            )
        }

        when (mode) {
            ClientMode.Bunker -> {
                Text(
                    "Paste a bunker:// URL. The client encrypts requests with NIP-44, publishes " +
                        "them as kind 24133 to the bunker's pubkey, and waits for replies.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = bunkerInput,
                    onValueChange = { bunkerInput = it },
                    label = { Text("bunker:// URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                )
                OutlinedTextField(
                    value = clientPriv,
                    onValueChange = { clientPriv = it },
                    label = { Text("Client private key (hex, optional - random if blank)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        val url = runCatching { BunkerUrl.parse(bunkerInput) }
                            .onFailure { appendLog("[client] parse error: ${it.message}") }
                            .getOrNull() ?: return@Button
                        client?.close()
                        val c = Nip46Client(url, clientPriv.takeIf { it.isNotBlank() }, ::appendLog)
                        client = c
                        appendLog("[client] pubkey ${c.clientPubKey}")
                        c.start()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect to bunker") }
            }

            ClientMode.NostrConnect -> {
                Text(
                    "Generate a nostrconnect:// URL, then share it with a remote signer. The " +
                        "client subscribes to its own pubkey on the listed relays and waits for the " +
                        "bunker to publish a connect ack. The bunker's pubkey is discovered from the " +
                        "first decryptable inbound event.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = ncRelaysCsv,
                    onValueChange = { ncRelaysCsv = it },
                    label = { Text("Relays (comma-separated, wss://…)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = clientPriv,
                    onValueChange = { clientPriv = it },
                    label = { Text("Client private key (hex, optional - random if blank)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = ncSecret,
                    onValueChange = { ncSecret = it },
                    label = { Text("Secret") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = ncName,
                    onValueChange = { ncName = it },
                    label = { Text("App name (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = ncPerms,
                    onValueChange = { ncPerms = it },
                    label = { Text("perms (optional, e.g. sign_event:1,nip44_encrypt)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Button(
                    onClick = {
                        val relays = ncRelaysCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        if (relays.isEmpty()) {
                            appendLog("[client] need at least one relay")
                            return@Button
                        }
                        client?.close()
                        val c = Nip46Client(
                            initialRelayUrls = relays,
                            initialRemotePubKey = null,
                            initialSecret = ncSecret.takeIf { it.isNotBlank() },
                            privateKeyHex = clientPriv.takeIf { it.isNotBlank() },
                            onLog = ::appendLog,
                        )
                        client = c
                        ncUrl = c.nostrConnectUrl(
                            secret = ncSecret,
                            name = ncName.takeIf { it.isNotBlank() },
                            perms = ncPerms.takeIf { it.isNotBlank() },
                        )
                        appendLog("[client] pubkey ${c.clientPubKey}")
                        appendLog("[client] nostrconnect URL ready — paste it into a bunker")
                        c.start()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Generate URL & start listening") }

                if (ncUrl.isNotBlank()) {
                    Text("Share this URL with a remote signer:", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(
                            ncUrl,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Methods", style = MaterialTheme.typography.titleSmall)
        Text(
            client?.bunkerPubKey?.let { "Bunker: ${it.take(16)}…" } ?: "Bunker not yet known",
            style = MaterialTheme.typography.bodySmall,
        )

        if (mode == ClientMode.Bunker) {
            OutlinedButton(
                onClick = { scope.launch { client?.connect()?.also { appendLog("[client] connect: $it") } } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("connect") }
        }
        OutlinedButton(
            onClick = { scope.launch { client?.ping()?.also { appendLog("[client] ping: $it") } } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("ping") }
        OutlinedButton(
            onClick = { scope.launch { client?.getPublicKey()?.also { appendLog("[client] get_public_key: $it") } } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("get_public_key") }

        OutlinedTextField(
            value = thirdPartyPub,
            onValueChange = { thirdPartyPub = it },
            label = { Text("Third-party pubkey hex (for encrypt/decrypt)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = encMsg,
            onValueChange = { encMsg = it },
            label = { Text("Plaintext / ciphertext") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                val pk = thirdPartyPub.trim().ifBlank { return@OutlinedButton }
                scope.launch { client?.nip44Encrypt(pk, encMsg)?.also { appendLog("[client] nip44_encrypt: $it") } }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("nip44_encrypt") }
        OutlinedButton(
            onClick = {
                val pk = thirdPartyPub.trim().ifBlank { return@OutlinedButton }
                scope.launch { client?.nip44Decrypt(pk, encMsg)?.also { appendLog("[client] nip44_decrypt: $it") } }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("nip44_decrypt") }
        OutlinedButton(
            onClick = {
                val pk = thirdPartyPub.trim().ifBlank { return@OutlinedButton }
                scope.launch { client?.nip04Encrypt(pk, encMsg)?.also { appendLog("[client] nip04_encrypt: $it") } }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("nip04_encrypt") }
        OutlinedButton(
            onClick = {
                val pk = thirdPartyPub.trim().ifBlank { return@OutlinedButton }
                scope.launch { client?.nip04Decrypt(pk, encMsg)?.also { appendLog("[client] nip04_decrypt: $it") } }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("nip04_decrypt") }

        OutlinedButton(
            onClick = {
                scope.launch {
                    val unsigned = """{"kind":1,"created_at":${System.currentTimeMillis() / 1000},""" +
                        """"tags":[],"content":"hello from nip46 tester"}"""
                    client?.signEvent(unsigned)?.also { appendLog("[client] sign_event: $it") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("sign_event (kind 1 hello)") }

        HorizontalDivider()
        Text("switch_relays", style = MaterialTheme.typography.titleSmall)
        Text(
            "Asks the bunker for its current relay list (params: []). If the bunker " +
                "returns an array, this client switches its subscriptions to those relays.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = {
                scope.launch { client?.switchRelays()?.also { appendLog("[client] switch_relays: $it") } }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("switch_relays") }

        HorizontalDivider()
        Text("Custom method", style = MaterialTheme.typography.titleSmall)
        Text(
            "Send any NIP-46 method. Each line in the params box is one positional param " +
                "(strings are sent as-is; the protocol always uses string params).",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = customMethod,
            onValueChange = { customMethod = it },
            label = { Text("method name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = customParams,
            onValueChange = { customParams = it },
            label = { Text("params (one per line)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                val method = customMethod.trim().ifBlank {
                    appendLog("[client] custom: method name required")
                    return@OutlinedButton
                }
                val params = customParams.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                scope.launch {
                    client?.request(method, params)?.also { appendLog("[client] $method: $it") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send custom method") }

        OutlinedButton(
            onClick = { client?.close().also { appendLog("[client] closed") } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Close") }

        HorizontalDivider()
        Text("Log", style = MaterialTheme.typography.titleSmall)
        LogPane(log, Modifier.fillMaxWidth())
    }
}

private fun randomSecret(): String {
    val bytes = ByteArray(8)
    Random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
