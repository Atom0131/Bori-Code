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
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
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
        val ksPassword = config.keystorePassword.toCharArray()
        val keyPassword = config.keyPassword.toCharArray()
        val format = when (file.extension.lowercase()) {
            "p12", "pfx" -> "PKCS12"
            "jks" -> "JKS"
            else -> "BKS"
        }
        val ks = KeyStore.getInstance(format, if (format == "BKS") "BC" else KeyStore.getDefaultType())
        file.inputStream().use { ks.load(it, ksPassword) }
        val privateKey = ks.getKey(config.keyAlias, keyPassword) as PrivateKey
        val cert = ks.getCertificate(config.keyAlias) as X509Certificate
        return privateKey to cert
    }

    /** Returns the list of key aliases in the keystore at [keystorePath]. */
    fun listAliases(config: KeystoreConfig): List<String> {
        val file = File(config.keystorePath)
        require(file.exists()) { "Keystore not found: ${config.keystorePath}" }
        val ksPassword = config.keystorePassword.toCharArray()
        val format = when (file.extension.lowercase()) {
            "p12", "pfx" -> "PKCS12"
            "jks" -> "JKS"
            else -> "BKS"
        }
        val ks = KeyStore.getInstance(format, if (format == "BKS") "BC" else KeyStore.getDefaultType())
        file.inputStream().use { ks.load(it, ksPassword) }
        return ks.aliases().toList()
    }

    fun sign(
        unsignedApk: File,
        signedApk: File,
        emit: (String) -> Unit,
        keystoreConfig: KeystoreConfig? = null,
        variant: BuildVariant = BuildVariant.DEBUG,
    ) {
        if (variant == BuildVariant.RELEASE && keystoreConfig == null) {
            throw RuntimeException("Release build requires a custom keystore — configure one in Settings → Release Signing")
        }
        val (privateKey, cert) = if (keystoreConfig != null) {
            emit("Loading custom keystore: ${keystoreConfig.keystorePath}")
            loadCustomKeyPair(keystoreConfig)
        } else {
            emit("Generating/loading debug keystore")
            getOrCreateKeyPair()
        }


        val tempV1 = File(signedApk.parentFile, "temp_v1.apk")
        try {
            emit("Signing APK (v1)")
            signV1(unsignedApk, tempV1, privateKey, cert)
            emit("Signing APK (v2)")
            signV2(tempV1, signedApk, privateKey, cert)
        } finally {
            tempV1.delete()
        }

        emit("Signed APK: ${signedApk.length() / 1024} KB")
    }

    private fun signV1(
        unsignedApk: File,
        outputApk: File,
        privateKey: PrivateKey,
        cert: X509Certificate,
    ) {
        data class Entry(val name: String, val bytes: ByteArray, val method: Int)
        val entries = mutableListOf<Entry>()
        ZipInputStream(unsignedApk.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.name.startsWith("META-INF/")) {
                    entries.add(Entry(entry.name, zin.readBytes(), entry.method))
                }
                entry = zin.nextEntry
            }
        }

        val sha1 = MessageDigest.getInstance("SHA-1")
        fun digest(b: ByteArray) = Base64.getEncoder().encodeToString(sha1.digest(b))

        val manifestSb = StringBuilder()
        manifestSb.appendLine("Manifest-Version: 1.0")
        manifestSb.appendLine("Created-By: Codebot")
        manifestSb.appendLine()
        for (e in entries) {
            manifestSb.appendLine("Name: ${e.name}")
            manifestSb.appendLine("SHA1-Digest: ${digest(e.bytes)}")
            manifestSb.appendLine()
        }
        val manifestBytes = manifestSb.toString().toByteArray(Charsets.UTF_8)

        val sfSb = StringBuilder()
        sfSb.appendLine("Signature-Version: 1.0")
        sfSb.appendLine("SHA1-Digest-Manifest: ${digest(manifestBytes)}")
        sfSb.appendLine("Created-By: Codebot")
        sfSb.appendLine()
        val manifestLines = manifestSb.toString().lines()
        var i = 0
        while (i < manifestLines.size) {
            if (manifestLines[i].startsWith("Name: ")) {
                val section = mutableListOf<String>()
                while (i < manifestLines.size && manifestLines[i].isNotEmpty()) {
                    section.add(manifestLines[i++])
                }
                val sectionBytes = (section.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8)
                sfSb.appendLine(section[0])
                sfSb.appendLine("SHA1-Digest: ${digest(sectionBytes)}")
                sfSb.appendLine()
            } else {
                i++
            }
        }
        val sfBytes = sfSb.toString().toByteArray(Charsets.UTF_8)

        val gen = CMSSignedDataGenerator()
        val contentSigner = JcaContentSignerBuilder("SHA1withRSA").build(privateKey)
        val digestProvider = JcaDigestCalculatorProviderBuilder().build()
        gen.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(digestProvider).build(contentSigner, cert))
        gen.addCertificates(org.bouncycastle.cert.jcajce.JcaCertStore(listOf(cert)))
        val certRsaBytes = gen.generate(CMSProcessableByteArray(sfBytes), false).encoded

        val zipEntries = entries.map { ZipEntryData(it.name, it.bytes, it.method) } + listOf(
            ZipEntryData("META-INF/MANIFEST.MF", manifestBytes, ZipEntry.DEFLATED),
            ZipEntryData("META-INF/CERT.SF", sfBytes, ZipEntry.DEFLATED),
            ZipEntryData("META-INF/CERT.RSA", certRsaBytes, ZipEntry.DEFLATED),
        )
        writeAlignedZip(outputApk, zipEntries)
    }

    private fun signV2(inputApk: File, outputApk: File, privateKey: PrivateKey, cert: X509Certificate) {
        val apkBytes = inputApk.readBytes()
        val eocdOffset = findEocdOffset(apkBytes)
        val cdOffset = ByteBuffer.wrap(apkBytes, eocdOffset + 16, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int

        val section1 = apkBytes.copyOfRange(0, cdOffset)
        val section2 = apkBytes.copyOfRange(cdOffset, eocdOffset)
        val eocdBase = apkBytes.copyOfRange(eocdOffset, apkBytes.size)

        val certDer = cert.encoded
        val pubKeyDer = cert.publicKey.encoded

        // Per spec: EOCD's CD-offset field must equal the signing block start for digest
        // computation. The signing block is inserted at cdOffset, so eocdBase already
        // has the correct value (cdOffset). No modification needed for the digest.
        val contentDigest = computeApkContentDigest(section1, section2, eocdBase)
        val signingBlock = buildApkSigningBlock(contentDigest, certDer, pubKeyDer, privateKey)

        // The output EOCD needs the real (post-insertion) CD offset
        val eocdFinal = eocdBase.copyOf()
        ByteBuffer.wrap(eocdFinal, 16, 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cdOffset + signingBlock.size)

        outputApk.outputStream().buffered().use { out ->
            out.write(section1)
            out.write(signingBlock)
            out.write(section2)
            out.write(eocdFinal)
        }
    }

    private fun computeApkContentDigest(s1: ByteArray, s2: ByteArray, s3: ByteArray): ByteArray {
        val chunkSize = 1 shl 20
        val sha256 = MessageDigest.getInstance("SHA-256")
        val chunkDigests = ByteArrayOutputStream()
        var totalChunks = 0

        for (section in listOf(s1, s2, s3)) {
            var offset = 0
            while (offset < section.size) {
                val len = minOf(chunkSize, section.size - offset)
                sha256.reset()
                sha256.update(0xa5.toByte())
                sha256.update(leInt(len))
                sha256.update(section, offset, len)
                chunkDigests.write(sha256.digest())
                totalChunks++
                offset += len
            }
        }

        sha256.reset()
        sha256.update(0x5a.toByte())
        sha256.update(leInt(totalChunks))
        sha256.update(chunkDigests.toByteArray())
        return sha256.digest()
    }

    private fun buildApkSigningBlock(
        contentDigest: ByteArray,
        certDer: ByteArray,
        pubKeyDer: ByteArray,
        privateKey: PrivateKey,
    ): ByteArray {
        val algId = 0x0103  // RSASSA-PKCS1-v1_5 with SHA2-256

        // SignedData
        val digestItem = concat(leInt(algId), leInt(contentDigest.size), contentDigest)
        val digestItemWrapped = concat(leInt(digestItem.size), digestItem)
        val digestsSeq = concat(leInt(digestItemWrapped.size), digestItemWrapped)
        val certWrapped = concat(leInt(certDer.size), certDer)
        val certsSeq = concat(leInt(certWrapped.size), certWrapped)
        val attrsSeq = leInt(0)
        val signedData = concat(digestsSeq, certsSeq, attrsSeq)

        // Signature
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(signedData)
        val sigValue = sig.sign()  // always 256 bytes for RSA-2048

        // Signatures sequence
        val sigItem = concat(leInt(algId), leInt(sigValue.size), sigValue)
        val sigItemWrapped = concat(leInt(sigItem.size), sigItem)
        val sigsSeq = concat(leInt(sigItemWrapped.size), sigItemWrapped)

        // Signer block
        val signerBlock = concat(leInt(signedData.size), signedData, sigsSeq, leInt(pubKeyDer.size), pubKeyDer)
        val signerWrapped = concat(leInt(signerBlock.size), signerBlock)
        val v2SchemeBlock = concat(leInt(signerWrapped.size), signerWrapped)

        // APK Signing Block
        val pairContentSize = (4 + v2SchemeBlock.size).toLong()
        val blockSizeValue = (8 + 4 + v2SchemeBlock.size + 8 + 16).toLong()

        val out = ByteArrayOutputStream()
        out.write(leLong(blockSizeValue))
        out.write(leLong(pairContentSize))
        out.write(leInt(0x7109871a))
        out.write(v2SchemeBlock)
        out.write(leLong(blockSizeValue))
        out.write("APK Sig Block 42".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun findEocdOffset(apkBytes: ByteArray): Int {
        val offset = apkBytes.size - 22
        check(
            apkBytes[offset] == 0x50.toByte() &&
                apkBytes[offset + 1] == 0x4b.toByte() &&
                apkBytes[offset + 2] == 0x05.toByte() &&
                apkBytes[offset + 3] == 0x06.toByte(),
        ) { "EOCD not found" }
        return offset
    }

    private fun leInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun leLong(v: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}
