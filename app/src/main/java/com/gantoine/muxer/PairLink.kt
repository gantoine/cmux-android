package com.gantoine.muxer

import android.net.Uri
import com.gantoine.muxer.ui.LaunchTarget

object PairLink {
    fun parse(uri: Uri?): LaunchTarget.Pair? {
        if (uri?.scheme != "muxer" || uri.host != "pair") return null
        return LaunchTarget.Pair(uri.getQueryParameter("url").orEmpty(), uri.getQueryParameter("code").orEmpty())
    }

    fun parse(text: String): LaunchTarget.Pair? = parse(Uri.parse(text.trim()))
}
