package com.gantoine.muxer

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.gantoine.muxer.data.MacStore
import com.gantoine.muxer.net.ConnectionManager
import com.gantoine.muxer.net.PairingClient
import com.gantoine.muxer.notifications.ConnectionService
import com.gantoine.muxer.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MuxerApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    val store: MacStore by lazy { MacStore(this) }
    val notifier: Notifier by lazy { Notifier(this) }
    val pairing: PairingClient by lazy { PairingClient(httpClient) }

    val connections: ConnectionManager by lazy {
        ConnectionManager(
            store = store,
            client = httpClient,
            scope = scope,
            notify = notifier::show,
            dismiss = notifier::dismiss,
            notifyApproval = notifier::showApproval,
            dismissApproval = notifier::dismissApproval,
        )
    }

    private val notificationPermission = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        notificationPermission.value = notifier.canNotify()
        scope.launch {
            store.migrateGlobalBackgroundSetting()
            connections.start()
            keepBackgroundServiceInSync()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                notificationPermission.value = notifier.canNotify()
                connections.setAppVisible(true)
            }

            override fun onStop(owner: LifecycleOwner) = connections.setAppVisible(false)
        })
    }

    fun notificationPermissionChanged() {
        notificationPermission.value = notifier.canNotify()
    }

    private suspend fun keepBackgroundServiceInSync() {
        combine(store.macs, notificationPermission) { macs, allowed -> allowed && macs.any { it.backgroundNotifications } }
            .distinctUntilChanged()
            .collect { wanted ->
                runCatching { if (wanted) ConnectionService.start(this) else ConnectionService.stop(this) }
                    .onFailure { Log.w("cmux", "Could not change the background connection service", it) }
            }
    }
}
