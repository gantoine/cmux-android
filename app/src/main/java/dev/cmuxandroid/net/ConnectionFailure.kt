package dev.cmuxandroid.net

import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun connectionFailureReason(failure: Throwable, wasConnected: Boolean): String = when {
    wasConnected -> "Connection lost"
    failure is UnknownHostException -> "Address not found"
    failure is SocketTimeoutException -> "Timed out"
    failure is NoRouteToHostException -> "Unreachable"
    failure is SSLException -> "Secure connection failed"
    else -> "Failed to connect"
}
