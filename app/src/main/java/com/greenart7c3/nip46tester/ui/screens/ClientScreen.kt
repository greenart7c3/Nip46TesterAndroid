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
import androidx.compose.ui.unit.dp
import com.greenart7c3.nip46tester.nip46.BunkerUrl
import com.greenart7c3.nip46tester.nip46.Nip46Client
import kotlinx.coroutines.launch

@Composable
fun ClientScreen() {
    val scope = rememberCoroutineScope()
    var bunkerInput by remember { mutableStateOf("") }
    var clientPriv by remember { mutableStateOf("") }
    var thirdPartyPub by remember { mutableStateOf("") }
    var encMsg by remember { mutableStateOf("hello from nip-46 tester") }
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
        Text(
            "Paste a bunker:// URL, then run the test methods. " +
                "The client uses NIP-44 to encrypt requests addressed to the bunker pubkey, " +
                "publishes them as kind 24133, and waits for replies.",
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

        HorizontalDivider()
        Text("Methods", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { scope.launch { client?.connect()?.also { appendLog("[client] connect: $it") } } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("connect") }
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
                scope.launch {
                    val unsigned = """{"kind":1,"created_at":${System.currentTimeMillis() / 1000},""" +
                        """"tags":[],"content":"hello from nip46 tester"}"""
                    client?.signEvent(unsigned)?.also { appendLog("[client] sign_event: $it") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("sign_event (kind 1 hello)") }

        OutlinedButton(
            onClick = { client?.close().also { appendLog("[client] closed") } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Close") }

        HorizontalDivider()
        Text("Log", style = MaterialTheme.typography.titleSmall)
        LogPane(log, Modifier.fillMaxWidth())
    }
}
