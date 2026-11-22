// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.service.voice

import android.content.Context
import java.util.Locale

/**
 * Telefon içi seste karakterlere ses dağıtımı (Windows: VoiceCast.cs, Mac: VoiceCast.swift ile aynı kurallar). Her ad
 * kalıcı olarak bir sese bağlanır; yeni karakter kendi cinsiyetindeki en az kullanılan sesi alır. Etiketsiz satırlar
 * anlatıcı sesiyle, tireyle değişen konuşmalar iki ses arasında sırayla okunur.
 */
class LocalCast(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("voice-cast-local", Context.MODE_PRIVATE)
    private val all = LocalTts.FEMALE_VOICES + LocalTts.MALE_VOICES
    private val narrator = "male_1"
    private val partner = "female_1"
    private var lastWasPartner = false

    @Synchronized
    fun voiceFor(speaker: String?, newTurn: Boolean): String {
        if (speaker != null) {
            lastWasPartner = false
            val key = speaker.uppercase(Locale.ROOT).trim()
            FIXED[key]?.let { return it }
            prefs.getString(key, null)?.let { return it }
            val voice = pick(gender(key))
            prefs.edit().putString(key, voice).apply()
            return voice
        }
        if (newTurn) lastWasPartner = !lastWasPartner
        return if (lastWasPartner) partner else narrator
    }

    private fun pick(female: Boolean?): String {
        val uses = all.associateWith { 0 }.toMutableMap()
        prefs.all.values.filterIsInstance<String>().forEach { uses[it] = (uses[it] ?: 0) + 1 }
        FIXED.values.forEach { uses[it] = (uses[it] ?: 0) + 1 }
        uses[narrator] = (uses[narrator] ?: 0) + 1
        return all.filter { female == null || (it.startsWith("female") == female) }
            .sortedWith(compareBy<String> { uses[it] ?: 0 }.thenBy { all.indexOf(it) }).first()
    }

    private fun gender(key: String): Boolean? {
        val lower = key.lowercase(Locale.ROOT)
        if (lower in FEMALE) return true
        if (lower in MALE) return false
        for (word in lower.split(' ', '-', '.').filter { it.isNotBlank() }) {
            if (word in FEMALE) return true
            if (word in MALE) return false
        }
        return null
    }

    private companion object {
        /** GTA 6'nın iki ana karakteri sabit seste: Jason ve Lucia hep aynı. */
        val FIXED = mapOf("JASON" to "male_2", "LUCIA" to "female_2")

        val FEMALE = setOf(
            "lucia", "bae-luxe", "roxy", "maureen", "amanda", "tracey", "sadie", "abigail", "mary-beth", "karen", "tilly",
            "freya", "faye", "angrboda", "sif", "thrud", "ellie", "abby", "dina", "tess", "marlene", "riley", "aloy", "elisabet",
            "beta", "mary", "jane", "mj", "may", "rio", "hailey", "lara", "jill", "claire", "ada", "ashley", "tifa", "aerith",
            "yuffie", "jessie", "yuna", "lulu", "rikku", "ciri", "yennefer", "triss", "kassandra", "aya", "evie", "lydia",
            "anna", "emma", "olivia", "sophia", "isabella", "mia", "amelia", "harper", "evelyn", "emily", "ella",
            "elizabeth", "sofia", "avery", "scarlett", "grace", "chloe", "victoria", "lily", "hannah", "zoe", "nora", "sarah",
            "rachel", "rebecca", "laura", "julia", "maria", "kate", "katie", "lucy", "alice", "eve", "helen", "linda", "susan",
            "nancy", "lisa", "betty", "margaret", "sandra", "kim", "mother", "mom", "mum", "queen", "princess", "lady",
            "girl", "woman", "sister", "aunt", "grandma", "witch", "goddess", "daughter", "wife", "dispatch", "operator",
        )
        val MALE = setOf(
            "jason", "cal", "boobie", "dre'quan", "raul", "brian", "michael", "franklin", "trevor", "lamar", "lester", "jimmy",
            "kratos", "atreus", "mimir", "baldur", "thor", "odin", "brok", "sindri", "tyr", "heimdall", "freyr", "joel",
            "tommy", "bill", "henry", "jesse", "lev", "isaac", "owen", "manny", "jerry", "peter", "miles", "harry", "otto",
            "kraven", "venom", "norman", "aaron", "leon", "chris", "albert", "wesker", "ethan", "cloud", "barret", "zack",
            "sephiroth", "noctis", "tidus", "auron", "geralt", "jaskier", "alexios", "bayek", "ezio", "altair", "connor",
            "edward", "arthur", "dutch", "john", "micah", "hosea", "nathan", "nate", "drake", "sully", "jin", "james",
            "robert", "william", "david", "richard", "joseph", "thomas", "charles", "daniel", "matthew", "anthony", "mark",
            "paul", "steven", "andrew", "kenneth", "kevin", "george", "jack", "jacob", "luke", "max", "father", "dad",
            "boy", "king", "prince", "lord", "sir", "man", "brother", "uncle", "grandpa", "son", "husband", "soldier",
            "guard", "captain", "commander", "general", "cop", "officer", "police", "deputy", "sheriff", "thug", "dealer",
        )
    }
}
