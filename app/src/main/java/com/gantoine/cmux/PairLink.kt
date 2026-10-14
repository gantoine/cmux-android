package com.gantoine.cmux

import android.net.Uri
import com.gantoine.cmux.ui.LaunchTarget

object PairLink {
    fun parse(uri: Uri?): LaunchTarget.Pair? {
        if (uri?.scheme != "cmuxandroid" || uri.host != "pair") return null
        return LaunchTarget.Pair(uri.getQueryParameter("url").orEmpty(), uri.getQueryParameter("code").orEmpty())
    }

    fun parse(text: String): LaunchTarget.Pair? = parse(Uri.parse(text.trim()))
}
