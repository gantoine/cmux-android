package com.gantoine.muxer.model

import java.time.Instant

fun parseInstant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

fun secondsBetween(now: Instant, due: Instant): Long = (due.epochSecond - now.epochSecond).coerceAtLeast(0)
