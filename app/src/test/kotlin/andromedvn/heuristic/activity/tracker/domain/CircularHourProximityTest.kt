package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Hour-of-day proximity, measured around the clock.
 *
 * `ActivityRepository.getSmartSuggestions` (line 241) weighted each stored
 * activity by
 *
 *     abs(Calendar.getInstance().apply { timeInMillis = act.timestamp }
 *             .get(Calendar.HOUR_OF_DAY) - targetHour)
 *
 * and scored 3.0 at distance 0, 1.5 within 2, else 0.5. A day is a circle, not
 * a line: 23:00 and 01:00 are two hours apart, but `abs(23 - 1)` is 22, so a
 * user labelling a 01:00 gap was shown as LEAST likely to have been asleep —
 * when a 23:00-to-01:00 stretch is precisely the habit that produced it. The
 * late-night band (22:00-05:00) was scored as maximally distant from itself
 * across the boundary.
 *
 * The same `when` in the no-history fallback of that function already treats
 * `in 22..23, in 0..5` as one block, so the intent was always circular; only
 * the weighted path disagreed with it.
 */
class CircularHourProximityTest {

    // --- the cases named in the report -------------------------------------

    @Test
    fun `2300 and 0100 are two hours apart, not twenty two`() {
        assertEquals(2, circularHourDistance(23, 1))
        assertNotEquals("this is the defect", 22, circularHourDistance(23, 1))
    }

    @Test
    fun `0030 and 2330 are one hour apart`() {
        // Hour-of-day form: 0 and 23.
        assertEquals(1, circularHourDistance(0, 23))
        // Minute-of-day form, which is what the two timestamps really are.
        assertEquals(60, circularDialDistance(30, 23 * 60 + 30, 1440))
        assertEquals(1, circularDialDistance(30, 23 * 60 + 30, 1440) / 60)
    }

    @Test
    fun `the same hour is always zero apart`() {
        for (hour in 0..23) {
            assertEquals("hour $hour vs itself", 0, circularHourDistance(hour, hour))
        }
        assertEquals(0, circularDialDistance(0, 0, 1440))
    }

    // --- properties over the whole dial ------------------------------------

    @Test
    fun `every distance is at most half the dial and the function is symmetric`() {
        for (a in 0..23) {
            for (b in 0..23) {
                val d = circularHourDistance(a, b)
                assertTrue("23:00 and 01:00 must not exceed 12 h, got $d for $a/$b", d in 0..12)
                assertEquals("asymmetric at $a/$b", circularHourDistance(b, a), d)
            }
        }
    }

    @Test
    fun `the distance agrees with the number of steps around the clock`() {
        for (a in 0..23) {
            for (b in 0..23) {
                val forward = ((b - a) + 24) % 24
                val backward = ((a - b) + 24) % 24
                assertEquals(
                    "shortest arc for $a->$b (forward $forward, backward $backward)",
                    minOf(forward, backward),
                    circularHourDistance(a, b)
                )
            }
        }
    }

    @Test
    fun `no two distinct hours are more than twelve apart`() {
        // The old form could return 23 (00:00 vs 23:00). A proximity score on a
        // circular clock can never need more than half a revolution.
        var worstOld = 0
        var worstNew = 0
        for (a in 0..23) {
            for (b in 0..23) {
                worstOld = maxOf(worstOld, kotlin.math.abs(a - b))
                worstNew = maxOf(worstNew, circularHourDistance(a, b))
            }
        }
        assertEquals("the old form's worst case", 23, worstOld)
        assertEquals("the circular form's worst case", 12, worstNew)
    }

    @Test
    fun `the two forms agree everywhere except across midnight`() {
        // The fix must be inert during the working day, or it would be changing
        // behaviour the user is used to. Only the pairs that straddle midnight
        // may differ.
        for (a in 0..23) {
            for (b in 0..23) {
                val old = kotlin.math.abs(a - b)
                val new = circularHourDistance(a, b)
                if (old == new) continue
                val straddles = (a < 12) != (b < 12)
                assertTrue("only midnight-straddling pairs may differ, got $a/$b old=$old new=$new", straddles)
                assertEquals("and the circular form is always the smaller one", minOf(old, new), new)
            }
        }
    }

    @Test
    fun `midnight and noon are the antipodes`() {
        assertEquals(12, circularHourDistance(0, 12))
        assertEquals(12, circularHourDistance(23, 11))
    }

    @Test
    fun `the whole late-night band is close to itself`() {
        // 22:00, 23:00, 00:00, 01:00, 02:00 form one cluster. Under the old
        // form 23:00 and 00:00 were 23 hours apart.
        val band = listOf(22, 23, 0, 1, 2)
        for (a in band) {
            for (b in band) {
                assertTrue(
                    "the night band must stay within 4 h of itself, got ${circularHourDistance(a, b)} for $a/$b",
                    circularHourDistance(a, b) <= 4
                )
            }
        }
    }

    // --- out-of-range input is wrapped, never negative --------------------

    @Test
    fun `an out of range hour is wrapped rather than producing a negative distance`() {
        assertEquals("hour 25 is hour 1", 0, circularHourDistance(25, 1))
        assertEquals("hour -1 is hour 23", 0, circularHourDistance(-1, 23))
        assertEquals(2, circularHourDistance(-1, 1))
        assertTrue(circularDialDistance(-30, 30, 1440) >= 0)
    }

    // --- the score the comparison feeds ------------------------------------

    @Test
    fun `the proximity weight still has its three levels`() {
        assertEquals(3.0, hourProximityWeight(0), 0.0)
        assertEquals(1.5, hourProximityWeight(1), 0.0)
        assertEquals(1.5, hourProximityWeight(2), 0.0)
        assertEquals(0.5, hourProximityWeight(3), 0.0)
        assertEquals(0.5, hourProximityWeight(12), 0.0)
    }

    @Test
    fun `a 2300 habit now scores as close to a 0100 gap`() {
        val activityHour = 23
        val targetHour = 1

        val oldDistance = kotlin.math.abs(activityHour - targetHour)
        val newDistance = circularHourDistance(activityHour, targetHour)

        assertEquals(22, oldDistance)
        assertEquals(2, newDistance)
        assertEquals(
            "the old form put it in the far bucket",
            0.5, hourProximityWeight(oldDistance), 0.0
        )
        assertEquals(
            "the circular form puts it in the near bucket",
            1.5, hourProximityWeight(newDistance), 0.0
        )
    }

    // --- end to end over a real timestamp ----------------------------------

    @Test
    fun `the distance is taken from the local hour of a real timestamp`() {
        // Built once in a fixed zone so the absolute millis are unambiguous.
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Manila"))
            fun at(hour: Int, minute: Int): Int = Calendar.getInstance().apply {
                clear()
                set(2024, Calendar.MARCH, 10, hour, minute, 0)
            }.get(Calendar.HOUR_OF_DAY)

            assertEquals(23, at(23, 0))
            assertEquals(1, at(1, 0))
            assertEquals("23:00 and 01:30 on the clock", 2, circularHourDistance(at(23, 0), at(1, 30)))
            assertEquals("00:30 and 23:30 on the clock", 1, circularHourDistance(at(0, 30), at(23, 30)))
            assertEquals("identical instants", 0, circularHourDistance(at(9, 15), at(9, 15)))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
