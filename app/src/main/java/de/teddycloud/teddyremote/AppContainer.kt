package de.teddycloud.teddyremote

import android.content.Context
import de.teddycloud.teddyremote.data.ProfilesStore
import de.teddycloud.teddyremote.data.SecretStore
import de.teddycloud.teddyremote.repository.TeddyRemoteRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val secretStore = SecretStore(appContext)
    val profilesStore = ProfilesStore(appContext, secretStore)
    val repository = TeddyRemoteRepository(appContext, profilesStore)
}
