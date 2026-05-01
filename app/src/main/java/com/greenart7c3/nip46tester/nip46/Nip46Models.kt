package com.greenart7c3.nip46tester.nip46

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val NostrJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

@Serializable
data class Nip46Request(
    val id: String,
    val method: String,
    val params: List<String> = emptyList(),
)

@Serializable
data class Nip46Response(
    val id: String,
    val result: String? = null,
    val error: String? = null,
)

@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
)

@Serializable
data class UnsignedEvent(
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
)

const val NIP46_KIND = 24133
