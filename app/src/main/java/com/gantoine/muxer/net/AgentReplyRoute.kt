package com.gantoine.muxer.net

import com.gantoine.muxer.ui.AgentMessaging

enum class AgentReplyRoute {
    Shared,
    Separate,
    ;

    companion object {
        fun choose(sharedConnected: Boolean, sharedAttachedToTarget: Boolean): AgentReplyRoute =
            if (sharedConnected && sharedAttachedToTarget) Shared else Separate

        fun shouldPasteInstead(error: Throwable): Boolean =
            error is RelayException && error.code in AgentMessaging.relayCannotMessage
    }
}
