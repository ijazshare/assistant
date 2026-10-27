/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.assistant

import android.icu.util.TimeZone
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Where a place named aloud keeps its clock, as far as the phone can tell. */
sealed interface Place {
    /** One time right now: [zone]'s, said as being in [name]. */
    data class Known(val name: String, val zone: ZoneId) : Place

    /** A country or a state whose zones disagree right now. */
    data class Several(val name: String) : Place

    /** A name more than one place goes by, and their times disagree: Georgia, Washington. */
    data class Shared(val name: String) : Place

    /** Nowhere the phone has a zone for, including a city its country contradicts. */
    data class Unknown(val name: String) : Place

    /** A place whose clocks changed after the phone's time-zone data was published. */
    data class Stale(val name: String) : Place
}

/**
 * Finds the time zone of a place named aloud, for the world clock in [WorldClock].
 *
 * The names are the platform's: the city in every zone ID (Africa/Johannesburg) and every
 * country's English name, whose zones come from ICU. [ALIASES] and [STATES] add only what
 * neither names. ICU's exemplar city names are left out: beside java.time's IDs they add
 * almost nothing but the towns [cities] leaves out on purpose.
 *
 * A place is answered only if every zone it could mean keeps the same time right now.
 * Australia does not, so it gets asked for a city; nor does "Washington", a state and a
 * capital three hours apart. A city named with its country has to be in it: "Perth,
 * Scotland" is not the Perth the phone knows, so the phone does not know it.
 *
 * The answer is only as good as the phone's time-zone data, which is updated apart from the
 * app. [dataVersion] is that data's release, such as "2026e": a zone whose clocks a later
 * release changed is declined rather than given the old rules' time.
 *
 * [zonesOf] is passed in because android.icu is only a stub in JVM unit tests.
 */
class Places(private val dataVersion: String, private val zonesOf: (region: String) -> Collection<String>) {

    /** Every name, folded by [keyOf], and each place it could mean. */
    private val index: Map<String, List<Meaning>> by lazy {
        (cities() + countries() + aliases(ALIASES, regions = false) + aliases(STATES, regions = true))
            .groupBy({ keyOf(it.first) }, { it.second })
    }

