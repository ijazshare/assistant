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
import java.util.Locale

/** Where a place named aloud keeps its clock, as far as the phone can tell. */
sealed interface Place {
    /** One time right now: [zone]'s, said as being in [name]. */
    data class Known(val name: String, val zone: ZoneId) : Place

    /** A country, a state or a shared name whose zones disagree right now. */
    data class Several(val name: String) : Place

    /** Nowhere the phone has a zone for, including a city its country contradicts. */
    data class Unknown(val name: String) : Place
}

/**
 * Finds the time zone of a place named aloud, for the world clock in [LocalAnswers].
 *
 * The names are the platform's: the city in every zone ID (Africa/Johannesburg) and every
 * country's English name, whose zones come from ICU. [ALIASES] adds only what neither
 * names. ICU's exemplar city names are left out: beside java.time's IDs they add almost
 * nothing but the towns [cities] leaves out on purpose.
 *
 * A place is answered only if every zone it could mean keeps the same time right now.
 * Australia does not, so it gets asked for a city; nor does "Washington", a state and a
 * capital three hours apart. A city named with its country has to be in it: "Perth,
 * Scotland" is not the Perth the phone knows, so the phone does not know it.
 *
 * [zonesOf] is passed in because android.icu is only a stub in JVM unit tests.
 */
class Places(private val zonesOf: (region: String) -> Collection<String>) {

    /**
     * Every name, folded by [keyOf], and what it could mean: zone IDs, and the codes of
     * countries, whose zones are only fetched once a question names one.
     */
    private val index: Map<String, Set<String>> by lazy {
        (cities() + countries() + aliases()).groupBy({ keyOf(it.first) }, { it.second })
            .mapValues { (_, meanings) -> meanings.toSet() }
    }

