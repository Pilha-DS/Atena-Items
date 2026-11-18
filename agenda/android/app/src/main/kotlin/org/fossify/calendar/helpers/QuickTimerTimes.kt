package org.fossify.calendar.helpers

import org.fossify.calendar.models.QuickTimer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Parsing, formatting and the ringing rules of the quick timers.
 *
 * Kept away from the UI and from Android so the rules can be unit tested: the
 * activity only asks for a duration in millis and for the labels it shows.
 */
object QuickTimerTimes {

    private const val MINUTES_PER_HOUR = 60L
    private const val HOURS_PER_DAY = 24L
    private const val MILLIS_PER_SECOND = 1000L
    private const val MILLIS_PER_MINUTE = 60L * MILLIS_PER_SECOND
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE

    /** One minute up to one day: enough for "chego em 3 horas". */
    const val MIN_MINUTES = 1L
    const val MAX_MINUTES = HOURS_PER_DAY * MINUTES_PER_HOUR

    /**
     * A timer that ended up to this long ago still rings; an older one is only
     * marked. A countdown that arrived through the sync while the device was
     * off is a note from the past, not an alarm.
     */
    const val RING_GRACE_MS = 10L * 60L * 1000L

    /** The buttons under the duration field, in minutes. */
    @Suppress("MagicNumber")
    val presets: List<Int> = listOf(5, 10, 15, 25, 30, 60, 120)

    private val clockFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    private val colon = Regex("""^(\d{1,2}):([0-5]\d)$""")
    private val hoursAndMinutes =
        Regex("""^(\d+)\s*h(?:oras?)?(?:\s*(\d{1,2})\s*(?:m|min|minutos?)?)?$""")
    private val minutesOnly = Regex("""^(\d+)\s*(?:m|min|minutos?)?$""")

    /**
     * Reads what people actually type: `25`, `25min`, `1h`, `1h30`, `1:30`,
     * `2 horas`. Returns `null` for anything outside 1 minute – 24 hours, so
     * the screen can show a single clear message.
     */
    fun parseDurationMillis(raw: String): Long? {
        val text = raw.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return null

        val totalMinutes = colon.matchEntire(text)?.let { match ->
            match.groupValues[1].toLong() * MINUTES_PER_HOUR + match.groupValues[2].toLong()
        } ?: hoursAndMinutes.matchEntire(text)?.let { match ->
            match.groupValues[1].toLong() * MINUTES_PER_HOUR +
                (match.groupValues[2].toLongOrNull() ?: 0L)
        } ?: minutesOnly.matchEntire(text)?.let { match ->
            match.groupValues[1].toLong()
        } ?: return null

        return if (totalMinutes in MIN_MINUTES..MAX_MINUTES) {
            totalMinutes * MILLIS_PER_MINUTE
        } else {
            null
        }
    }

    /** "12:34", or "1:02:03" once the countdown passes an hour. Zero when it is over. */
    fun remainingLabel(endTime: Long, now: Long): String {
        val totalSeconds = (endTime - now).coerceAtLeast(0L) / MILLIS_PER_SECOND
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    /** "15:42", in the device's time zone. */
    fun endLabel(endTime: Long): String = clockFormat.get()!!.format(Date(endTime))

    /** It is due and still inside the grace window: this one rings. */
    fun shouldRing(timer: QuickTimer, now: Long): Boolean =
        !timer.notified && timer.endTime <= now && now - timer.endTime <= RING_GRACE_MS

    /** It expired too long ago to be useful: mark it without a sound. */
    fun isStale(timer: QuickTimer, now: Long): Boolean =
        !timer.notified && timer.endTime <= now && now - timer.endTime > RING_GRACE_MS

    fun isFinished(timer: QuickTimer, now: Long): Boolean = timer.endTime <= now
}