    /** Every language by its English name: "what time is it in Spanish" is not about Spain. */
    private val languages: Set<String> by lazy {
        Locale.getISOLanguages().map { keyOf(Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH)) }
            .toSet() + OTHER_LANGUAGES
    }

    /** The place in [spoken], or null if it is not one: "five minutes", "Spanish". */
    fun find(spoken: String, at: Instant): Place? {
        // A second sentence is no part of the place: "Tokyo? Thank you."
        val said = spoken.substringBefore('?').substringBefore('!')
        offset(said)?.let { return it }
        val phrase = Phrase(said)
        // Letters alone would make "UTC+2" plain UTC, and "in 3 days, Tokyo" a town in Japan.
        if (phrase.key().isEmpty() || phrase.hasDigits) return null
        val clock = phrase.beforeTime()
        val place = lookup(phrase, at) ?: clock?.let { lookup(it, at) }
        // "Central European Time" names a zone the phone has no name for, and "Eastern
        // Standard Time" is not Eastern time while the clocks are forward: both are declined
        // rather than handed on to be guessed. "Military time" names nowhere, and is handed on.
        val named = isPlaceLike(phrase.keys) || (clock != null && isPlaceLike(clock.keys))
        return place ?: Place.Unknown(phrase.said()).takeIf { named }
    }

    /**
     * The place in "Tokyo time" or "Pacific time", or null if [spoken] is not a place's time.
     * Nothing says a place is meant but the name, so a name the phone does not know is null.
     */
    fun timeOf(spoken: String, at: Instant): Place? {
        val phrase = Phrase(spoken.substringBefore('?').substringBefore('!'))
        if (phrase.keys.lastOrNull() != "time" || phrase.hasDigits) return null
        return lookup(phrase, at) ?: phrase.beforeTime()?.let { lookup(it, at) }
    }

    private fun lookup(phrase: Phrase, at: Instant): Place? {
        val whole = named(phrase.key())
        return if (whole.isNotEmpty()) {
            settle(whole, phrase.said(), at)
        } else {
            phrase.cuts().firstNotNullOfOrNull { (cut, from) -> within(phrase, cut, from, at) }
        }
    }

    /**
     * A city and where it is, the city's words ending at [cut] and the rest's starting at
     * [from]: "Sydney, Australia", "Tokyo in Japan", "Johannesburg South Africa". Null if the
     * rest is not a place, or there is no comma or "in" and the words before it are not one
     * either.
     */
    private fun within(phrase: Phrase, cut: Int, from: Int, at: Instant): Place? {
        val around = named(phrase.key(from)).ifEmpty { return null }
        val city = named(phrase.key(0, cut))
        if (city.isEmpty()) {
            // Without a comma or an "in" both halves have to be names: New South Wales is not
            // some "New South" in Wales.
            return if (cut == phrase.qualifier?.first) town(phrase, cut, from, around, at) else null
        }
        // The city wins, but only if it lies in what was named with it.
        val inside = city.map { zones -> zones.filter { zone -> around.flatten().any { it.rules == zone.rules } } }
            .filter { it.isNotEmpty() }
        if (inside.isEmpty()) return Place.Unknown(phrase.said())
        // Named the narrower way round: "Australia, Perth" is Perth, not all of Australia.
        val reversed = isRegion(phrase.key(0, cut)) && around.flatten().size < city.flatten().size
        return settle(inside, if (reversed) phrase.said(from) else phrase.said(0, cut), at)
    }

    /**
     * A town with no zone of its own keeps its country's or state's time, if that has only
     * one. A city is no such place: "Norwich, Norfolk" is not on Norfolk Island.
     */
    private fun town(phrase: Phrase, cut: Int, from: Int, around: List<List<ZoneId>>, at: Instant): Place? {
        val zones = around.flatten().distinct()
        return when {
            // "In an hour, London" names no town; it is another question.
            !isPlaceLike(phrase.keys.subList(0, cut)) -> null

            !isRegion(phrase.key(from)) || !oneTime(zones, at) -> Place.Unknown(phrase.said())

            else -> known(phrase.said(), zones)
        }
    }

    /** Whether [key] names a country or a state, which a town can be in and a city cannot. */
    private fun isRegion(key: String) = index[key].orEmpty().any { it.region }

    /** Each place [key] could mean, as its zones; none if it means nothing. */
    private fun named(key: String): List<List<ZoneId>> = index[key].orEmpty()
        .map { meaning ->
            meaning.ids.flatMap { if ('/' in it) listOf(it) else OFFICIAL[it] ?: zonesOf(it) }
                .mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() }
                .distinct()
        }
        .filter { it.isNotEmpty() }
        .distinct()

    private fun settle(places: List<List<ZoneId>>, name: String, at: Instant): Place {
        val zones = places.flatten().distinct()
        return when {
            oneTime(zones, at) -> known(name, zones)
            places.size > 1 -> Place.Shared(name)
            else -> Place.Several(name)
        }
    }

    /** [name] at the time all of [zones] keep, unless the phone's data on one of them is out of date. */
    private fun known(name: String, zones: List<ZoneId>): Place =
        if (zones.any { zone -> CHANGED[zone.id]?.let { dataVersion < it } == true }) {
            Place.Stale(name)
        } else {
            Place.Known(name, zones.first())
        }

    private fun oneTime(zones: List<ZoneId>, at: Instant) = zones.map { it.rules.getOffset(at) }.toSet().size == 1

    /** "UTC+2", "GMT-05:30": a fixed offset, if the rest is only "right now" and the like. */
    private fun offset(spoken: String): Place? {
        val match = OFFSET.find(spoken) ?: return null
        val rest = spoken.substring(match.range.last + 1)
        if (rest.any { it.isDigit() } || trimmed(wordsOf(rest), keep = 0).isNotEmpty()) return null
        val name = spoken.substring(match.groups[1]?.range?.first ?: return null, match.range.last + 1)
        val (signed, hourDigits, minuteDigits) = match.groupValues.drop(2)
        val sign = if (signed.lowercase() in setOf("-", "−", "minus")) -1 else 1
        val hours = hourDigits.toInt()
        val minutes = minuteDigits.ifEmpty { "0" }.toInt()
        // Kiribati's +14 is as far as any clock is set.
        return if (minutes < MINUTES_PER_HOUR && hours * MINUTES_PER_HOUR + minutes <= MAX_OFFSET_MINUTES) {
            Place.Known(name, ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes))
        } else {
            Place.Unknown(name)
        }
    }

    /**
     * Whether words that name nothing known are still a place, and so get a plain "I don't
     * know". "In five minutes", "in a fortnight", "in military time" and "in Spanish" follow
     * the same question without naming anywhere: those are other questions, and stay the
     * model's.
     */
    private fun isPlaceLike(keys: List<String>) = keys.isNotEmpty() && keys.first() !in ARTICLES &&
        keys.none { it in NOT_PLACES } && keys.dropWhile { it == "the" }.joinToString(" ") !in languages

    /**
     * The city in each two-part zone ID: Africa/Johannesburg is Johannesburg's. A third
     * part is a town inside a state or province, whose better-known namesake is usually
     * somewhere else: America/Argentina/San_Juan would answer Argentina's time for Puerto
     * Rico's capital.
     */
    private fun cities() = ZoneId.getAvailableZoneIds()
        .filter { it.count { c -> c == '/' } == 1 && it.substringBefore('/') in AREAS && it !in NOT_CITIES }
        .map { it.substringAfter('/') to Meaning(listOf(it), region = false) }

    /** Each country by its English name: "Myanmar (Burma)" by either. */
    private fun countries() = Locale.getISOCountries().flatMap { code ->
        val name = Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH)
        listOfNotNull(name.replace(ALSO, ""), ALSO.find(name)?.groupValues?.get(1))
            .map { it to Meaning(listOf(code), region = true) }
    }

    /** Each line is one place, by every name on it; a country's code makes it a country. */
    private fun aliases(lines: List<Pair<String, String>>, regions: Boolean) = lines.flatMap { (names, meanings) ->
        val ids = meanings.split(' ')
        names.split(", ").map { it to Meaning(ids, region = regions || ids.none { id -> '/' in id }) }
    }

    /** One place a name can mean: its zones or a country's code, and whether it is a country or state. */
    private class Meaning(val ids: List<String>, val region: Boolean)

    companion object {
        /**
         * The phone's own data. ICU is complete from API 24 and minSdk is 33; in JVM unit
         * tests android.jar only stubs it, so they pass a lookup of their own. An unreadable
         * version counts as the oldest, so every zone [CHANGED] lists is declined.
         */
        val device: Places by lazy {
            Places(runCatching<String?> { TimeZone.getTZDataVersion() }.getOrNull().orEmpty()) { region ->
                TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, region, null)
            }
        }

        /**
         * The first part of a zone ID that is followed by a city, rather than Etc/ or US/. Not
         * Antarctica: its research stations are named for people and other places, and Davis,
         * California does not keep Antarctic time.
         */
        private val AREAS = setOf(
            "Africa", "America", "Arctic", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific",
        )

        /**
         * Old zone IDs kept for regions rather than cities. Australia/West is Perth and
         * would make "the west" a place, and Pacific/Samoa is Pago Pago, a day behind the
         * country called Samoa. Then specks whose names other places are better known by:
         * Midway Atoll is not Chicago's airport, the Falklands' Stanley is not every other
         * Stanley, and Christmas Island, still found by that name, is not Christmas.
         */
        private val NOT_CITIES = setOf(
            "Australia/ACT", "Australia/LHI", "Australia/NSW", "Australia/North", "Australia/Queensland",
            "Australia/South", "Australia/Tasmania", "Australia/Victoria", "Australia/West",
            "Australia/Yancowinna", "Pacific/Samoa",
            "Atlantic/South_Georgia", "Atlantic/Stanley", "Indian/Christmas", "Pacific/Easter", "Pacific/Johnston",
            "Pacific/Midway",
        )

        /**
         * What the platform does not name: some names, and the zones or countries they mean.
         * Each line is one place, so a name on several lines is shared by several places:
         * Georgia is a state and a country, and Norfolk is in Virginia, in England and in the
         * Pacific.
         */
        private val ALIASES = listOf(
            // Countries by the names people use rather than the formal ones.
            "usa, us, america, united states of america" to "US",
            "uk, britain, great britain, england, scotland, wales, northern ireland" to "GB",
            "uae, emirates" to "AE",
            "holland" to "NL",
            "turkey" to "TR",
            "czech republic" to "CZ",
            "ivory coast" to "CI",
            "palestine" to "PS",
            "macedonia" to "MK",
            "saudi, ksa" to "SA",
            // North and South Korea keep the same time.
            "korea" to "KR KP",
            "utc, gmt, greenwich mean time, coordinated universal time, zulu time" to "Etc/UTC",
            // Cities that share a zone without being its namesake.
            "mumbai, bombay, delhi, new delhi, bangalore, bengaluru, chennai" to "Asia/Kolkata",
            "lahore, islamabad, rawalpindi, peshawar" to "Asia/Karachi",
            "mecca, makkah, medina, madinah, jeddah" to "Asia/Riyadh",
            "abu dhabi" to "Asia/Dubai",
            "doha" to "Asia/Qatar",
            "beijing, shenzhen, guangzhou" to "Asia/Shanghai",
            "osaka" to "Asia/Tokyo",
            "hanoi, ho chi minh city" to "Asia/Ho_Chi_Minh",
            "ankara" to "Europe/Istanbul",
            "cape town" to "Africa/Johannesburg",
            "abuja" to "Africa/Lagos",
            "rio de janeiro, rio, brasilia" to "America/Sao_Paulo",
            "barcelona" to "Europe/Madrid",
            "milan" to "Europe/Rome",
            "munich, frankfurt" to "Europe/Berlin",
            "geneva" to "Europe/Zurich",
            "edinburgh" to "Europe/London",
            "wellington, christchurch" to "Pacific/Auckland",
            "easter island" to "Pacific/Easter",
            "ottawa" to "America/Toronto",
            "calgary" to "America/Edmonton",
            "washington, washington dc, dc, boston, miami, atlanta, philadelphia, new york city, nyc" to
                "America/New_York",
            "houston, dallas" to "America/Chicago",
            "salt lake city" to "America/Denver",
            "san francisco, seattle, las vegas, vegas, la, san diego" to "America/Los_Angeles",
            // The zones of the US by their own names. "Eastern Standard Time" is not here: it
            // is an hour off Eastern time while the clocks are forward.
            "eastern time, east coast" to "America/New_York",
            "central time" to "America/Chicago",
            "mountain time" to "America/Denver",
            "pacific time, west coast" to "America/Los_Angeles",
            // Names a zone's namesake shares with better-known places, each one a place of its
            // own, so the name alone asks which.
            "norfolk, chatham, jersey" to "America/New_York",
            "norfolk, chatham" to "Europe/London",
            "cordoba, merida" to "Europe/Madrid",
            "merida" to "America/Caracas",
        )

        /**
         * US states and Canadian provinces, which ICU does not map to zones. Each is every
         * zone in it, so one whose zones disagree right now asks for a city; and each is
         * somewhere a town can be, as a city is not.
         */
        private val STATES = listOf(
            "connecticut, delaware, district of columbia, georgia, maine, maryland, massachusetts, " +
                "new hampshire, new jersey, new york, north carolina, ohio, pennsylvania, rhode island, " +
                "south carolina, vermont, virginia, west virginia" to "America/New_York",
            "alabama, arkansas, illinois, iowa, louisiana, minnesota, mississippi, missouri, oklahoma, " +
                "wisconsin" to "America/Chicago",
            "colorado, montana, new mexico, utah, wyoming" to "America/Denver",
            "california, washington, washington state" to "America/Los_Angeles",
            // West Wendover keeps Mountain time, so a town said to be in Nevada may not be Pacific.
            "nevada" to "America/Los_Angeles America/Denver",
            "hawaii" to "Pacific/Honolulu",
            "alaska" to "America/Anchorage America/Juneau America/Sitka America/Yakutat America/Nome " +
                "America/Metlakatla America/Adak",
            "arizona" to "America/Phoenix America/Denver",
            "florida, tennessee" to "America/New_York America/Chicago",
            "idaho, oregon" to "America/Boise America/Los_Angeles",
            "indiana" to "America/Indiana/Indianapolis America/Chicago",
            "kentucky" to "America/Kentucky/Louisville America/Chicago",
            "michigan" to "America/Detroit America/Menominee",
            "kansas, nebraska, north dakota, south dakota, texas" to "America/Chicago America/Denver",
            "alberta" to "America/Edmonton",
            "manitoba" to "America/Winnipeg",
            "saskatchewan" to "America/Regina America/Swift_Current",
            "new brunswick" to "America/Moncton",
            "nova scotia, prince edward island" to "America/Halifax",
            "newfoundland" to "America/St_Johns",
            "newfoundland and labrador" to "America/St_Johns America/Goose_Bay",
            "quebec" to "America/Toronto America/Blanc-Sablon",
            "ontario" to "America/Toronto America/Winnipeg America/Atikokan",
            "british columbia" to "America/Vancouver America/Edmonton America/Dawson_Creek America/Fort_Nelson " +
                "America/Creston",
            "yukon" to "America/Whitehorse America/Dawson",
            "northwest territories" to "America/Yellowknife America/Inuvik",
            "nunavut" to "America/Iqaluit America/Rankin_Inlet America/Resolute America/Cambridge_Bay",
        )

        /**
         * Countries whose official time is not every zone ICU lists. China keeps Beijing time
         * throughout, though tzdb also has Xinjiang's unofficial Urumqi; Hong Kong and Macau
         * keep the same time.
         */
        private val OFFICIAL = mapOf("CN" to listOf("Asia/Shanghai", "Asia/Hong_Kong", "Asia/Macau"))

        /**
         * Zones whose present or future clocks a time-zone data release changed, by the first
         * release with the change. A phone with older data gives these the old rules' time, so
         * they are declined there. Each release that changes a zone's offsets from now on
         * belongs here.
         */
        private val CHANGED = mapOf(
            // Kazakhstan moved to UTC+5 on 2024-03-01.
            "Asia/Almaty" to "2024a",
            "Asia/Qostanay" to "2024a",
            // Paraguay kept -03 all year from 2024.
            "America/Asuncion" to "2025a",
            // British Columbia kept -07 from 2026-03-09.
            "America/Vancouver" to "2026b",
            // Alberta kept -06 from 2026-06-18; Morocco and Western Sahara keep +00 from 2026-09-20.
            "America/Edmonton" to "2026c",
            "Africa/Casablanca" to "2026c",
            "Africa/El_Aaiun" to "2026c",
            // The Northwest Territories keep -06 from 2026-08-21, and Manitoba -05 from 2026-10-31.
            "America/Yellowknife" to "2026e",
            "America/Inuvik" to "2026e",
            "America/Winnipeg" to "2026e",
            "America/Rainy_River" to "2026e",
        )

        /** Words no place name has, but "in five minutes" and "in military time" do. */
        private val NOT_PLACES = setOf(
            "time", "second", "seconds", "minute", "minutes", "hour", "hours", "day", "days", "week", "weeks",
            "fortnight", "month", "months", "year", "years", "decade", "decades", "morning", "afternoon",
            "evening", "night", "tonight", "tomorrow", "yesterday", "noon", "midnight", "calendar", "hijri",
            "islam", "islamic", "ramadan", "lunar", "military", "christmas", "easter", "words", "video", "game",
            "movie", "when", "here", "my",
        )

        /** Languages by the names people say, which Locale gives as Chinese, Persian, Bangla and Tagalog. */
        private val OTHER_LANGUAGES = setOf("mandarin", "cantonese", "farsi", "bengali", "filipino")

        /** No place's name starts "a" or "an": "in a fortnight", "in an hour". */
        private val ARTICLES = setOf("a", "an")

        /** The second name in "Myanmar (Burma)". */
        private val ALSO = Regex("""\s*\(([^)]*)\)""")

        /** "UTC+2", "GMT -5", "UTC+05:30", "UTC plus 3", with a typographic minus too. */
        private val OFFSET = Regex(
            """^\W*(?:the\s+)?(utc|gmt)\s*([+\-−]|plus\b|minus\b)\s*(\d{1,2})(?::?(\d{2}))?(?!\d)""",
            RegexOption.IGNORE_CASE,
        )

        private const val MINUTES_PER_HOUR = 60
        private const val MAX_OFFSET_MINUTES = 14 * MINUTES_PER_HOUR
    }
}

