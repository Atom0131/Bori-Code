package com.bori.app.build

import com.bori.core.build.steps.KeystoreCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate

class KeystoreCreatorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun createdKeyLoadsAsPkcs12AndSigns() {
        val file = File(tmp.root, KeystoreCreator.fileName("My upload key"))
        assertEquals("My-upload-key.jks", file.name)
        KeystoreCreator.create(file, "upload", "secret123", KeystoreCreator.Owner("Ada Lopez", "Bori", country = "pr"))

        val ks = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "secret123".toCharArray()) } }
        assertEquals(listOf("upload"), ks.aliases().toList())
        val key = ks.getKey("upload", "secret123".toCharArray()) as PrivateKey
        val cert = ks.getCertificate("upload") as X509Certificate
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertTrue(cert.subjectX500Principal.name.contains("CN=Ada Lopez"))
        assertTrue(cert.subjectX500Principal.name.contains("C=PR"))
        // Play wants the certificate valid until at least late 2033.
        assertTrue(cert.notAfter.time > java.util.GregorianCalendar(2034, 0, 1).timeInMillis)
        val data = "bori".toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(data) }.sign()
        assertTrue(Signature.getInstance("SHA256withRSA").apply { initVerify(cert); update(data) }.verify(sig))
    }

    @Test fun neverOverwritesAnExistingKey() {
        val file = tmp.newFile("upload.jks").apply { writeText("keep me") }
        val e = runCatching { KeystoreCreator.create(file, "upload", "secret123", KeystoreCreator.Owner("Ada")) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertEquals("keep me", file.readText())
    }
}
