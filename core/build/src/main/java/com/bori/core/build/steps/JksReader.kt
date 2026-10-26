package com.bori.core.build.steps

import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Reads Java KeyStore (JKS) files, which Android has no KeyStore type for. Android Studio and
 * older keytool versions make keystores in this format, so without it those couldn't sign.
 *
 * The format: magic 0xFEEDFEED, version, entry count, then entries (private keys protected with
 * Sun's SHA-1 keystream scheme, and certificates), and a SHA-1 over the store password, the
 * phrase "Mighty Aphrodite" and everything before it.
 */
internal object JksReader {

    class Entry(val protectedKey: ByteArray?, val chain: List<X509Certificate>)

    private const val MAGIC = 0xFEEDFEED.toInt()

    fun isJks(bytes: ByteArray): Boolean =
        bytes.size >= 4 && DataInputStream(ByteArrayInputStream(bytes)).readInt() == MAGIC

    /** The entries by alias; throws if [storePassword] is wrong or the file is damaged. */
    fun read(bytes: ByteArray, storePassword: CharArray): Map<String, Entry> {
        require(bytes.size > 20) { "Keystore file is too short" }
        val body = bytes.copyOfRange(0, bytes.size - 20)
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(passwordBytes(storePassword))
        sha.update("Mighty Aphrodite".toByteArray(Charsets.UTF_8))
        sha.update(body)
        require(sha.digest().contentEquals(bytes.copyOfRange(bytes.size - 20, bytes.size))) {
            "Wrong keystore password (or the keystore is damaged)"
        }

        val input = DataInputStream(ByteArrayInputStream(body))
        require(input.readInt() == MAGIC) { "Not a JKS keystore" }
        val version = input.readInt()
        val certs = CertificateFactory.getInstance("X.509")
        fun readCert(): X509Certificate {
            if (version == 2) input.readUTF() // certificate type, "X.509"
            val der = ByteArray(input.readInt()).also { input.readFully(it) }
            return certs.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

        val entries = linkedMapOf<String, Entry>()
        repeat(input.readInt()) {
            val tag = input.readInt()
            val alias = input.readUTF()
            input.readLong() // creation date
            when (tag) {
                1 -> {
                    val key = ByteArray(input.readInt()).also { input.readFully(it) }
                    val chain = List(input.readInt()) { readCert() }
                    entries[alias] = Entry(key, chain)
                }
                2 -> entries[alias] = Entry(null, listOf(readCert()))
                else -> throw IllegalArgumentException("Unsupported JKS entry type $tag")
            }
        }
        return entries
    }

    /** Decrypts a JKS-protected private key; throws if [keyPassword] is wrong. */
    fun decryptKey(protectedKey: ByteArray, keyPassword: CharArray): PrivateKey {
        val encrypted = EncryptedPrivateKeyInfo.getInstance(protectedKey).encryptedData
        val salt = encrypted.copyOfRange(0, 20)
        val cipherText = encrypted.copyOfRange(20, encrypted.size - 20)
        val check = encrypted.copyOfRange(encrypted.size - 20, encrypted.size)
        val password = passwordBytes(keyPassword)

        val sha = MessageDigest.getInstance("SHA-1")
        val plain = ByteArray(cipherText.size)
        var digest = salt
        var i = 0
        while (i < cipherText.size) {
            sha.update(password)
            sha.update(digest)
            digest = sha.digest()
            for (j in digest.indices) {
                if (i + j >= cipherText.size) break
                plain[i + j] = (cipherText[i + j].toInt() xor digest[j].toInt()).toByte()
            }
            i += digest.size
        }
        sha.update(password)
        sha.update(plain)
        require(sha.digest().contentEquals(check)) { "Wrong key password" }

        val spec = PKCS8EncodedKeySpec(plain)
        val algorithms = listOf("RSA", "EC", "DSA")
        for (algorithm in algorithms) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec)
            } catch (_: Exception) { }
        }
        throw IllegalArgumentException("Unsupported key algorithm in keystore")
    }

    /** JKS passwords are hashed as UTF-16 big-endian, two bytes per char. */
    private fun passwordBytes(password: CharArray): ByteArray {
        val out = ByteArray(password.size * 2)
        password.forEachIndexed { i, c ->
            out[i * 2] = (c.code shr 8).toByte()
            out[i * 2 + 1] = c.code.toByte()
        }
        return out
    }
}