/** A place as it was said: its words, folded for lookup, and where each one sits. */
private class Phrase(private val text: String) {

    private val words = trimmed(wordsOf(text))

    val keys: List<String> = words.map { it.key }

    /** Digits or a sign: a number of minutes or days, or an offset, and never a name. */
    val hasDigits = text.any { it.isDigit() || it == '+' || it == '−' }

    /**
     * Where a city's words end and the words saying where it is begin: at the first comma,
     * as in "Sydney, Australia", or around an "in", as in "Tokyo in Japan".
     */
    val qualifier: Pair<Int, Int>? = (1 until words.size)
        .firstOrNull { keys[it] == "in" || ',' in text.substring(words[it - 1].end, words[it].start) }
        ?.let { if (keys[it] == "in") it to it + 1 else it to it }

    /** Where a place could be cut into a city and its country: only at [qualifier], if there is one. */
    fun cuts(): List<Pair<Int, Int>> = qualifier?.let { listOf(it) } ?: (1 until words.size).map { it to it }

    /** The folded words from [from] to [to], without a leading "the": "the UK" is "uk". */
    fun key(from: Int = 0, to: Int = words.size): String =
        keys.subList(from, to).let { if (it.firstOrNull() == "the") it.drop(1) else it }.joinToString(" ")

