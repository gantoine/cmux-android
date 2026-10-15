package com.gantoine.muxer.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gantoine.muxer.protocol.Approval
import com.gantoine.muxer.protocol.Retry
import com.gantoine.muxer.protocol.WorkspaceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MacSnapshot(
    val macId: String,
    val macName: String,
    val connected: Boolean,
    val updatedAt: Long,
    val workspaces: List<WorkspaceInfo> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val retries: List<Retry> = emptyList(),
    val busySurfaces: Set<String> = emptySet(),
)

private val Context.snapshotDataStore by preferencesDataStore(name = "snapshots")

class SnapshotStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val snapshots: Flow<Map<String, MacSnapshot>> = context.snapshotDataStore.data.map { decode(it) }

    suspend fun current(): Map<String, MacSnapshot> = snapshots.first()

    suspend fun write(snapshot: MacSnapshot) {
        context.snapshotDataStore.edit { it[key(snapshot.macId)] = json.encodeToString(MacSnapshot.serializer(), snapshot) }
    }

    suspend fun markDisconnected(macId: String) {
        context.snapshotDataStore.edit { preferences ->
            val existing = preferences[key(macId)]?.let { decodeOne(it) } ?: return@edit
            if (!existing.connected) return@edit
            preferences[key(macId)] = json.encodeToString(MacSnapshot.serializer(), existing.copy(connected = false))
        }
    }

    suspend fun remove(macId: String) {
        context.snapshotDataStore.edit { it.remove(key(macId)) }
    }

    private fun decode(preferences: Preferences): Map<String, MacSnapshot> =
        preferences.asMap().values
            .mapNotNull { value -> (value as? String)?.let { decodeOne(it) } }
            .associateBy { it.macId }

    private fun decodeOne(encoded: String): MacSnapshot? =
        runCatching { json.decodeFromString(MacSnapshot.serializer(), encoded) }.getOrNull()

    private fun key(macId: String) = stringPreferencesKey("mac:$macId")
}
