package de.teddycloud.teddyremote.mqtt

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth
import com.hivemq.client.mqtt.mqtt3.message.subscribe.Mqtt3Subscribe
import de.teddycloud.teddyremote.model.CertificateCandidate
import de.teddycloud.teddyremote.model.CertificateTarget
import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.MqttBoxEvent
import de.teddycloud.teddyremote.network.CertificateProbe
import de.teddycloud.teddyremote.network.createTlsMaterial
import de.teddycloud.teddyremote.network.trustManagerFactoryFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CertificateConfirmationRequired(val candidate: CertificateCandidate) : Exception(
    "Zertifikat für ${candidate.host}:${candidate.port} muss bestätigt werden",
)

class MqttConnection(
    private val certificateProbe: CertificateProbe = CertificateProbe(),
) {
    @Volatile
    private var client: Mqtt3AsyncClient? = null
    private val connectionEpoch = AtomicLong()

    suspend fun connect(
        profile: ConnectionProfile,
        password: String?,
        onEvent: (MqttBoxEvent) -> Unit,
        onDisconnected: (Throwable?) -> Unit,
    ) {
        disconnect()
        val epoch = connectionEpoch.incrementAndGet()
        var callbackClient: Mqtt3AsyncClient? = null
        var builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(profile.mqttClientId)
            .serverHost(profile.mqttHost)
            .serverPort(profile.mqttPort)
            .addDisconnectedListener { context ->
                if (connectionEpoch.get() == epoch && client === callbackClient) {
                    runCatching { onDisconnected(context.cause) }
                        .onFailure { Log.w(LOG_TAG, "MQTT disconnect callback failed", it) }
                }
            }

        if (profile.mqttTls) {
            builder = builder.sslConfig(
                MqttClientSslConfig.builder()
                    .trustManagerFactory(resolveMqttTrust(profile))
                    .hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())
                    .handshakeTimeout(8, TimeUnit.SECONDS)
                    .build(),
            )
        }

        val mqttClient = builder.buildAsync()
        callbackClient = mqttClient
        client = mqttClient
        val connect = mqttClient.connectWith().cleanSession(true).keepAlive(30)
        val configuredConnect = if (profile.mqttUsername.isNotBlank()) {
            val authBuilder = Mqtt3SimpleAuth.builder().username(profile.mqttUsername)
            val auth = if (password.isNullOrEmpty()) authBuilder.build()
            else authBuilder.password(password.encodeToByteArray()).build()
            connect.simpleAuth(auth)
        } else connect
        configuredConnect.send().await()

        val prefix = profile.mqttPrefix.trim('/')
        subscribe(mqttClient, "$prefix/status", profile.mqttPrefix, onEvent)
        subscribe(mqttClient, "$prefix/box/+/+", profile.mqttPrefix, onEvent)
    }

    suspend fun disconnect() {
        connectionEpoch.incrementAndGet()
        val current = client
        client = null
        if (current != null && current.state.isConnected) {
            runCatching { current.disconnect().await() }
        }
    }

    private suspend fun subscribe(
        client: Mqtt3AsyncClient,
        topic: String,
        prefix: String,
        onEvent: (MqttBoxEvent) -> Unit,
    ) {
        val subscription = Mqtt3Subscribe.builder()
            .topicFilter(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .build()
        client.subscribe(subscription) { publish ->
            runCatching {
                MqttTopicParser.parse(prefix, publish.topic.toString(), publish.payloadAsBytes)?.let(onEvent)
            }.onFailure { Log.w(LOG_TAG, "MQTT publish callback failed for ${publish.topic}", it) }
        }.await()
    }

    private suspend fun resolveMqttTrust(profile: ConnectionProfile): TrustManagerFactory {
        val pin = profile.mqttCertificateFingerprint?.replace(":", "")?.uppercase()
        if (pin == null && systemTlsHandshakeSucceeds(profile.mqttHost, profile.mqttPort)) {
            return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as java.security.KeyStore?)
            }
        }

        val probed = certificateProbe.inspectCertificate(
            CertificateTarget.MQTT,
            profile.mqttHost,
            profile.mqttPort,
        )
        val actual = probed.candidate.fingerprintSha256.replace(":", "")
        if (pin == null || !pin.equals(actual, ignoreCase = true)) {
            throw CertificateConfirmationRequired(probed.candidate)
        }
        return trustManagerFactoryFor(probed.certificate)
    }

    private suspend fun systemTlsHandshakeSucceeds(host: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val tls = createTlsMaterial(null)
                Socket().use { plain ->
                    plain.connect(InetSocketAddress(host, port), 5_000)
                    plain.soTimeout = 5_000
                    (tls.sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket).use { socket ->
                        socket.sslParameters = socket.sslParameters.apply {
                            endpointIdentificationAlgorithm = "HTTPS"
                        }
                        socket.startHandshake()
                    }
                }
            }.isSuccess
        }

    private companion object {
        const val LOG_TAG = "MqttConnection"
    }
}

private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { result, error ->
        if (error == null) continuation.resume(result)
        else continuation.resumeWithException(error.cause ?: error)
    }
    continuation.invokeOnCancellation { cancel(true) }
}
