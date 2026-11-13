package com.bori.core.build.steps

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Makes a new release (upload) signing key on the phone, the way Android Studio's "Generate Signed
 * Bundle → Create new key store" does: an RSA key and a self-signed SHA-256 certificate in a PKCS12
 * keystore (keytool's default format since Java 9, read by [ApkSigner], keytool and Google Play).
 * PKCS12 keeps one password for the store and the key, so there's one password.
 */
object KeystoreCreator {
    /** Who the certificate names. Only [name] is needed; Play never shows these. */
    data class Owner(
        val name: String,
        val organization: String = "",
        val city: String = "",
        val country: String = "",
    )

    const val KEY_BITS = 4096
    /** Play needs a certificate valid until at least late 2033; Android Studio suggests 25 years. */
    const val VALID_YEARS = 30
    const val MIN_PASSWORD = 6

    /**
     * Writes a new keystore at [file] holding one key, [alias], protected by [password]. Never
     * overwrites: an existing file is an error, so a key in use can't be lost. Slow (a 4096-bit key
     * takes a few seconds on a phone), so call it off the main thread.
     */
    fun create(file: File, alias: String, password: String, owner: Owner, years: Int = VALID_YEARS): X509Certificate {
        require(alias.isNotBlank()) { "The key needs a name (alias)." }
        require(password.length >= MIN_PASSWORD) { "The password needs at least $MIN_PASSWORD characters." }
        require(owner.name.isNotBlank()) { "Put your name or your organization's name on the key." }
        require(!file.exists()) { "${file.name} already exists. Pick another name, so the key you have isn't replaced." }

        val random = SecureRandom()
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS, random) }.generateKeyPair()
        val subject = X500NameBuilder(BCStyle.INSTANCE).apply {
            addRDN(BCStyle.CN, owner.name.trim())
            owner.organization.trim().takeIf { it.isNotEmpty() }?.let { addRDN(BCStyle.O, it) }
            owner.city.trim().takeIf { it.isNotEmpty() }?.let { addRDN(BCStyle.L, it) }
            owner.country.trim().uppercase().takeIf { it.length == 2 }?.let { addRDN(BCStyle.C, it) }
        }.build()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + years * 365L * 24 * 60 * 60 * 1000)
        val holder = JcaX509v3CertificateBuilder(
            subject, BigInteger(64, random).abs(), notBefore, notAfter, subject, keys.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)

        val pass = password.toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, pass)
            setKeyEntry(alias.trim(), keys.private, pass, arrayOf(cert))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { store.store(it, pass) }
        check(tmp.renameTo(file)) { "Couldn't save ${file.name}" }
        return cert
    }

    /** A file name for a key from what the user typed: letters, digits, '-' and '_'. */
    fun fileName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifEmpty { "release" } + ".jks"
}