    /** The same words as they were said, to be said back. */
    fun said(from: Int = 0, to: Int = words.size): String = text.substring(words[from].start, words[to - 1].end)

    /** The place before a closing "time": "Tokyo" in "Tokyo time". */
    fun beforeTime(): Phrase? =
        if (keys.size > 1 && keys.last() == "time") Phrase(text.substring(0, words[words.size - 2].end)) else null
}

private class Word(val key: String, val start: Int, val end: Int, val initials: Boolean)

/** Said after a place, about when rather than where: "Sydney right now, please". */
private val TRAILING = listOf(
    "right now", "now", "at the moment", "currently", "today", "please", "thank you", "thanks", "again", "exactly",
    "by the way", "out loud",
).map { it.split(' ') }

private val TOKEN = Regex("""[\p{L}\p{M}'’]+""")
private val MARKS = Regex("""\p{M}+""")

/** "São Paulo", "SAO PAULO" and Sao_Paulo are all "sao paulo"; "Saint Lucia" is "st lucia". */
private fun keyOf(name: String): String = wordsOf(name.replace("&", " and ")).joinToString(" ") { it.key }

private fun wordsOf(text: String): List<Word> {
    val words = ArrayList<Word>()
    for (token in TOKEN.findAll(text)) {
        val key = Normalizer.normalize(token.value, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
            .filter { it in 'a'..'z' }
            .let { if (it == "saint") "st" else it }
        if (key.isEmpty()) continue
        val last = words.lastOrNull()
        // "D.C." and "U.S." arrive a letter at a time; joined up they are the names.
        if (key.length == 1 && last?.initials == true) {
            words[words.lastIndex] = Word(last.key + key, last.start, token.range.last + 1, initials = true)
        } else {
            words += Word(key, token.range.first, token.range.last + 1, initials = key.length == 1)
        }
    }
    return words
}

/** [words] without what [TRAILING] says, keeping at least [keep] of them. */
private tailrec fun trimmed(words: List<Word>, keep: Int = 1): List<Word> {
    val filler = TRAILING.firstOrNull { it.size <= words.size - keep && words.takeLast(it.size).map(Word::key) == it }
    return if (filler == null) words else trimmed(words.dropLast(filler.size), keep)
}
