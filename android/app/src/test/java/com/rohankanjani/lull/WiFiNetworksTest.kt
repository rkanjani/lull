package com.rohankanjani.lull

import com.rohankanjani.lull.WiFiNetworks.Choice
import com.rohankanjani.lull.WiFiNetworks.Heard
import org.junit.Assert.assertEquals
import org.junit.Test

/** Android only: an iPhone can't list the Wi-Fi networks around it, only the one it's on. */
class WiFiNetworksTest {
    @Test
    fun listsEach24GHzNetworkOnceStrongestFirstWithThePhonesOwnOnTop() {
        val heard = listOf(
            Heard("Neighbour", 2437, -70),
            Heard("Home", 2412, -60),
            Heard("Home", 5180, -40),
            Heard("Home-5G", 5745, -45),
            Heard("Cafe", 2462, -50),
            Heard("Cafe", 2412, -80),
            Heard("", 2437, -30),
        )
        assertEquals(
            listOf(Choice("Home", current = true), Choice("Cafe", current = false), Choice("Neighbour", current = false)),
            WiFiNetworks.choose(heard, current = "Home"),
        )
    }

    @Test
    fun offersThePhonesOwnNetworkWhenAScanMissesItButNotWhenItsOnly5GHz() {
        assertEquals(listOf(Choice("Home", current = true)), WiFiNetworks.choose(emptyList(), current = "Home"))
        assertEquals(emptyList<Choice>(), WiFiNetworks.choose(listOf(Heard("Home", 5180, -40)), current = "Home"))
    }
}
