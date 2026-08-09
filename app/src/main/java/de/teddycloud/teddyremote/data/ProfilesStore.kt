package de.teddycloud.teddyremote.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.ProfilesState
import de.teddycloud.teddyremote.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

private val profileJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

private object ProfilesSerializer : Serializer<ProfilesState> {
    override val defaultValue: ProfilesState = ProfilesState()

    override suspend fun readFrom(input: InputStream): ProfilesState = try {
        profileJson.decodeFromString(input.readBytes().decodeToString())
    } catch (error: SerializationException) {
        throw CorruptionException("Die TeddyRemote-Profile sind beschädigt", error)
    }

    override suspend fun writeTo(t: ProfilesState, output: OutputStream) {
        output.write(profileJson.encodeToString(ProfilesState.serializer(), t).encodeToByteArray())
    }
}

/**
 * Persists profile metadata. Password material is delegated to [SecretStore] and never enters
 * DataStore or a log message.
 */
class ProfilesStore(
    context: Context,
    private val secretStore: SecretStore,
) {
    private val dataStore: DataStore<ProfilesState> = DataStoreFactory.create(
        serializer = ProfilesSerializer,
        produceFile = {
            File(context.filesDir, "datastore/teddyremote-profiles.json").also {
                it.parentFile?.mkdirs()
            }
        },
    )

    val state: Flow<ProfilesState> = dataStore.data

    suspend fun saveProfile(profile: ConnectionProfile, mqttPassword: String?): ConnectionProfile {
        val normalized = profile.normalized()
        require(normalized.validate().isEmpty()) { normalized.validate().joinToString() }
        if (mqttPassword != null) {
            if (mqttPassword.isBlank()) secretStore.remove(normalized.id)
            else secretStore.put(normalized.id, mqttPassword)
        }
        dataStore.updateData { current ->
            val exists = current.profiles.any { it.id == normalized.id }
            val profiles = if (exists) {
                current.profiles.map { if (it.id == normalized.id) normalized else it }
            } else {
                current.profiles + normalized
            }
            current.copy(
                profiles = profiles,
                activeProfileId = current.activeProfileId ?: normalized.id,
            )
        }
        return normalized
    }

    suspend fun duplicateProfile(profileId: String): ConnectionProfile {
        val current = state.first()
        val source = requireNotNull(current.profiles.firstOrNull { it.id == profileId }) {
            "Unbekanntes Profil"
        }
        val duplicate = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} Kopie",
            mqttClientId = "teddyremote-${UUID.randomUUID()}",
        )
        saveProfile(duplicate, secretStore.get(source.id))
        return duplicate
    }

    suspend fun deleteProfile(profileId: String) {
        secretStore.remove(profileId)
        dataStore.updateData { current ->
            val profiles = current.profiles.filterNot { it.id == profileId }
            current.copy(
                profiles = profiles,
                activeProfileId = if (current.activeProfileId == profileId) profiles.firstOrNull()?.id else current.activeProfileId,
                connectionRequested = if (current.activeProfileId == profileId) false else current.connectionRequested,
            )
        }
    }

    suspend fun activateProfile(profileId: String) {
        dataStore.updateData { current ->
            require(current.profiles.any { it.id == profileId }) { "Unbekanntes Profil" }
            current.copy(activeProfileId = profileId, connectionRequested = false)
        }
    }

    suspend fun setConnectionRequested(requested: Boolean) {
        dataStore.updateData { it.copy(connectionRequested = requested) }
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.updateData { it.copy(themeMode = themeMode) }
    }

    suspend fun mqttPassword(profileId: String): String? = secretStore.get(profileId)
}
