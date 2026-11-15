package de.teddycloud.teddyremote

import android.content.Context
import de.teddycloud.teddyremote.data.PlaybackHistoryStore
import de.teddycloud.teddyremote.data.ProfilesStore
import de.teddycloud.teddyremote.data.SecretStore
import de.teddycloud.teddyremote.repository.TeddyRemoteRepository
import de.teddycloud.teddyremote.repository.NetworkMonitor
import de.teddycloud.teddyremote.updates.ApkInstaller
import de.teddycloud.teddyremote.updates.AndroidUpdateOperations

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val secretStore = SecretStore(appContext)
    val profilesStore = ProfilesStore(appContext, secretStore)
    val playbackHistoryStore = PlaybackHistoryStore(appContext)
    val networkMonitor = NetworkMonitor(appContext)
    val repository = TeddyRemoteRepository(appContext, profilesStore, networkMonitor = networkMonitor, playbackHistoryStore = playbackHistoryStore)
    internal val apkInstaller = ApkInstaller(appContext)
    internal val updateOperations = AndroidUpdateOperations(appContext, networkMonitor, apkInstaller)
}
