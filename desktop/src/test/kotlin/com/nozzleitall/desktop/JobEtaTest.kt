package com.nozzleitall.desktop

import com.nozzleitall.desktop.screens.JobEta
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class JobEtaTest {
    private val utc = ZoneId.of("UTC")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 18, h, m, 0, 0, utc).toInstant().toEpochMilli()

    @Test fun halfwayAfterAnHourLeavesAnHour() = assertEquals(3600.0, JobEta.remainingSeconds(0.5f, 3600.0)!!, 0.5)
    @Test fun aQuarterDoneAfterTenMinutesLeavesThirty() = assertEquals(1800.0, JobEta.remainingSeconds(0.25f, 600.0)!!, 0.5)
    @Test fun tooEarlyUnknownOrDoneGivesNothing() {
        assertNull(JobEta.remainingSeconds(0.01f, 60.0)); assertNull(JobEta.remainingSeconds(0.5f, null)); assertNull(JobEta.remainingSeconds(1f, 100.0)); assertNull(JobEta.remainingSeconds(0.5f, 0.0))
    }
    @Test fun theEndTimeIsToday() = assertEquals("4:35 PM", JobEta.finishesAt(at(15, 5), 90 * 60.0, utc, Locale.US))
    @Test fun pastMidnightNamesTomorrow() = assertEquals("tomorrow 1:00 AM", JobEta.finishesAt(at(22, 0), 3 * 3600.0, utc, Locale.US))
    @Test fun furtherAwayNamesTheDay() = assertEquals("Tue 10:00 AM", JobEta.finishesAt(at(10, 0), 2 * 86400.0, utc, Locale.US))
}
