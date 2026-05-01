package com.greenart7c3.nip46tester.nip46

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Probes whether a relay can carry NIP-46 traffic. NIP-46 uses ephemeral kind 24133
 * events. A relay supports it if it (a) accepts a published 24133 event with OK true
 * and (b) routes that event to a matching subscription.
 */
class Nip46RelayTester(val relayUrl: String, val onLog: (String) -> Unit = {}) {

    data class Result(
        val accepted: Boolean,
        val routedToSubscriber: Boolean,
        val okMessage: String,
        val notes: List<String>,
    )

    suspend fun run(timeoutMs: Long = 15_000): Result {
        val notes = mutableListOf<String>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val publisher = CryptoAdapter()
        val subscriber = CryptoAdapter()

        val relay = RelayConnection(relayUrl)
        relay.connect()

        val okSignal = CompletableDeferred<RelayMessage.Ok>()
        val eventSignal = CompletableDeferred<NostrEvent>()
        val noticeSink = mutableListOf<String>()
        val statusErr = CompletableDeferred<String>()

        scope.launch {
            relay.status.collect {
                onLog("[relay] $it")
                if (it is RelayStatus.Failed && !statusErr.isCompleted) statusErr.complete(it.reason)
            }
        }
        scope.launch {
            relay.messages.collect { m ->
                when (m) {
                    is RelayMessage.Ok -> if (!okSignal.isCompleted) okSignal.complete(m)
                    is RelayMessage.Event -> if (m.event.kind == NIP46_KIND && !eventSignal.isCompleted) eventSignal.complete(m.event)
                    is RelayMessage.Notice -> noticeSink.add(m.text).also { onLog("[relay] NOTICE ${m.text}") }
                    is RelayMessage.Closed -> noticeSink.add("CLOSED ${m.message}").also { onLog("[relay] CLOSED ${m.message}") }
                    else -> {}
                }
            }
        }

        val subId = "nip46-probe-${System.currentTimeMillis()}"
        val filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(NIP46_KIND))))
            put("#p", JsonArray(listOf(JsonPrimitive(subscriber.pubKeyHex))))
            put("limit", JsonPrimitive(0))
        }
        relay.subscribe(subId, filter)
        onLog("[probe] subscribed as $subId waiting for kind 24133 to ${subscriber.pubKeyHex.take(8)}…")

        val ciphertext = publisher.nip44Encrypt("""{"id":"probe","method":"ping","params":[]}""", subscriber.pubKeyHex)
        val signed = publisher.signNip46Event(ciphertext, subscriber.pubKeyHex)
        relay.publish(signed)
        onLog("[probe] published kind 24133 id=${signed.id.take(8)}…")

        val ok = withTimeoutOrNull(timeoutMs) { okSignal.await() }
        if (ok == null) notes += "No OK reply within ${timeoutMs}ms"
        val routed = withTimeoutOrNull(timeoutMs) { eventSignal.await() } != null
        if (!routed) notes += "Subscribed listener did not receive event (relay may drop ephemeral kinds)"
        if (statusErr.isCompleted) notes += "Connection error: ${statusErr.getCompleted()}"
        notes += noticeSink.map { "Notice: $it" }

        relay.unsubscribe(subId)
        relay.close()

        return Result(
            accepted = ok?.accepted == true,
            routedToSubscriber = routed,
            okMessage = ok?.message ?: "(no OK)",
            notes = notes,
        )
    }
}
