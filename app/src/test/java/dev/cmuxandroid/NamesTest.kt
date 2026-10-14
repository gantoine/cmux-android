package dev.cmuxandroid

import dev.cmuxandroid.data.Names
import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {
    @Test
    fun namesEndingInSGetABareApostrophe() {
        assertEquals("Georges' MacBook Pro", Names.possessive("Georges's MacBook Pro"))
        assertEquals("Georges\u2019 MacBook Pro", Names.possessive("Georges\u2019s MacBook Pro"))
        assertEquals("Anna's MacBook Air", Names.possessive("Anna's MacBook Air"))
        assertEquals("Boss's", Names.possessive("Boss's"))
        assertEquals("M4", Names.possessive("M4"))
    }
}
