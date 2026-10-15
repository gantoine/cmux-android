package com.gantoine.muxer.wear

import android.util.Log
import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.watch.WatchProtocol
import com.gantoine.muxer.watch.WatchSummary
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class WatchSync(private val app: MuxerApplication) {
    @OptIn(FlowPreview::class)
    fun start() {
        if (!hasPlayServices()) return
        app.scope.launch {
            try {
                combine(app.store.macs, app.snapshots.snapshots) { macs, snapshots ->
                    WatchSummaries.build(macs, snapshots, System.currentTimeMillis())
                }
                    .debounce(DEBOUNCE_MS)
                    .distinctUntilChanged()
                    .collect(::publish)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.i("cmux", "Stopped sharing agent status with a watch", failure)
            }
        }
    }

    private suspend fun publish(summary: WatchSummary) {
        val request = PutDataMapRequest.create(WatchProtocol.SUMMARY_PATH)
            .apply { dataMap.putString(WatchProtocol.SUMMARY_KEY, WatchProtocol.encodeSummary(summary)) }
            .asPutDataRequest()
            .setUrgent()
        Wearable.getDataClient(app).putDataItem(request).await()
    }

    private fun hasPlayServices(): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    private companion object {
        const val DEBOUNCE_MS = 1_000L
    }
}
