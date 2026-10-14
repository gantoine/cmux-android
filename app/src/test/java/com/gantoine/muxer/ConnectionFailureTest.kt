package com.gantoine.muxer

import com.gantoine.muxer.net.connectionFailureReason
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ConnectionFailureTest {
    @Test
    fun reasonsLeaveOutTheAddress() {
        assertEquals("Failed to connect", connectionFailureReason(ConnectException("Failed to connect to /10.0.2.2:18765"), wasConnected = false))
        assertEquals("Address not found", connectionFailureReason(UnknownHostException("my-mac.tailnet.ts.net"), wasConnected = false))
        assertEquals("Timed out", connectionFailureReason(SocketTimeoutException("timeout"), wasConnected = false))
        assertEquals("Failed to connect", connectionFailureReason(IOException("unexpected end of stream on http://10.0.2.2:18765/"), wasConnected = false))
    }

    @Test
    fun droppedConnectionSaysSo() {
        assertEquals("Connection lost", connectionFailureReason(IOException("Socket closed"), wasConnected = true))
    }
}
