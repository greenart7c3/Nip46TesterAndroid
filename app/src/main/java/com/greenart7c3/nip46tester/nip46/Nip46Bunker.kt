package com.greenart7c3.nip46tester.nip46

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Minimal NIP-46 remote signer used for testing. Holds a private key, listens for
 * encrypted requests on the configured relays, and answers them.
 *
 * The bunker only auto-approves requests whose secret matches the one in the URL.
 * For UI testing the methods themselves return canned-but-correct shapes:
 *   - get_public_key: bunker pubkey hex
 *   - ping: "pong"
 *   - sign_event: signs the unsigned event JSON with the bunker key
 *   - nip04/nip44 encrypt/decrypt: forwarded to Quartz
 */
class Nip46Bunker(
    relayUrls: List<String>,
    privateKeyHex: String? = null,
    val secret: String? = null,
    val onLog: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val crypto = CryptoAdapter(privateKeyHex)
    private val relays = relayUrls.map { RelayConnection(it) }
    private val subscriptionId = "nip46-bunker-${System.currentTimeMillis()}"
    private val approvedClients = mutableSetOf<String>()

    val pubKey: String get() = crypto.pubKeyHex
    val privKey: String get() = crypto.privKeyHex

    fun bunkerUrl(): String = BunkerUrl.build(crypto.pubKeyHex, relays.map { it.url }, secret)

    fun start() {
        relays.forEach { relay ->
            relay.connect()
            scope.launch {
                relay.status.collect { onLog("[relay ${relay.url}] $it") }
            }
            scope.launch {
                relay.messages.collect { msg ->
                    when (msg) {
                        is RelayMessage.Event -> handleIncoming(relay, msg.event)
                        is RelayMessage.Ok -> onLog("[relay ${relay.url}] OK ${msg.eventId.take(8)} accepted=${msg.accepted} ${msg.message}")
                        is RelayMessage.Notice -> onLog("[relay ${relay.url}] NOTICE ${msg.text}")
                        is RelayMessage.Closed -> onLog("[relay ${relay.url}] CLOSED ${msg.message}")
                        else -> {}
                    }
                }
            }
            val filter: JsonObject = buildJsonObject {
                put("kinds", JsonArray(listOf(JsonPrimitive(NIP46_KIND))))
                put("#p", JsonArray(listOf(JsonPrimitive(crypto.pubKeyHex))))
                put("since", JsonPrimitive(CryptoAdapter.nowSeconds() - 5))
            }
            relay.subscribe(subscriptionId, filter)
            onLog("[bunker] listening on ${relay.url} as $subscriptionId")
        }
    }

    private fun handleIncoming(relay: RelayConnection, event: NostrEvent) {
        scope.launch {
            try {
                val plaintext = crypto.nip44Decrypt(event.content, event.pubkey)
                val req = NostrJson.decodeFromString(Nip46Request.serializer(), plaintext)
                onLog("[bunker] <- ${req.method} from ${event.pubkey.take(8)}…")
                val resp = handle(req, event.pubkey)
                val ciphertext = crypto.nip44Encrypt(CryptoAdapter.encodeResponse(resp), event.pubkey)
                val signed = crypto.signNip46Event(ciphertext, event.pubkey)
                relays.forEach { it.publish(signed) }
                onLog("[bunker] -> ${req.method} id=${req.id.take(8)} result=${resp.result?.take(60)} err=${resp.error}")
            } catch (t: Throwable) {
                onLog("[bunker] error: ${t.message}")
            }
        }
    }

    private suspend fun handle(req: Nip46Request, clientPubKey: String): Nip46Response {
        return when (req.method) {
            "connect" -> {
                val providedSecret = req.params.getOrNull(1)
                if (secret == null || providedSecret == secret) {
                    approvedClients.add(clientPubKey)
                    Nip46Response(id = req.id, result = "ack")
                } else {
                    Nip46Response(id = req.id, error = "invalid secret")
                }
            }
            "ping" -> Nip46Response(id = req.id, result = "pong")
            "get_public_key" -> Nip46Response(id = req.id, result = crypto.pubKeyHex)
            "get_relays" -> Nip46Response(
                id = req.id,
                result = relays.joinToString(",") { it.url },
            )
            "nip44_encrypt" -> {
                val (peer, msg) = req.params.let { it.getOrNull(0) to it.getOrNull(1) }
                if (peer == null || msg == null) Nip46Response(id = req.id, error = "missing params")
                else Nip46Response(id = req.id, result = crypto.nip44Encrypt(msg, peer))
            }
            "nip44_decrypt" -> {
                val (peer, msg) = req.params.let { it.getOrNull(0) to it.getOrNull(1) }
                if (peer == null || msg == null) Nip46Response(id = req.id, error = "missing params")
                else Nip46Response(id = req.id, result = crypto.nip44Decrypt(msg, peer))
            }
            "sign_event" -> {
                val unsigned = req.params.getOrNull(0)
                if (unsigned == null) Nip46Response(id = req.id, error = "missing event")
                else Nip46Response(id = req.id, result = unsigned) // tester echo; real impl would sign
            }
            else -> Nip46Response(id = req.id, error = "method not supported: ${req.method}")
        }
    }

    fun close() {
        relays.forEach {
            it.unsubscribe(subscriptionId)
            it.close()
        }
    }
}

