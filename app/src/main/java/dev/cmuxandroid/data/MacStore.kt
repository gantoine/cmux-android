package dev.cmuxandroid.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class Mac(
    val id: String,
    val name: String,
    val url: String,
    val encryptedToken: String,
    val lastNotificationSeq: Long = 0,
)

data class Settings(val backgroundNotifications: Boolean, val fontSize: Float, val fitToPhone: Boolean = true)

private val Context.dataStore by preferencesDataStore(name = "cmux")

class MacStore(private val context: Context, private val cipher: TokenCipher = TokenCipher()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Mac.serializer())

    val macs: Flow<List<Mac>> = context.dataStore.data.map { decodeMacs(it) }

    val settings: Flow<Settings> = context.dataStore.data.map {
        Settings(
            backgroundNotifications = it[BACKGROUND_NOTIFICATIONS] ?: false,
            fontSize = it[FONT_SIZE] ?: DEFAULT_FONT_SIZE,
            fitToPhone = it[FIT_TO_PHONE] ?: true,
        )
    }

    fun token(mac: Mac): String? = cipher.decrypt(mac.encryptedToken)

    suspend fun add(id: String, name: String, url: String, token: String, notificationSeq: Long) {
        update { macs ->
            macs.filterNot { it.id == id || it.url == url } + Mac(id, name, url, cipher.encrypt(token), notificationSeq)
        }
    }

    suspend fun remove(id: String) = update { macs -> macs.filterNot { it.id == id } }

    suspend fun recordNotificationSeq(id: String, seq: Long) = update { macs ->
        macs.map { if (it.id == id && seq > it.lastNotificationSeq) it.copy(lastNotificationSeq = seq) else it }
    }

    suspend fun rename(id: String, name: String) = update { macs ->
        macs.map { if (it.id == id && it.name != name) it.copy(name = name) else it }
    }

    suspend fun current(): List<Mac> = macs.first()

    suspend fun setBackgroundNotifications(enabled: Boolean) {
        context.dataStore.edit { it[BACKGROUND_NOTIFICATIONS] = enabled }
    }

    suspend fun setFontSize(size: Float) {
        context.dataStore.edit { it[FONT_SIZE] = size }
    }

    suspend fun setFitToPhone(enabled: Boolean) {
        context.dataStore.edit { it[FIT_TO_PHONE] = enabled }
    }

    private suspend fun update(transform: (List<Mac>) -> List<Mac>) {
        context.dataStore.edit { preferences ->
            preferences[MACS] = json.encodeToString(serializer, transform(decodeMacs(preferences)))
        }
    }

    private fun decodeMacs(preferences: Preferences): List<Mac> =
        preferences[MACS]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    companion object {
        const val DEFAULT_FONT_SIZE = 11f
        private val MACS = stringPreferencesKey("macs")
        private val BACKGROUND_NOTIFICATIONS = booleanPreferencesKey("background_notifications")
        private val FONT_SIZE = floatPreferencesKey("font_size")
        private val FIT_TO_PHONE = booleanPreferencesKey("fit_to_phone")
    }
}
