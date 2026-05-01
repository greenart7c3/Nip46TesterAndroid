package com.greenart7c3.nip46tester.nip46

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * NIP-46 client. Supports both flows defined by the spec:
 *
 *  - bunker:// (bunker-initiated): the user knows the bunker's pubkey and relays up front
 *    and sends a connect request first.
 *  - nostrconnect:// (client-initiated): the client publishes its own URL out-of-band, then
 *    listens; the bunker is the one that initiates contact. The bunker's pubkey is
 *    discovered from the first decryptable inbound message.
 */
class Nip46Client(
    val relayUrls: List<String>,
    initialRemotePubKey: String? = null,
    val initialSecret: String? = null,
    privateKeyHex: String? = null,
    val onLog: (String) -> Unit = {},
) {
    constructor(
        bunker: BunkerUrl,
        privateKeyHex: String? = null,
        onLog: (String) -> Unit = {},
    ) : this(
        relayUrls = bunker.relays,
        initialRemotePubKey = bunker.remotePubKey,
        initialSecret = bunker.secret,
        privateKeyHex = privateKeyHex,
        onLog = onLog,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val crypto = CryptoAdapter(privateKeyHex)
    private val relays = relayUrls.map { RelayConnection(it) }
    private val pending = mutableMapOf<String, CompletableDeferred<Nip46Response>>()
    private val subscriptionId = "nip46-client-${System.currentTimeMillis()}"

    @Volatile private var remotePubKey: String? = initialRemotePubKey

    val clientPubKey: String get() = crypto.pubKeyHex
    val bunkerPubKey: String? get() = remotePubKey
    val isClientInitiated: Boolean = initialRemotePubKey == null

    /** Builds a nostrconnect:// URL pointing at this client. Use only in client-initiated mode. */
    fun nostrConnectUrl(secret: String, name: String? = null, perms: String? = null): String =
        NostrConnectUrl.build(
            clientPubKey = crypto.pubKeyHex,
            relays = relayUrls,
            secret = secret,
            name = name,
            perms = perms,
        )

    fun start() {
        if (relays.isEmpty()) {
            onLog("[client] No relays configured")
            return
        }
        relays.forEach { relay ->
            relay.connect()
            scope.launch {
                relay.status.collect { onLog("[relay ${relay.shortName()}] $it") }
            }
            scope.launch {
                relay.messages.collect { msg ->
                    when (msg) {
                        is RelayMessage.Event -> handleIncoming(msg.event)
                        is RelayMessage.Ok -> onLog("[relay ${relay.shortName()}] OK ${msg.eventId.take(8)} accepted=${msg.accepted} ${msg.message}")
                        is RelayMessage.Notice -> onLog("[relay ${relay.shortName()}] NOTICE ${msg.text}")
                        is RelayMessage.Closed -> onLog("[relay ${relay.shortName()}] CLOSED ${msg.message}")
                        else -> {}
                    }
                }
            }
            val filter: JsonObject = buildJsonObject {
                put("kinds", JsonArray(listOf(JsonPrimitive(NIP46_KIND))))
                // In nostrconnect mode the bunker's pubkey is unknown until first contact;
                // skip the authors filter and discover it from incoming events.
                remotePubKey?.let { put("authors", JsonArray(listOf(JsonPrimitive(it)))) }
                put("#p", JsonArray(listOf(JsonPrimitive(crypto.pubKeyHex))))
                put("limit", JsonPrimitive(0))
            }
            relay.subscribe(subscriptionId, filter)
            onLog("[relay ${relay.shortName()}] subscribed as $subscriptionId")
        }
    }

    private suspend fun handleIncoming(event: NostrEvent) {
        if (remotePubKey != null && event.pubkey != remotePubKey) return
        val plaintext = try {
            crypto.nip44Decrypt(event.content, event.pubkey)
        } catch (t: Throwable) {
            onLog("[client] decrypt failed from ${event.pubkey.take(8)}…: ${t.message}")
            return
        }
        val resp = try {
            NostrJson.decodeFromString(Nip46Response.serializer(), plaintext)
        } catch (t: Throwable) {
            onLog("[client] non-response payload from ${event.pubkey.take(8)}…: ${t.message}")
            return
        }
        if (remotePubKey == null) {
            remotePubKey = event.pubkey
            onLog("[client] discovered bunker pubkey: ${event.pubkey}")
        }
        onLog("[client] <- ${resp.id.take(8)} result=${resp.result?.take(60)} error=${resp.error}")
        pending.remove(resp.id)?.complete(resp)
    }

    suspend fun request(method: String, params: List<String>, timeoutMs: Long = 15_000): Nip46Response? {
        val target = remotePubKey
        if (target == null) {
            onLog("[client] no bunker connected yet — waiting for bunker to publish to nostrconnect URL")
            return null
        }
        val req = Nip46Request(id = CryptoAdapter.newRequestId(), method = method, params = params)
        val deferred = CompletableDeferred<Nip46Response>()
        pending[req.id] = deferred

        val plaintext = CryptoAdapter.encodeRequest(req)
        val ciphertext = crypto.nip44Encrypt(plaintext, target)
        val event = crypto.signNip46Event(ciphertext, target)
        relays.forEach { it.publish(event) }
        onLog("[client] -> ${req.method} id=${req.id.take(8)}")

        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    suspend fun connect(): Nip46Response? {
        val target = remotePubKey ?: run {
            onLog("[client] cannot send connect: bunker pubkey unknown (use nostrconnect or wait for ack)")
            return null
        }
        val params = mutableListOf(target)
        initialSecret?.let { params.add(it) }
        return request("connect", params)
    }

    suspend fun ping(): Nip46Response? = request("ping", emptyList())

    suspend fun getPublicKey(): Nip46Response? = request("get_public_key", emptyList())

    suspend fun signEvent(unsignedJson: String): Nip46Response? = request("sign_event", listOf(unsignedJson))

    suspend fun nip44Encrypt(thirdPartyPubKey: String, plaintext: String): Nip46Response? =
        request("nip44_encrypt", listOf(thirdPartyPubKey, plaintext))

    suspend fun nip44Decrypt(thirdPartyPubKey: String, ciphertext: String): Nip46Response? =
        request("nip44_decrypt", listOf(thirdPartyPubKey, ciphertext))

    suspend fun nip04Encrypt(thirdPartyPubKey: String, plaintext: String): Nip46Response? =
        request("nip04_encrypt", listOf(thirdPartyPubKey, plaintext))

    suspend fun nip04Decrypt(thirdPartyPubKey: String, ciphertext: String): Nip46Response? =
        request("nip04_decrypt", listOf(thirdPartyPubKey, ciphertext))

    fun close() {
        relays.forEach {
            it.unsubscribe(subscriptionId)
            it.close()
        }
    }
}

private fun RelayConnection.shortName(): String = url.substringAfter("://").take(24)
