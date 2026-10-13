package dev.cmuxandroid

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.cmuxandroid.data.MacStore
import dev.cmuxandroid.net.ConnectionManager
import dev.cmuxandroid.net.PairingClient
import dev.cmuxandroid.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CmuxApplication : Application() {
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
        )
    }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        connections.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = connections.setAppVisible(true)
            override fun onStop(owner: LifecycleOwner) = connections.setAppVisible(false)
        })
    }
}