    /** Every language by its English name: "what time is it in Spanish" is not about Spain. */
    private val languages: Set<String> by lazy {
        Locale.getISOLanguages().map { keyOf(Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH)) }.toSet()
    }

    /** The place in [spoken], or null if it is not one: "five minutes", "Spanish". */
    fun find(spoken: String, at: Instant): Place? {
        val phrase = Phrase(spoken)
        if (phrase.key().isEmpty()) return null
        val whole = named(phrase.key())
        val place = if (whole.isNotEmpty()) {
            settle(whole, phrase.said(), at)
        } else {
            phrase.cuts().firstNotNullOfOrNull { within(phrase, it, at) }
        }
        return place ?: Place.Unknown(phrase.said()).takeIf { isPlaceLike(phrase) }
    }

    /**
     * A city and where it is, cut apart at [cut]: "Sydney, Australia", "Johannesburg South
     * Africa". Null if the words after the cut are not a place, or there is no comma and
     * the words before it are not one either.
     */
    private fun within(phrase: Phrase, cut: Int, at: Instant): Place? {
        val around = named(phrase.key(cut)).ifEmpty { return null }
        val city = named(phrase.key(0, cut))
        return when {
            // A town with no zone of its own still keeps its country's time, if the
            // country has only the one.
            city.isEmpty() && cut == phrase.comma ->
                if (oneTime(around, at)) Place.Known(phrase.said(), around.first()) else Place.Unknown(phrase.said())

            // Without a comma both halves have to be names: New South Wales is not some
            // "New South" in Wales.
            city.isEmpty() -> null

            // The city wins, but only if it lies in what was named with it.
            else -> city.filter { zone -> around.any { it.rules == zone.rules } }
                .ifEmpty { return Place.Unknown(phrase.said()) }
                .let { settle(it, phrase.said(0, cut), at) }
        }
    }

    /** Every zone [key] could mean, or none. */
    private fun named(key: String): List<ZoneId> = index[key].orEmpty()
        .flatMap { if ('/' in it) listOf(it) else zonesOf(it) }
        .mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() }
        .distinct()

    private fun settle(zones: List<ZoneId>, name: String, at: Instant): Place =
        if (oneTime(zones, at)) Place.Known(name, zones.first()) else Place.Several(name)

    private fun oneTime(zones: List<ZoneId>, at: Instant) = zones.map { it.rules.getOffset(at) }.toSet().size == 1

    /**
     * Whether words that name nothing known are still a place, and so get a plain "I don't
     * know". "In five minutes", "in military time" and "in Spanish" follow the same question
     * without naming anywhere: those are other questions, and stay the model's.
     */
    private fun isPlaceLike(phrase: Phrase) =
        !phrase.hasDigits && phrase.keys.none { it in NOT_PLACES } && phrase.key() !in languages

    /**
     * The city in each two-part zone ID: Africa/Johannesburg is Johannesburg's. A third
     * part is a town inside a state or province, whose better-known namesake is usually
     * somewhere else: America/Argentina/San_Juan would answer Argentina's time for Puerto
     * Rico's capital.
     */
    private fun cities() = ZoneId.getAvailableZoneIds()
        .filter { it.count { c -> c == '/' } == 1 && it.substringBefore('/') in AREAS && it !in NOT_CITIES }
        .map { it.substringAfter('/') to it }

    /** Each country by its English name: "Myanmar (Burma)" by either. */
    private fun countries() = Locale.getISOCountries().flatMap { region ->
        val name = Locale.Builder().setRegion(region).build().getDisplayCountry(Locale.ENGLISH)
        listOfNotNull(name.replace(ALSO, ""), ALSO.find(name)?.groupValues?.get(1)).map { it to region }
    }

    private fun aliases() = ALIASES.flatMap { (names, meanings) ->
        names.split(", ").flatMap { name -> meanings.split(' ').map { name to it } }
    }

    companion object {
        /**
         * The phone's own data. ICU is complete from API 24 and minSdk is 33; in JVM unit
         * tests android.jar only stubs it, so they pass a lookup of their own.
         */
        val device: Places by lazy {
            Places { region -> TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, region, null) }
        }

        /** The first part of a zone ID that is followed by a city, rather than Etc/ or US/. */
        private val AREAS = setOf(
            "Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific",
        )

        /**
         * Old zone IDs kept for regions rather than cities. Australia/West is Perth and
         * would make "the west" a place, and Pacific/Samoa is Pago Pago, a day behind the
         * country called Samoa.
         */
        private val NOT_CITIES = setOf(
            "Australia/ACT", "Australia/LHI", "Australia/NSW", "Australia/North", "Australia/Queensland",
            "Australia/South", "Australia/Tasmania", "Australia/Victoria", "Australia/West",
            "Australia/Yancowinna", "Pacific/Samoa",
        )

        /**
         * What the platform does not name: some names, and the zones or countries they mean.
         * A name in several entries means all of them, which is how "Georgia" stays both a
         * state and a country, and how a state that spans zones asks for a city.
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
            "utc, gmt" to "Etc/UTC",
            // Cities that share a zone without being its namesake.
            "mumbai, bombay, delhi, new delhi, bangalore, bengaluru, chennai" to "Asia/Kolkata",
            "lahore, islamabad, rawalpindi, peshawar" to "Asia/Karachi",
            "mecca, makkah, medina, madinah, jeddah" to "Asia/Riyadh",
            "abu dhabi" to "Asia/Dubai",
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
            "ottawa" to "America/Toronto",
            "calgary" to "America/Edmonton",
            "washington, washington dc, boston, miami, atlanta, philadelphia, new york city, nyc" to "America/New_York",
            "houston, dallas" to "America/Chicago",
            "salt lake city" to "America/Denver",
            "san francisco, seattle, las vegas, san diego, washington state" to "America/Los_Angeles",
            // US states and Canadian provinces, which ICU does not map to zones. Each is
            // every zone in it, so one whose zones disagree right now asks for a city.
            "connecticut, delaware, district of columbia, georgia, maine, maryland, massachusetts, " +
                "new hampshire, new jersey, new york, north carolina, ohio, pennsylvania, rhode island, " +
                "south carolina, vermont, virginia, west virginia" to "America/New_York",
            "alabama, arkansas, illinois, iowa, louisiana, minnesota, mississippi, missouri, oklahoma, " +
                "wisconsin" to "America/Chicago",
            "colorado, montana, new mexico, utah, wyoming" to "America/Denver",
            "california, nevada, washington" to "America/Los_Angeles",
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
            "northwest territories" to "America/Edmonton America/Inuvik",
            "nunavut" to "America/Iqaluit America/Rankin_Inlet America/Resolute America/Cambridge_Bay",
        )

        /** Words no place name has, but "in five minutes" and "in military time" do. */
        private val NOT_PLACES = setOf(
            "time", "second", "seconds", "minute", "minutes", "hour", "hours", "day", "days", "week", "weeks",
            "month", "months", "year", "years", "calendar", "hijri", "when", "here", "my",
        )

        /** The second name in "Myanmar (Burma)". */
        private val ALSO = Regex("""\s*\(([^)]*)\)""")
    }
}

/** A place as it was said: its words, folded for lookup, and where each one sits. */
private class Phrase(private val text: String) {

    private val words = trimmed(wordsOf(text))

    val keys: List<String> = words.map { it.key }

    val hasDigits = text.any { it.isDigit() }

    /** The word a comma comes just before, as in "Sydney, Australia", if one does. */
    val comma: Int? = (1 until words.size).firstOrNull { ',' in text.substring(words[it - 1].end, words[it].start) }

    /** Where a place could be cut into a city and its country: only at the comma, if there is one. */
    fun cuts(): List<Int> = comma?.let { listOf(it) } ?: (1 until words.size).toList()

    /** The folded words from [from] to [to], without a leading "the": "the UK" is "uk". */
    fun key(from: Int = 0, to: Int = words.size): String =
        keys.subList(from, to).let { if (it.firstOrNull() == "the") it.drop(1) else it }.joinToString(" ")

    /** The same words as they were said, to be said back. */
    fun said(from: Int = 0, to: Int = words.size): String = text.substring(words[from].start, words[to - 1].end)
}

private class Word(val key: String, val start: Int, val end: Int, val initials: Boolean)

/** Said after a place, about when rather than where: "Sydney right now, please". */
private val TRAILING = listOf("right now", "now", "at the moment", "currently", "today", "please").map { it.split(' ') }

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

private tailrec fun trimmed(words: List<Word>): List<Word> {
    val filler = TRAILING.firstOrNull { it.size < words.size && words.takeLast(it.size).map(Word::key) == it }
    return if (filler == null) words else trimmed(words.dropLast(filler.size))
}
