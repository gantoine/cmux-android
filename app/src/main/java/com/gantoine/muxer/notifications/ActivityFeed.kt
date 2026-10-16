package com.gantoine.muxer.notifications

import com.gantoine.muxer.data.Mac
import com.gantoine.muxer.data.MacStore
import com.gantoine.muxer.net.ConnectionManager
import com.gantoine.muxer.net.ConnectionState
import com.gantoine.muxer.net.RelayConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant

data class TimedSummary(val summary: ActivitySummary, val now: Instant)

class ActivityFeed(private val store: MacStore, private val connections: ConnectionManager) {
    private val workingSince = mutableMapOf<String, Long>()

    private data class Watched(val mac: Mac, val state: ConnectionState, val activity: ComputerActivity?)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun summaries(): Flow<TimedSummary> =
        combine(store.macs, connections.connectionsFlow) { macs, open -> macs.mapNotNull { mac -> open[mac.id]?.let { mac to it } } }
            .flatMapLatest { watched ->
                if (watched.isEmpty()) flowOf(emptyList()) else combine(watched.map { (mac, connection) -> watch(mac, connection) }) { it.toList() }
            }
            .flatMapLatest { watched ->
                val retrying = watched.any { it.activity?.retries?.isNotEmpty() == true }
                if (retrying) ticking().map { now -> summarize(watched, now) } else flowOf(summarize(watched, Instant.now()))
            }
            .distinctUntilChanged { old, new -> old.summary == new.summary && new.summary.retries.isEmpty() }

    private fun watch(mac: Mac, connection: RelayConnection): Flow<Watched> =
        combine(connection.state, connection.workspaces, connection.busySurfaces, connection.retries, connection.approvals) { state, workspaces, busy, retries, approvals ->
            val activity = ComputerActivity(mac.id, workspaces.orEmpty(), busy, retries, approvals.orEmpty())
                .takeIf { state is ConnectionState.Connected && mac.showActivity }
            Watched(mac, state, activity)
        }

    private fun ticking(): Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(REFRESH_MILLIS)
        }
    }

    private fun summarize(watched: List<Watched>, now: Instant): TimedSummary {
        val activities = watched.mapNotNull { it.activity }
        val working = activities.flatMap { LiveActivities.working(it) }.map { it.copy(since = startedAt(it, now)) }
        workingSince.keys.retainAll(working.map { it.key }.toSet())
        val running = watched.filter { it.state !is ConnectionState.Stopped }
        val summary = ActivitySummary(
            waiting = activities.flatMap { LiveActivities.waiting(it) },
            retries = activities.flatMap { LiveActivities.retries(it, now) },
            working = working,
            connected = running.count { it.state is ConnectionState.Connected },
            total = running.size,
            macNames = running.associate { it.mac.id to it.mac.name },
        )
        return TimedSummary(summary, now)
    }

    private fun startedAt(agent: WorkingAgent, now: Instant): Long = workingSince.getOrPut(agent.key) { now.toEpochMilli() }

    private companion object {
        const val REFRESH_MILLIS = 5_000L
    }
}
