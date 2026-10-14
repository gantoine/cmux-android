package com.gantoine.cmux.data

object Names {
    private val doubledPossessive = Regex("s(['\u2019])s(?=\\s)")

    fun possessive(name: String): String = doubledPossessive.replace(name, "s$1")
}
