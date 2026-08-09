package de.teddycloud.teddyremote.network

import android.annotation.SuppressLint
import de.teddycloud.teddyremote.model.CertificateCandidate
import de.teddycloud.teddyremote.model.CertificateTarget
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal fun X509Certificate.sha256Fingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(":") { "%02X".format(it) }

internal fun fingerprintMatchesPin(expected: String?, actual: String): Boolean {
    if (expected.isNullOrBlank()) return false
    return expected.replace(":", "").equals(actual.replace(":", ""), ignoreCase = true)
}

@SuppressLint("CustomX509TrustManager")
internal class ProfileTrustManager(pinnedFingerprint: String?) : X509TrustManager {
    private val systemTrustManager: X509TrustManager = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as java.security.KeyStore?) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .single()

    private val normalizedPin = pinnedFingerprint?.replace(":", "")?.uppercase()

    override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        systemTrustManager.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Server lieferte kein Zertifikat")

        // Once a profile has a confirmed pin, it is authoritative even if a replacement
        // certificate would be accepted by Android's system trust store.
        if (normalizedPin != null) {
            if (!fingerprintMatchesPin(normalizedPin, leaf.sha256Fingerprint())) {
                throw CertificateException("Unbekanntes oder geändertes Serverzertifikat")
            }
            leaf.checkValidity()
            return
        }

        systemTrustManager.checkServerTrusted(chain, authType)
    }
}

internal data class TlsMaterial(
    val sslContext: SSLContext,
    val trustManager: X509TrustManager,
)

internal fun createTlsMaterial(pinnedFingerprint: String?): TlsMaterial {
    val trustManager = ProfileTrustManager(pinnedFingerprint)
    val context = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }
    return TlsMaterial(context, trustManager)
}

/**
 * Reads certificate metadata without sending application data. The result must still be explicitly
 * confirmed before a normal API or MQTT connection is attempted with that certificate.
 */
@SuppressLint("CustomX509TrustManager")
class CertificateProbe {
    suspend fun inspect(
        target: CertificateTarget,
        host: String,
        port: Int,
        timeoutMillis: Int = 5_000,
    ): CertificateCandidate = inspectCertificate(target, host, port, timeoutMillis).candidate

    internal suspend fun inspectCertificate(
        target: CertificateTarget,
        host: String,
        port: Int,
        timeoutMillis: Int = 5_000,
    ): ProbedCertificate = kotlinx.coroutines.Dispatchers.IO.let { dispatcher ->
        kotlinx.coroutines.withContext(dispatcher) {
            val trustAll = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustAll), SecureRandom())
            }
            Socket().use { plain ->
                plain.connect(InetSocketAddress(host, port), timeoutMillis)
                plain.soTimeout = timeoutMillis
                (sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket).use { socket ->
                    socket.startHandshake()
                    val certificate = socket.session.peerCertificates.first() as X509Certificate
                    ProbedCertificate(
                        candidate = CertificateCandidate(
                            target = target,
                            host = host,
                            port = port,
                            subject = certificate.subjectX500Principal.name,
                            issuer = certificate.issuerX500Principal.name,
                            fingerprintSha256 = certificate.sha256Fingerprint(),
                        ),
                        certificate = certificate,
                    )
                }
            }
        }
    }
}

internal data class ProbedCertificate(
    val candidate: CertificateCandidate,
    val certificate: X509Certificate,
)

internal fun trustManagerFactoryFor(certificate: X509Certificate): TrustManagerFactory {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setCertificateEntry("teddyremote-pinned-server", certificate)
    }
    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(keyStore)
    }
}
