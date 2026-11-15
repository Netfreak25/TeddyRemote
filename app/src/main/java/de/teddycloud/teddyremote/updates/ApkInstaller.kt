package de.teddycloud.teddyremote.updates

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import de.teddycloud.teddyremote.TeddyRemoteApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Installs only after an explicit button press; Android must request the user's confirmation. */
internal class ApkInstaller(private val context: Context) {
    private val installer = context.packageManager.packageInstaller
    private val preferences = context.getSharedPreferences("update-install", Context.MODE_PRIVATE)
    @Volatile private var sessionId = preferences.getInt(SESSION_KEY, NO_SESSION)
    private val _status = MutableStateFlow(InstallStatus())
    val status = _status.asStateFlow()
    private val _confirmation = MutableStateFlow<Intent?>(null)
    val confirmation = _confirmation.asStateFlow()

    init {
        if (sessionId != NO_SESSION) {
            if (installer.getSessionInfo(sessionId) != null) {
                _status.value = InstallStatus(true, "Android-Installation läuft oder wartet auf Bestätigung.")
            } else clearSession()
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    @SuppressLint("UseKtx") // KTX edit discards commit()'s result; persistence must succeed before commit.
    suspend fun start(file: File) = withContext(Dispatchers.IO) {
        check(canInstall()) { "Die Installationsfreigabe fehlt." }
        check(sessionId == NO_SESSION) { "Es läuft bereits eine Installation." }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(UpdatePolicy.PACKAGE_NAME)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        sessionId = id
        try {
            check(preferences.edit().putInt(SESSION_KEY, id).commit()) { "Installationsstatus konnte nicht gespeichert werden." }
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { input ->
                        val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(bytes)
                            if (count == -1) break
                            output.write(bytes, 0, count)
                        }
                    }
                    session.fsync(output)
                }
                coroutineContext.ensureActive()
                val callback = Intent(context, UpdateInstallReceiver::class.java).setAction(RESULT_ACTION)
                // PackageInstaller adds status extras; immutable PendingIntents are rejected on API 35+.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val sender = PendingIntent.getBroadcast(context, id, callback, flags)
                _status.value = InstallStatus(true, "Android-Installationsdialog wird vorbereitet …")
                session.commit(sender.intentSender)
            }
        } catch (error: Throwable) {
            try { installer.abandonSession(id) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            if (sessionId == id) {
                clearSession()
                _status.value = InstallStatus(message = error.message ?: "Installation konnte nicht vorbereitet werden.")
            }
            throw error
        }
    }

    fun receive(intent: Intent) {
        if (intent.action != RESULT_ACTION || sessionId == NO_SESSION ||
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION) != sessionId
        ) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmation == null) { fail("Android hat keinen Bestätigungsdialog bereitgestellt."); return }
                _status.value = InstallStatus(true, "Bitte die Installation im Android-Dialog bestätigen.")
                _confirmation.value = confirmation
            }
            PackageInstaller.STATUS_SUCCESS -> {
                clearSession()
                _status.value = InstallStatus(message = "Update installiert. App erneut öffnen.", succeeded = true)
            }
            else -> {
                val message = when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation abgebrochen. Die bisherige App bleibt erhalten."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Nicht genügend Speicher für die Installation."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android hat die Installation blockiert. Bitte die Systemhinweise beachten."
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Die APK passt nicht zur installierten App."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Diese APK ist nicht mit dem Gerät kompatibel."
                    else -> "Android konnte das Update nicht installieren. Die bisherige App bleibt erhalten."
                }
                clearSession()
                _status.value = InstallStatus(message = message)
            }
        }
    }

    fun confirmationShown() { _confirmation.value = null }

    fun cancel() = fail("Installation abgebrochen. Die bisherige App bleibt erhalten.")

    fun fail(message: String) {
        var detail = message
        try {
            if (sessionId != NO_SESSION && installer.getSessionInfo(sessionId) != null) installer.abandonSession(sessionId)
        } catch (error: Exception) { detail += " Android-Sitzung konnte nicht beendet werden: ${error.message}" }
        clearSession()
        _status.value = InstallStatus(message = detail)
    }

    private fun clearSession() {
        sessionId = NO_SESSION
        preferences.edit { remove(SESSION_KEY) }
        _confirmation.value = null
    }

    companion object {
        private const val SESSION_KEY = "sessionId"
        private const val NO_SESSION = -1
        private const val RESULT_ACTION = "de.teddycloud.teddyremote.UPDATE_INSTALL_RESULT"
    }
}

/** Non-exported; only the explicit PackageInstaller PendingIntent may report installation results. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as TeddyRemoteApplication).container.apkInstaller.receive(intent)
    }
}
