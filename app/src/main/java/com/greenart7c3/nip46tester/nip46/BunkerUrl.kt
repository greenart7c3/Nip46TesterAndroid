package com.greenart7c3.nip46tester.nip46

import android.net.Uri

data class BunkerUrl(
    val remotePubKey: String,
    val relays: List<String>,
    val secret: String?,
) {
    companion object {
        fun parse(url: String): BunkerUrl {
            val trimmed = url.trim()
            require(trimmed.startsWith("bunker://")) { "Not a bunker URL" }
            val uri = Uri.parse(trimmed)
            val pubkey = uri.host ?: error("Missing pubkey in bunker URL")
            val relays = uri.getQueryParameters("relay")
            val secret = uri.getQueryParameter("secret")
            return BunkerUrl(pubkey, relays, secret)
        }

        fun build(remotePubKey: String, relays: List<String>, secret: String?): String {
            val sb = StringBuilder("bunker://").append(remotePubKey)
            val params = buildList {
                relays.forEach { add("relay" to it) }
                if (!secret.isNullOrBlank()) add("secret" to secret)
            }
            if (params.isNotEmpty()) {
                sb.append('?')
                sb.append(
                    params.joinToString("&") {
                        "${Uri.encode(it.first)}=${Uri.encode(it.second)}"
                    },
                )
            }
            return sb.toString()
        }
    }
}

data class NostrConnectUrl(
    val clientPubKey: String,
    val relays: List<String>,
    val secret: String,
    val name: String?,
    val url: String?,
    val perms: String?,
) {
    companion object {
        fun parse(url: String): NostrConnectUrl {
            val trimmed = url.trim()
            require(trimmed.startsWith("nostrconnect://")) { "Not a nostrconnect URL" }
            val uri = Uri.parse(trimmed)
            return NostrConnectUrl(
                clientPubKey = uri.host ?: error("Missing pubkey"),
                relays = uri.getQueryParameters("relay"),
                secret = uri.getQueryParameter("secret") ?: "",
                name = uri.getQueryParameter("name"),
                url = uri.getQueryParameter("url"),
                perms = uri.getQueryParameter("perms"),
            )
        }

        fun build(
            clientPubKey: String,
            relays: List<String>,
            secret: String,
            name: String? = null,
            perms: String? = null,
        ): String {
            val sb = StringBuilder("nostrconnect://").append(clientPubKey)
            val params = buildList {
                relays.forEach { add("relay" to it) }
                add("secret" to secret)
                if (!name.isNullOrBlank()) add("name" to name)
                if (!perms.isNullOrBlank()) add("perms" to perms)
            }
            sb.append('?')
            sb.append(
                params.joinToString("&") {
                    "${Uri.encode(it.first)}=${Uri.encode(it.second)}"
                },
            )
            return sb.toString()
        }
    }
}
