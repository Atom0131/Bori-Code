package com.bori.core.build.steps

import android.content.Context
import com.bori.core.build.model.BuildVariant
import com.bori.core.build.model.KeystoreConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkSigner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keystoreFile = File(context.filesDir, "debug.keystore")
    private val keyAlias = "debugkey"
    private val keystorePassword = "android".toCharArray()

    private fun getOrCreateKeyPair(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("BKS", "BC")
        if (keystoreFile.exists()) {
            keystoreFile.inputStream().use { ks.load(it, keystorePassword) }
        } else {
            ks.load(null, keystorePassword)
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val kp = kpg.generateKeyPair()
            val subject = X500Name("CN=Android Debug,O=Android,C=US")
            val now = Date()
            val notAfter = Date(now.time + 30L * 365 * 24 * 60 * 60 * 1000)
            val certBuilder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(SecureRandom().nextLong().and(Long.MAX_VALUE)),
                now, notAfter, subject, kp.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
            ks.setKeyEntry(keyAlias, kp.private, keystorePassword, arrayOf(cert))
            keystoreFile.outputStream().use { ks.store(it, keystorePassword) }
        }
        val privateKey = ks.getKey(keyAlias, keystorePassword) as PrivateKey
        val cert = ks.getCertificate(keyAlias) as X509Certificate
        return privateKey to cert
    }

    private fun loadCustomKeyPair(config: KeystoreConfig): Pair<PrivateKey, X509Certificate> {
        val file = File(config.keystorePath)
        require(file.exists()) { "Keystore not found: ${config.keystorePath}" }
        val bytes = file.readBytes()
        val ksPassword = config.keystorePassword.toCharArray()
        val keyPassword = config.keyPassword.toCharArray()
        if (JksReader.isJks(bytes)) {
            val entry = JksReader.read(bytes, ksPassword)[config.keyAlias]
            val protectedKey = entry?.protectedKey ?: throw IllegalArgumentException("No key named \"${config.keyAlias}\" in the keystore")
            return JksReader.decryptKey(protectedKey, keyPassword) to entry.chain.first()
        }
        val ks = loadKeyStore(bytes, ksPassword)
        val privateKey = ks.getKey(config.keyAlias, keyPassword) as? PrivateKey
            ?: throw IllegalArgumentException("No key named \"${config.keyAlias}\" in the keystore")
        val cert = ks.getCertificate(config.keyAlias) as X509Certificate
        return privateKey to cert
    }

    /** Returns the list of key aliases in the keystore at [keystorePath]. */
    fun listAliases(config: KeystoreConfig): List<String> {
        val file = File(config.keystorePath)
        require(file.exists()) { "Keystore not found: ${config.keystorePath}" }
        val bytes = file.readBytes()
        val ksPassword = config.keystorePassword.toCharArray()
        if (JksReader.isJks(bytes)) return JksReader.read(bytes, ksPassword).filterValues { it.protectedKey != null }.keys.toList()
        return loadKeyStore(bytes, ksPassword).aliases().toList()
    }

    /**
     * Opens a PKCS12 or Bouncy Castle (BKS) keystore, whatever its file extension says: keytool
     * has written PKCS12 by default since Java 9, even into files named .jks. JKS itself goes
     * through [JksReader].
     */
    private fun loadKeyStore(bytes: ByteArray, password: CharArray): KeyStore {
        var failure: Exception? = null
        for (type in listOf("PKCS12", "BKS")) {
            try {
                val ks = if (type == "BKS") KeyStore.getInstance(type, "BC") else KeyStore.getInstance(type)
                ks.load(bytes.inputStream(), password)
                return ks
            } catch (e: Exception) {
                failure = failure ?: e
            }
        }
        throw IllegalArgumentException("Couldn't open the keystore (wrong password, or not a PKCS12, JKS or BKS file): ${failure?.message}", failure)
    }

    /**
     * Signs [unsignedApk] into [signedApk] with apksig: v1 (JAR) when [minSdk] is below 24, plus
     * v2 and v3, with RSA, EC or DSA keys. Entries keep the 4-byte / 16 KB alignment the
     * packager gave them. A release needs [keystoreConfig]; debug builds use the app's own debug key.
     */
    fun sign(
        unsignedApk: File,
        signedApk: File,
        emit: (String) -> Unit,
        keystoreConfig: KeystoreConfig? = null,
        variant: BuildVariant = BuildVariant.DEBUG,
        minSdk: Int = 21,
    ) {
        val (privateKey, cert) = keyPair(keystoreConfig, variant, emit)
        val signer = com.android.apksig.ApkSigner.SignerConfig.Builder("CERT", privateKey, listOf(cert)).build()
        val v1 = minSdk < 24
        emit("Signing APK (${if (v1) "v1+" else ""}v2+v3, ${privateKey.algorithm})")
        signedApk.delete()
        com.android.apksig.ApkSigner.Builder(listOf(signer))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(v1)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setDebuggableApkPermitted(true)
            .setAlignmentPreserved(true)
            .setCreatedBy("Bori Code")
            .build()
            .sign()
        emit("Signed APK: ${signedApk.length() / 1024} KB")
    }

    /**
     * JAR-signs an Android App Bundle the way jarsigner does (SHA-256 digests), which is what
     * Google Play checks on upload: the upload key's signature. Play re-signs the APKs it makes.
     */
    fun signBundle(
        unsignedAab: File,
        signedAab: File,
        emit: (String) -> Unit,
        keystoreConfig: KeystoreConfig?,
    ) {
        val (privateKey, cert) = keyPair(keystoreConfig, BuildVariant.RELEASE, emit)
        emit("Signing app bundle (JAR, SHA-256, ${privateKey.algorithm})")
        signJar(unsignedAab, signedAab, privateKey, cert)
        emit("Signed app bundle: ${signedAab.length() / 1024} KB")
    }

    private fun keyPair(keystoreConfig: KeystoreConfig?, variant: BuildVariant, emit: (String) -> Unit): Pair<PrivateKey, X509Certificate> {
        if (variant == BuildVariant.RELEASE && keystoreConfig == null) {
            throw RuntimeException("Release build requires a custom keystore — configure one in Settings → Release Signing")
        }
        return if (keystoreConfig != null) {
            emit("Loading custom keystore: ${keystoreConfig.keystorePath}")
            loadCustomKeyPair(keystoreConfig)
        } else {
            emit("Generating/loading debug keystore")
            getOrCreateKeyPair()
        }
    }

    /** v1 JAR signature over every entry (old META-INF signatures dropped), with CRLF manifests as the JAR spec wants. */
    private fun signJar(input: File, output: File, privateKey: PrivateKey, cert: X509Certificate) {
        data class Entry(val name: String, val bytes: ByteArray, val method: Int)
        val entries = mutableListOf<Entry>()
        ZipInputStream(input.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val upper = entry.name.uppercase()
                val signatureFile = upper.startsWith("META-INF/") && (upper == "META-INF/MANIFEST.MF" ||
                    upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))
                if (!entry.isDirectory && !signatureFile) entries.add(Entry(entry.name, zin.readBytes(), entry.method))
                entry = zin.nextEntry
            }
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
        fun digest(b: ByteArray) = Base64.getEncoder().encodeToString(sha256.digest(b))

        val manifestMain = "Manifest-Version: 1.0\r\nCreated-By: Bori Code\r\n\r\n"
        val sections = entries.map { e -> manifestLine("Name: ${e.name}") + manifestLine("SHA-256-Digest: ${digest(e.bytes)}") + "\r\n" }
        val manifestBytes = (manifestMain + sections.joinToString("")).toByteArray(Charsets.UTF_8)

        val sf = StringBuilder()
        sf.append("Signature-Version: 1.0\r\n")
        sf.append("Created-By: Bori Code\r\n")
        sf.append(manifestLine("SHA-256-Digest-Manifest: ${digest(manifestBytes)}"))
        sf.append(manifestLine("SHA-256-Digest-Manifest-Main-Attributes: ${digest(manifestMain.toByteArray(Charsets.UTF_8))}"))
        sf.append("\r\n")
        entries.forEachIndexed { i, e ->
            sf.append(manifestLine("Name: ${e.name}"))
            sf.append(manifestLine("SHA-256-Digest: ${digest(sections[i].toByteArray(Charsets.UTF_8))}"))
            sf.append("\r\n")
        }
        val sfBytes = sf.toString().toByteArray(Charsets.UTF_8)

        val keyAlgorithm = when (privateKey.algorithm) { "EC" -> "ECDSA"; else -> privateKey.algorithm }
        val gen = CMSSignedDataGenerator()
        val contentSigner = JcaContentSignerBuilder("SHA256with$keyAlgorithm").build(privateKey)
        val digestProvider = JcaDigestCalculatorProviderBuilder().build()
        gen.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(digestProvider).build(contentSigner, cert))
        gen.addCertificates(org.bouncycastle.cert.jcajce.JcaCertStore(listOf(cert)))
        val blockBytes = gen.generate(CMSProcessableByteArray(sfBytes), false).encoded
        val blockName = when (privateKey.algorithm) { "EC" -> "EC"; "DSA" -> "DSA"; else -> "RSA" }

        val zipEntries = listOf(
            ZipEntryData("META-INF/MANIFEST.MF", manifestBytes, ZipEntry.DEFLATED),
            ZipEntryData("META-INF/UPLOAD.SF", sfBytes, ZipEntry.DEFLATED),
            ZipEntryData("META-INF/UPLOAD.$blockName", blockBytes, ZipEntry.DEFLATED),
        ) + entries.map { ZipEntryData(it.name, it.bytes, it.method) }
        writeAlignedZip(output, zipEntries)
    }

    /** One manifest header line, wrapped at 72 bytes with continuation lines that start with a space. */
    private fun manifestLine(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 70) return line + "\r\n"
        val out = StringBuilder()
        var i = 0
        var first = true
        while (i < line.length) {
            val limit = if (first) 70 else 69
            var j = i
            var size = 0
            while (j < line.length) {
                val charBytes = line.substring(j, j + 1).toByteArray(Charsets.UTF_8).size
                if (size + charBytes > limit) break
                size += charBytes
                j++
            }
            if (!first) out.append(' ')
            out.append(line, i, j).append("\r\n")
            i = j
            first = false
        }
        return out.toString()
    }
}
