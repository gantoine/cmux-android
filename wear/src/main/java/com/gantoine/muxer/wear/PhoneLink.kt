package com.gantoine.muxer.wear

import android.content.Context
import android.net.Uri
import com.gantoine.muxer.watch.WatchAnswer
import com.gantoine.muxer.watch.WatchAnswerResult
import com.gantoine.muxer.watch.WatchProtocol
import com.gantoine.muxer.watch.WatchSummary
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

class PhoneLink(context: Context) {
    private val context = context.applicationContext
    private val dataClient get() = Wearable.getDataClient(context)

    suspend fun summary(): WatchSummary? = try {
        val items = dataClient.getDataItems(summaryUri(host = null)).await()
        try {
            items.mapNotNull(::decode).maxByOrNull { it.updatedAt }
        } finally {
            items.release()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    fun summaries(): Flow<WatchSummary?> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { launch { send(summary()) } }
        dataClient.addListener(listener, summaryUri(host = "*"), DataClient.FILTER_LITERAL)
        send(summary())
        awaitClose { dataClient.removeListener(listener) }
    }

    suspend fun answer(answer: WatchAnswer): WatchAnswerResult = try {
        withTimeout(ANSWER_TIMEOUT_MS) {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            if (phone == null) {
                WatchAnswerResult(ok = false, message = PHONE_NOT_REACHABLE)
            } else {
                val reply = Wearable.getMessageClient(context)
                    .sendRequest(phone.id, WatchProtocol.ANSWER_PATH, WatchProtocol.encodeAnswer(answer))
                    .await()
                WatchProtocol.decodeResult(reply)
            }
        }
    } catch (_: TimeoutCancellationException) {
        WatchAnswerResult(ok = false, message = "Your phone did not answer in time.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        WatchAnswerResult(ok = false, message = PHONE_NOT_REACHABLE)
    }

    private fun decode(item: DataItem): WatchSummary? =
        WatchProtocol.decodeSummary(DataMapItem.fromDataItem(item).dataMap.getString(WatchProtocol.SUMMARY_KEY))

    private fun summaryUri(host: String?): Uri = Uri.Builder()
        .scheme(PutDataRequest.WEAR_URI_SCHEME)
        .apply { if (host != null) authority(host) }
        .path(WatchProtocol.SUMMARY_PATH)
        .build()

    private companion object {
        const val ANSWER_TIMEOUT_MS = 20_000L
        const val PHONE_NOT_REACHABLE = "Your phone is not reachable. Open Muxer on your phone and try again."
    }
}
