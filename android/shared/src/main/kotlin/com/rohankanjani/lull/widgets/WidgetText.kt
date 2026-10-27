package com.rohankanjani.lull.widgets

import com.rohankanjani.lull.Format
import com.rohankanjani.lull.LiveStatus
import com.rohankanjani.lull.LullTab
import java.time.Instant
import java.time.ZoneId

fun clock(millis: Long): String {
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
    return Format.clock(time.hour * 60 + time.minute)
}

/** "Ends 6:30 AM" through a night, "Turns off at 1:30 AM" for air from the Remote. */
val LiveStatus.ending: String
    get() = if (this is LiveStatus.Night) "Ends ${clock(endsAt)}" else "Turns off at ${clock(endsAt)}"

/** Where a tap takes Lull: the Schedule for a night, the Remote for air. */
val LiveStatus.tab: LullTab get() = if (this is LiveStatus.Night) LullTab.SCHEDULE else LullTab.REMOTE
