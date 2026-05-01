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
 * NIP-46 client. Connects to a bunker, sends signed encrypted requests, awaits responses.
 *
 * Logs every wire-level event so the UI can show what happens during the test run.
 */
class Nip46Client(
    val bunker: BunkerUrl,
    privateKeyHex: String? = null,
    val onLog: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val crypto = CryptoAdapter(privateKeyHex)
    private val relays = bunker.relays.map { RelayConnection(it) }
    private val pending = mutableMapOf<String, CompletableDeferred<Nip46Response>>()
    private val subscriptionId = "nip46-client-${System.currentTimeMillis()}"

    val clientPubKey: String get() = crypto.pubKeyHex

    fun start() {
        if (relays.isEmpty()) {
            onLog("[client] No relays in bunker URL")
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
                        is RelayMessage.RawText -> { /* logged via parsed variants */ }
                        else -> {}
                    }
                }
            }
            // Subscribe to messages addressed to us from the bunker.
            val filter: JsonObject = buildJsonObject {
                put("kinds", JsonArray(listOf(JsonPrimitive(NIP46_KIND))))
                put("authors", JsonArray(listOf(JsonPrimitive(bunker.remotePubKey))))
                put("#p", JsonArray(listOf(JsonPrimitive(crypto.pubKeyHex))))
                put("limit", JsonPrimitive(0))
            }
            relay.subscribe(subscriptionId, filter)
            onLog("[relay ${relay.shortName()}] subscribed as $subscriptionId")
        }
    }

    private suspend fun handleIncoming(event: NostrEvent) {
        if (event.pubkey != bunker.remotePubKey) return
        try {
            val plaintext = crypto.nip44Decrypt(event.content, event.pubkey)
            val resp = NostrJson.decodeFromString(Nip46Response.serializer(), plaintext)
            onLog("[client] <- ${resp.id.take(8)} result=${resp.result?.take(60)} error=${resp.error}")
            pending.remove(resp.id)?.complete(resp)
        } catch (t: Throwable) {
            onLog("[client] decrypt failed: ${t.message}")
        }
    }

    suspend fun request(method: String, params: List<String>, timeoutMs: Long = 15_000): Nip46Response? {
        val req = Nip46Request(id = CryptoAdapter.newRequestId(), method = method, params = params)
        val deferred = CompletableDeferred<Nip46Response>()
        pending[req.id] = deferred

        val plaintext = CryptoAdapter.encodeRequest(req)
        val ciphertext = crypto.nip44Encrypt(plaintext, bunker.remotePubKey)
        val event = crypto.signNip46Event(ciphertext, bunker.remotePubKey)
        relays.forEach { it.publish(event) }
        onLog("[client] -> ${req.method} id=${req.id.take(8)}")

        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    suspend fun connect(): Nip46Response? {
        val params = mutableListOf(bunker.remotePubKey)
        bunker.secret?.let { params.add(it) }
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
