package de.teddycloud.teddyremote.updates

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import de.teddycloud.teddyremote.BuildConfig
import de.teddycloud.teddyremote.repository.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class AndroidUpdateOperations(
    private val context: Context,
    private val network: NetworkMonitor,
    private val installer: ApkInstaller,
) : UpdateOperations {
    private val client = GitHubUpdateClient(::wifiHttpClient, File(context.cacheDir, "updates"))

    override suspend fun check(): AppRelease? {
        requireReleaseApp()
        return client.latest(BuildConfig.VERSION_NAME)
    }

    override suspend fun download(release: AppRelease, progress: (Int) -> Unit): File {
        requireReleaseApp()
        val apk = client.download(release, progress)
        try { verify(apk, release); return apk }
        catch (error: Throwable) { apk.delete(); throw error }
    }

    override suspend fun install(file: File, release: AppRelease) {
        requireReleaseApp()
        verify(file, release)
        installer.start(file)
    }

    override fun canInstall() = installer.canInstall()
    override fun cancelNetwork() = client.cancel()
    override fun cancelInstallation() = installer.cancel()

    private fun requireReleaseApp() {
        check(!BuildConfig.DEBUG && context.packageName == UpdatePolicy.PACKAGE_NAME) { "Updates sind nur in der Release-App verfügbar." }
    }

    /** Uses normal platform TLS trust, not the profile's self-signed TC pins or credentials. */
    private fun wifiHttpClient(): OkHttpClient {
        network.refresh()
        val wifi = network.activeWifiNetwork() ?: error("Kein WLAN. Updates werden nicht über Mobilfunk geladen.")
        return OkHttpClient.Builder()
            .socketFactory(wifi.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = wifi.getAllByName(hostname).toList()
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(false)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                if (url.scheme != "https" || !(url.host == "github.com" || url.host == "api.github.com" || url.host.endsWith(".githubusercontent.com"))) {
                    throw IOException("Unsichere oder fremde Update-Downloadadresse abgelehnt.")
                }
                chain.proceed(chain.request())
            }
            .build()
    }

    @Suppress("DEPRECATION")
    private suspend fun verify(apk: File, release: AppRelease) = withContext(Dispatchers.IO) {
        require(apk.isFile && apk.length() == release.size) { "Die APK-Datei fehlt oder ist unvollständig." }
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val candidate = requireNotNull(manager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)) {
            "Die heruntergeladene Datei ist keine lesbare APK."
        }
        UpdatePolicy.validateApk(identity(installed), identity(candidate), release, Build.VERSION.SDK_INT)
    }

    private fun identity(info: PackageInfo): ApkIdentity {
        val application = requireNotNull(info.applicationInfo) { "APK-Anwendungsinformationen fehlen." }
        val signers = info.signingInfo?.apkContentsSigners.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
        }.toSet()
        return ApkIdentity(info.packageName, info.longVersionCode, info.versionName,
            application.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, signers, application.minSdkVersion)
    }
}
