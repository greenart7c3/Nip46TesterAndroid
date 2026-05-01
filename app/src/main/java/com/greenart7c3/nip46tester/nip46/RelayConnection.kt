package com.greenart7c3.nip46tester.nip46

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

sealed class RelayMessage {
    data class Event(val subscriptionId: String, val event: NostrEvent) : RelayMessage()
    data class Eose(val subscriptionId: String) : RelayMessage()
    data class Notice(val text: String) : RelayMessage()
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage()
    data class Auth(val challenge: String) : RelayMessage()
    data class Closed(val subscriptionId: String, val message: String) : RelayMessage()
    data class RawText(val text: String) : RelayMessage()
}

sealed class RelayStatus {
    data object Disconnected : RelayStatus()
    data object Connecting : RelayStatus()
    data object Connected : RelayStatus()
    data class Failed(val reason: String) : RelayStatus()
}

class RelayConnection(
    val url: String,
    private val client: OkHttpClient = defaultClient,
) {
    private var ws: WebSocket? = null

    private val _status = MutableStateFlow<RelayStatus>(RelayStatus.Disconnected)
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    private val _messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 64)
    val messages: Flow<RelayMessage> = _messages.asSharedFlow()

    fun connect() {
        if (ws != null) return
        _status.value = RelayStatus.Connecting
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _status.value = RelayStatus.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                _messages.tryEmit(RelayMessage.RawText(text))
                parse(text)?.let { _messages.tryEmit(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _status.value = RelayStatus.Failed(t.message ?: t.javaClass.simpleName)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _status.value = RelayStatus.Disconnected
            }
        })
    }

    fun send(text: String): Boolean = ws?.send(text) == true

    fun close() {
        ws?.close(1000, "client closing")
        ws = null
        _status.value = RelayStatus.Disconnected
    }

    private fun parse(text: String): RelayMessage? = try {
        val arr: JsonArray = NostrJson.parseToJsonElement(text).jsonArray
        when ((arr[0] as JsonPrimitive).content) {
            "EVENT" -> RelayMessage.Event(
                subscriptionId = (arr[1] as JsonPrimitive).content,
                event = NostrJson.decodeFromJsonElement(NostrEvent.serializer(), arr[2] as JsonObject),
            )
            "EOSE" -> RelayMessage.Eose((arr[1] as JsonPrimitive).content)
            "NOTICE" -> RelayMessage.Notice((arr[1] as JsonPrimitive).content)
            "OK" -> RelayMessage.Ok(
                eventId = (arr[1] as JsonPrimitive).content,
                accepted = (arr[2] as JsonPrimitive).boolean,
                message = (arr.getOrNull(3) as? JsonPrimitive)?.contentOrNull ?: "",
            )
            "AUTH" -> RelayMessage.Auth((arr[1] as JsonPrimitive).content)
            "CLOSED" -> RelayMessage.Closed(
                subscriptionId = (arr[1] as JsonPrimitive).content,
                message = (arr.getOrNull(2) as? JsonPrimitive)?.contentOrNull ?: "",
            )
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    fun publish(event: NostrEvent) {
        val eventJson = NostrJson.encodeToJsonElement(NostrEvent.serializer(), event)
        val arr = JsonArray(listOf(JsonPrimitive("EVENT"), eventJson))
        send(arr.toString())
    }

    fun subscribe(subscriptionId: String, filter: JsonElement) {
        val arr = JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(subscriptionId), filter))
        send(arr.toString())
    }

    fun unsubscribe(subscriptionId: String) {
        val arr = JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subscriptionId)))
        send(arr.toString())
    }

    companion object {
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
