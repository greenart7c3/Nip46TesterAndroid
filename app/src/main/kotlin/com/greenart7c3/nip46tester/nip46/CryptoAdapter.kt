package com.greenart7c3.nip46tester.nip46

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip44Encryption.Nip44
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlin.coroutines.resume

/**
 * Thin wrapper around Quartz crypto so the protocol code below stays decoupled from
 * specific Quartz internals. If a Quartz API moves, only this file needs adjusting.
 */
class CryptoAdapter(privateKeyHex: String? = null) {

    val keyPair: KeyPair = if (privateKeyHex.isNullOrBlank()) {
        KeyPair()
    } else {
        KeyPair(privKey = privateKeyHex.hexToBytes())
    }

    private val signer = NostrSignerInternal(keyPair)
    private val nip44 = Nip44()

    val pubKeyHex: String = keyPair.pubKey.toHex()
    val privKeyHex: String = keyPair.privKey?.toHex() ?: ""

    fun nip44Encrypt(plaintext: String, peerPubKeyHex: String): String =
        nip44.encrypt(plaintext, keyPair.privKey!!, peerPubKeyHex.hexToBytes())

    fun nip44Decrypt(ciphertext: String, peerPubKeyHex: String): String =
        nip44.decrypt(ciphertext, keyPair.privKey!!, peerPubKeyHex.hexToBytes())

    /** Signs a kind-24133 event whose content is already an encrypted payload. */
    suspend fun signNip46Event(content: String, recipientPubKeyHex: String): NostrEvent =
        suspendCancellableCoroutine { cont ->
            signer.sign(
                createdAt = nowSeconds(),
                kind = NIP46_KIND,
                tags = arrayOf(arrayOf("p", recipientPubKeyHex)),
                content = content,
            ) { signed ->
                cont.resume(
                    NostrEvent(
                        id = signed.id,
                        pubkey = signed.pubKey,
                        createdAt = signed.createdAt,
                        kind = signed.kind,
                        tags = signed.tags.map { it.toList() },
                        content = signed.content,
                        sig = signed.sig,
                    ),
                )
            }
        }

    companion object {
        fun nowSeconds(): Long = System.currentTimeMillis() / 1000

        fun newRequestId(): String {
            val bytes = ByteArray(8)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.toHex()
        }

        fun encodeRequest(req: Nip46Request): String = NostrJson.encodeToString(req)

        fun encodeResponse(resp: Nip46Response): String = NostrJson.encodeToString(resp)
    }
}

internal fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Invalid hex length" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}
