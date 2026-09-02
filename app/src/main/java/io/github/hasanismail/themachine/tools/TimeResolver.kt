/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tools

/**
 * Turns the pieces of a time the model heard into the numbers Android wants.
 *
 * The division of labour here is deliberate and was arrived at the hard way. Asked for
 * a timer's total length, a 1B model would answer "ten minute timer" with 180 — the
 * number from the nearest worked example — and asked for a 24-hour clock value it read
 * "half past six in the evening" as hour 2. Neither is a prompting problem: small models
 * copy surface patterns and do not reliably do arithmetic.
 *
 * So the model is asked only to report what it actually heard — ten, minutes, evening —
 * and every calculation happens here, where it is ordinary code that can be tested.
 */
object TimeResolver {

    const val AM = "am"
    const val PM = "pm"

    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
    private const val HOURS_PER_DAY = 24L
    private const val NOON = 12
    private const val LAST_HOUR = 23
    private const val LAST_MINUTE = 59

    /** Longest timer worth honouring; beyond a day it is a reminder, not a countdown. */
    private const val MAX_TIMER_SECONDS =
        HOURS_PER_DAY * MINUTES_PER_HOUR * SECONDS_PER_MINUTE

    /**
     * Total seconds for a spoken duration, or null if nothing usable was heard.
     *
     * Absent parts count as zero rather than failing, because "an hour and a half" only
     * ever arrives as some of these fields.
     */
    fun totalSeconds(hours: Int?, minutes: Int?, seconds: Int?): Int? {
        if (hours == null && minutes == null && seconds == null) return null
        val total = (hours ?: 0).toLong() * MINUTES_PER_HOUR * SECONDS_PER_MINUTE +
            (minutes ?: 0).toLong() * SECONDS_PER_MINUTE +
            (seconds ?: 0).toLong()
        if (total <= 0 || total > MAX_TIMER_SECONDS) return null
        return total.toInt()
    }

    /**
     * Converts a spoken hour to 24-hour time.
     *
     * [meridiem] is what the model heard, if anything: "am", "pm", or null when the
     * speaker did not say. An hour above 12 is already unambiguous and is passed
     * through, so a model that does convert on its own is not broken by this.
     */
    fun to24Hour(hour: Int?, meridiem: String?): Int? {
        if (hour == null || hour < 0 || hour > LAST_HOUR) return null
        val said = meridiem?.trim()?.lowercase()
        return when {
            hour > NOON -> hour
            said == PM -> if (hour == NOON) NOON else hour + NOON
            said == AM -> if (hour == NOON) 0 else hour
            else -> hour
        }
    }

    /** Minutes, defaulting to o'clock, and rejecting anything outside the hour. */
    fun minuteOf(minute: Int?): Int? {
        val value = minute ?: 0
        return if (value in 0..LAST_MINUTE) value else null
    }
}
