package com.gantoine.muxer.notifications

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gantoine.muxer.R
import com.gantoine.muxer.data.MacStore
import com.gantoine.muxer.net.ConnectionManager
import com.gantoine.muxer.net.ConnectionState
import com.gantoine.muxer.net.VisibleTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant

class ActivityNotifier(
    private val context: Context,
    private val store: MacStore,
    private val connections: ConnectionManager,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
) {
    private val workingSince = mutableMapOf<String, Long>()
    private val retryLengths = mutableMapOf<String, Long>()
    private val postedWorking = mutableMapOf<Int, WorkingAgent>()

    @Volatile
    private var macNames = emptyMap<String, String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            combine(openComputers(), connections.lookingAt) { computers, lookingAt -> computers to lookingAt }
                .flatMapLatest { (computers, lookingAt) ->
                    if (computers.all { it.retries.isEmpty() }) {
                        flowOf(activities(computers, lookingAt, Instant.now()))
                    } else {
                        ticking().map { now -> activities(computers, lookingAt, now) }
                    }
                }
                .collect(::render)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun openComputers(): Flow<List<ComputerActivity>> =
        combine(store.macs, connections.connectionsFlow) { macs, open ->
            macs.filter { it.showActivity }.mapNotNull { mac -> open[mac.id]?.let { mac to it } }
        }.flatMapLatest { watched ->
            macNames = watched.associate { (mac, _) -> mac.id to mac.name }
            if (watched.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    watched.map { (mac, connection) ->
                        combine(connection.state, connection.workspaces, connection.busySurfaces, connection.retries) { state, workspaces, busy, retries ->
                            ComputerActivity(mac.id, workspaces.orEmpty(), busy, retries).takeIf { state is ConnectionState.Connected }
                        }
                    },
                ) { computers -> computers.filterNotNull() }
            }
        }

    private fun ticking(): Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(REFRESH_MILLIS)
        }
    }

    private fun activities(computers: List<ComputerActivity>, lookingAt: VisibleTerminal?, now: Instant): Pair<List<LiveActivity>, Instant> {
        val list = computers.flatMap { computer ->
            val isLookingAt = { surfaceId: String -> lookingAt?.macId == computer.macId && lookingAt.surfaceId == surfaceId }
            LiveActivities.working(computer, isLookingAt) + LiveActivities.retries(computer, now, isLookingAt)
        }
        return list to now
    }

    private fun render(update: Pair<List<LiveActivity>, Instant>) {
        val (activities, now) = update
        val manager = NotificationManagerCompat.from(context)
        val wanted = if (notifier.canNotify()) activities.associateBy { notificationId(it.key) } else emptyMap()
        forgetGone(wanted.values, now)
        manager.activeNotifications
            .filter { NotificationCompat.getChannelId(it.notification) == Notifier.ACTIVITY_CHANNEL && it.id !in wanted.keys }
            .forEach { manager.cancel(it.id) }
        postedWorking.keys.retainAll(wanted.keys)
        wanted.forEach { (id, activity) ->
            when (activity) {
                is WorkingAgent -> if (postedWorking[id] != activity) {
                    post(manager, id, working(activity))
                    postedWorking[id] = activity
                }
                is RetryCountdown -> post(manager, id, retry(activity, now))
            }
        }
    }

    private fun forgetGone(activities: Collection<LiveActivity>, now: Instant) {
        val keys = activities.map { it.key }.toSet()
        workingSince.keys.retainAll(keys)
        val retryKeys = activities.filterIsInstance<RetryCountdown>().map(::retryKey).toSet()
        retryLengths.keys.retainAll(retryKeys)
        activities.forEach { activity ->
            when (activity) {
                is WorkingAgent -> workingSince.getOrPut(activity.key) { now.toEpochMilli() }
                is RetryCountdown -> retryLengths.getOrPut(retryKey(activity)) { LiveActivities.secondsUntil(activity, now) }
            }
        }
    }

    private fun retryKey(retry: RetryCountdown) = "${retry.key}:${retry.dueAt}"

    private fun working(agent: WorkingAgent): NotificationCompat.Builder =
        baseBuilder(agent)
            .setContentTitle(LiveActivities.workingTitle(agent))
            .setWhen(workingSince[agent.key] ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setShortCriticalText(agent.workspaceTitle)

    private fun retry(retry: RetryCountdown, now: Instant): NotificationCompat.Builder {
        val remaining = LiveActivities.secondsUntil(retry, now)
        val percent = LiveActivities.progressPercent(retryLengths[retryKey(retry)] ?: remaining, remaining)
        val builder = baseBuilder(retry)
            .setContentTitle(LiveActivities.retryTitle(retry, now))
            .setContentText(listOfNotNull(retry.workspaceTitle, "attempt ${retry.attempt}").joinToString(" · "))
            .setWhen(retry.dueAt.toEpochMilli())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShortCriticalText(LiveActivities.countdown(remaining))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(PROGRESS_MAX)))
                    .setProgress(percent),
            )
        } else {
            builder.setProgress(PROGRESS_MAX, percent, false)
        }
    }

    private fun baseBuilder(activity: LiveActivity): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, Notifier.ACTIVITY_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setSubText(macNames[activity.macId])
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setRequestPromotedOngoing(true)
            .setContentIntent(notifier.openTerminalIntent(activity.macId, activity.workspaceId, activity.surfaceId, notificationId(activity.key)))
    }

    private fun post(manager: NotificationManagerCompat, id: Int, builder: NotificationCompat.Builder) {
        runCatching {
            @Suppress("MissingPermission")
            manager.notify(id, builder.build())
        }
    }

    private fun notificationId(key: String): Int = key.hashCode()

    private companion object {
        const val REFRESH_MILLIS = 5_000L
        const val PROGRESS_MAX = 100
    }
}
