package dev.msk.norouterdrop

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing: ephemeral X25519 + a 6-digit Short Authentication String (SAS), the
 * same numeric-comparison pattern Bluetooth pairing uses. The SAS is a hash of
 * BOTH public keys, so a man-in-the-middle who relays or swaps keys makes the
 * two phones show different codes. The user compares the codes on both screens;
 * only after both confirm does either side store the key.
 */
class PairingSession {
    // X25519 from BouncyCastle (the phone's built-in X25519 is missing or broken on some devices and crashed pairing).
    private val priv = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(SecureRandom())
    /** Raw 32-byte X25519 public key. */
    val publicKeyEncoded: ByteArray = priv.generatePublicKey().encoded

    /** Returns (master secret, 6-digit SAS). Null if the peer key is malformed. */
    fun finish(peerPublicKeyEncoded: ByteArray): Pair<ByteArray, String>? = try {
        require(peerPublicKeyEncoded.size == 32)
        val secret = ByteArray(32)
        priv.generateSecret(org.bouncycastle.crypto.params.X25519PublicKeyParameters(peerPublicKeyEncoded, 0), secret, 0)
        val sorted = listOf(publicKeyEncoded, peerPublicKeyEncoded).sortedWith { a, b ->
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return@sortedWith d
            }
            a.size - b.size
        }
        val h = sha256(sorted[0], sorted[1], "norouterdrop-code-v1".toByteArray(Charsets.UTF_8))
        val num = ((h[0].toInt() and 0xFF) shl 16) or ((h[1].toInt() and 0xFF) shl 8) or (h[2].toInt() and 0xFF)
        LinkKeys.masterFromSecret(secret) to "%06d".format(num % 1_000_000)
    } catch (e: Exception) { null }

    companion object {
        fun sha256(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            for (p in parts) md.update(p)
            return md.digest()
        }
    }
}

/**
 * Key hierarchy, HKDF-style with SHA-256 over domain-separated labels:
 *   ECDH secret -> pair master (stored, Keystore-wrapped)
 *   pair master -> per-direction link keys (A2B / B2A by peer-id order)
 * Directional keys stop reflection: a phone never decrypts with the key it
 * encrypts with, so its own frames bounced back at it are rejected.
 */
object LinkKeys {
    fun masterFromSecret(secret: ByteArray): ByteArray =
        PairingSession.sha256("td-master-v1".toByteArray(Charsets.UTF_8), secret)

    /** Returns (sendKey, receiveKey) for the calling side. */
    fun directional(master: ByteArray, myId: String, peerId: String): Pair<SecretKey, SecretKey> {
        val a = minOf(myId, peerId)
        val b = maxOf(myId, peerId)
        fun kdf(tag: String) = SecretKeySpec(
            PairingSession.sha256("td-link-v1".toByteArray(Charsets.UTF_8), master,
                "$a|$b|$tag".toByteArray(Charsets.UTF_8)), "AES")
        val ab = kdf("A2B")
        val ba = kdf("B2A")
        return if (myId == a) ab to ba else ba to ab
    }
}

/**
 * The pair master never touches disk in plaintext: it is wrapped by an
 * AES-256-GCM key that lives only in Android Keystore (hardware-backed where
 * the phone supports it, non-exportable by API design).
 */
object KeystoreWrap {
    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "norouterdrop_wrap_v1"

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance("AES", KS)
        kg.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return kg.generateKey()
    }

    fun wrap(plain: ByteArray): String {
        // Keystore keys must generate their own IV (randomized encryption required); read it back from the cipher.
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = c.iv
        return Base64.encodeToString(iv + c.doFinal(plain), Base64.NO_WRAP)
    }

    fun unwrap(b64: String): ByteArray? = try {
        val blob = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, blob, 0, 12))
        c.doFinal(blob, 12, blob.size - 12)
    } catch (e: Exception) { null }
}
