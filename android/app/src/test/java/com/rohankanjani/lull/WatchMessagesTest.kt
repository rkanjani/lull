package com.rohankanjani.lull

import com.rohankanjani.lull.bedjetkit.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the watch and the phone say to each other. The iPhone apps send the same bytes, pinned by the same strings in
 * `WatchMessagesTests.swift`, so a change to either side shows up in both.
 */
class WatchMessagesTest {
    private val side = "6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D10"

    @Test
    fun commandsEncodeTheSameAsTheIPhoneApps() {
        val cool = RemotePreset(Mode.COOL, minutes = 30, temperature = 44, fanPercent = 40)
        val cases = listOf(
            WatchCommand.StartNight(side) to """{"bedJet":"6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D10","kind":"startNight"}""",
            WatchCommand.StartNight(null) to """{"kind":"startNight"}""",
            WatchCommand.EndNight(side) to """{"bedJet":"6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D10","kind":"endNight"}""",
            WatchCommand.Remote(cool, side) to
                """{"bedJet":"6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D10","fanPercent":40,"kind":"remote","minutes":30,"mode":"cool","temperature":44}""",
            WatchCommand.Remote(RemotePreset(Mode.EXTENDED_HEAT, minutes = 90, temperature = 58, fanPercent = 25), null) to
                """{"fanPercent":25,"kind":"remote","minutes":90,"mode":"gentleHeat","temperature":58}""",
            // From the watch's Remote, which says what was changed on it.
            WatchCommand.Remote(cool, side, setOf(RemoteSetting.TIME, RemoteSetting.FAN)) to
                """{"bedJet":"6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D10","changed":["fan","time"],"fanPercent":40,"kind":"remote","minutes":30,"mode":"cool","temperature":44}""",
            WatchCommand.Refresh to """{"kind":"refresh"}""",
        )
        for ((command, json) in cases) {
            assertEquals(json, command.data.decodeToString())
            assertEquals(command, WatchCommand.from(json.toByteArray()))
        }
    }

    @Test
    fun aSettingALaterVersionAddsIsLeftOut() {
        val cool = RemotePreset(Mode.COOL, minutes = 30, temperature = 44, fanPercent = 40)
        val json = """{"changed":["fan","sparkle"],"fanPercent":40,"kind":"remote","minutes":30,"mode":"cool","temperature":44}"""
        assertEquals(WatchCommand.Remote(cool, null, setOf(RemoteSetting.FAN)), WatchCommand.from(json.toByteArray()))
    }

    @Test
    fun repliesEncodeTheSameAsTheIPhoneApps() {
        assertEquals("""{"ok":true}""", WatchReply.done.data.decodeToString())
        val failed = WatchReply.failed("Couldn't reach your BedJet.")
        assertEquals("""{"message":"Couldn't reach your BedJet.","ok":false}""", failed.data.decodeToString())
        assertEquals(failed, WatchReply.from(failed.data))
    }

    @Test
    fun onlyErrandsAreAnswered() {
        assertTrue(WatchCommand.StartNight(null).isAnswered)
        assertTrue(WatchCommand.EndNight(side).isAnswered)
        assertTrue(WatchCommand.Remote(RemotePreset(Mode.COOL, minutes = 30, temperature = 44, fanPercent = 40), null).isAnswered)
        // The watch takes any reply as its errand's, so a refresh sent while one runs mustn't answer it.
        assertFalse(WatchCommand.Refresh.isAnswered)
    }

    @Test
    fun ignoresWhatItDoesntUnderstand() {
        assertNull(WatchCommand.from("""{"kind":"dance"}""".toByteArray()))
        assertNull(WatchCommand.from("""{"kind":"remote","mode":"sauna","minutes":30,"temperature":44,"fanPercent":40}""".toByteArray()))
        assertNull(WatchCommand.from("not json".toByteArray()))
        // A BedJet named but unreadable isn't taken as none named, which would mean the one on screen.
        assertNull(WatchCommand.from("""{"bedJet":"left side","kind":"endNight"}""".toByteArray()))
        assertNull(WatchCommand.from("""{"bedJet":42,"kind":"startNight"}""".toByteArray()))
        assertNull(WatchCommand.from("""{"bedJet":null,"kind":"endNight"}""".toByteArray()))
    }
}
