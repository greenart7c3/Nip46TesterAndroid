package com.greenart7c3.nip46tester.nip46

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.serialization.encodeToString

/**
 * Thin wrapper around Quartz crypto. Centralises the few Quartz APIs the rest of the
 * code touches so that if Quartz changes between versions only this file needs updating.
 */
class CryptoAdapter(privateKeyHex: String? = null) {

    val keyPair: KeyPair = if (privateKeyHex.isNullOrBlank()) {
        KeyPair()
    } else {
        KeyPair(privKey = privateKeyHex.hexToByteArray())
    }

    private val signer = NostrSignerInternal(keyPair)

    val pubKeyHex: String = keyPair.pubKey.toHexKey()
    val privKeyHex: String = keyPair.privKey?.toHexKey() ?: ""

    suspend fun nip44Encrypt(plaintext: String, peerPubKeyHex: String): String =
        signer.nip44Encrypt(plaintext, peerPubKeyHex)

    suspend fun nip44Decrypt(ciphertext: String, peerPubKeyHex: String): String =
        signer.nip44Decrypt(ciphertext, peerPubKeyHex)

    suspend fun nip04Encrypt(plaintext: String, peerPubKeyHex: String): String =
        signer.nip04Encrypt(plaintext, peerPubKeyHex)

    suspend fun nip04Decrypt(ciphertext: String, peerPubKeyHex: String): String =
        signer.nip04Decrypt(ciphertext, peerPubKeyHex)

    /** Signs a kind-24133 event whose content is already an encrypted payload. */
    suspend fun signNip46Event(content: String, recipientPubKeyHex: String): NostrEvent {
        val tags: Array<Array<String>> = arrayOf(arrayOf("p", recipientPubKeyHex))
        val signed: Event = signer.sign(
            createdAt = nowSeconds(),
            kind = NIP46_KIND,
            tags = tags,
            content = content,
        )
        return NostrEvent(
            id = signed.id,
            pubkey = signed.pubKey,
            createdAt = signed.createdAt,
            kind = signed.kind,
            tags = signed.tags.map { it.toList() },
            content = signed.content,
            sig = signed.sig,
        )
    }

    companion object {
        fun nowSeconds(): Long = System.currentTimeMillis() / 1000

        fun newRequestId(): String {
            val bytes = ByteArray(8)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.toHexKey()
        }

        fun encodeRequest(req: Nip46Request): String = NostrJson.encodeToString(req)

        fun encodeResponse(resp: Nip46Response): String = NostrJson.encodeToString(resp)
    }
}
