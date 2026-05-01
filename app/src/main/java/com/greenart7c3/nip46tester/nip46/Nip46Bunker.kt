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
 * Two ways to bring a client on board:
 *  - Bunker-initiated: hand the printed bunker:// URL to the client.
 *  - Client-initiated (nostrconnect://): paste the client's URL into [connectToClient];
 *    the bunker then connects to the URL's relays and publishes a "connect" ack.
 */
class Nip46Bunker(
    relayUrls: List<String>,
    privateKeyHex: String? = null,
    val secret: String? = null,
    val onLog: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val crypto = CryptoAdapter(privateKeyHex)
    private val relays: MutableList<RelayConnection> = relayUrls.map { RelayConnection(it) }.toMutableList()
    private val subscriptionId = "nip46-bunker-${System.currentTimeMillis()}"
    private val approvedClients = mutableSetOf<String>()
    private var started = false

    val pubKey: String get() = crypto.pubKeyHex
    val privKey: String get() = crypto.privKeyHex
    val relayUrlsSnapshot: List<String> get() = relays.map { it.url }

    fun bunkerUrl(): String = BunkerUrl.build(crypto.pubKeyHex, relays.map { it.url }, secret)

    fun start() {
        started = true
        relays.toList().forEach { wireUpRelay(it) }
    }

    private fun wireUpRelay(relay: RelayConnection) {
        relay.connect()
        scope.launch {
            relay.status.collect { onLog("[relay ${relay.url}] $it") }
        }
        scope.launch {
            relay.messages.collect { msg ->
                when (msg) {
                    is RelayMessage.Event -> handleIncoming(msg.event)
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

    /**
     * Adds the URL's relays to this bunker's relay set (if missing) and publishes an
     * encrypted "connect" ack to the client. The id of the response carries the secret
     * back to the client per the NIP-46 nostrconnect handshake.
     */
    suspend fun connectToClient(url: NostrConnectUrl) {
        if (!started) {
            onLog("[bunker] cannot connect: bunker not started")
            return
        }
        url.relays.forEach { relayUrl ->
            if (relays.none { it.url == relayUrl }) {
                val r = RelayConnection(relayUrl)
                relays += r
                wireUpRelay(r)
                onLog("[bunker] added relay $relayUrl from nostrconnect URL")
            }
        }
        approvedClients.add(url.clientPubKey)

        val response = Nip46Response(
            id = url.secret.ifBlank { CryptoAdapter.newRequestId() },
            result = "ack",
        )
        val ciphertext = crypto.nip44Encrypt(CryptoAdapter.encodeResponse(response), url.clientPubKey)
        val signed = crypto.signNip46Event(ciphertext, url.clientPubKey)
        // Publish on every relay listed in the URL so the client's subscription receives it.
        val targets = relays.filter { it.url in url.relays }
        targets.forEach { it.publish(signed) }
        onLog("[bunker] -> connect ack to ${url.clientPubKey.take(16)}… on ${targets.size} relay(s)")
    }

    private fun handleIncoming(event: NostrEvent) {
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
