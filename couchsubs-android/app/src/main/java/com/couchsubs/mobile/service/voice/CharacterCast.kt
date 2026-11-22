// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.service.voice

import android.content.Context
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Karakter → ses eşlemesi ("oyuncu kadrosu"). Her karaktere bir kez ses seçilir ve telefonda saklanır; karakter
 * oyun boyunca hep aynı sesle konuşur. Bilinen karakterlerin cinsiyeti tablodan, bilinmeyenlerinki (anahtar varsa)
 * Gemini'ye bir kez sorulur, yoksa addan tahmin edilir. Kadın ve erkek karakterler kendi ses havuzlarından,
 * mümkünse daha önce verilmemiş bir ses alır.
 */
class CharacterCast(context: Context, private val key: () -> String?) {
    private val prefs = context.applicationContext.getSharedPreferences("voice-cast", Context.MODE_PRIVATE)
    private val asking = HashSet<String>()
    private val background = Executors.newSingleThreadExecutor()

    /** Konuşan adı olmayan satırlar (ara yazılar, anlatıcı, adsız konuşmalar). */
    val narrator: String = "Charon"

    @Synchronized
    fun voiceFor(name: String?, game: String?): String {
        if (name == null) return narrator
        val id = name.uppercase(Locale.ROOT).trim()
        FIXED[id]?.let { return it }
        prefs.getString("voice.$id", null)?.let { return it }
        val gender = prefs.getString("gender.$id", null) ?: KNOWN_GENDER[id]
        if (gender == null && key() != null && asking.add(id)) {
            // İlk satır tahminle okunur; cevap gelince karakterin sesi kalıcı olarak seçilir.
            background.execute {
                val answer = key()?.let { GeminiSpeech.guessGender(it, id, game) }
                synchronized(this) {
                    if (answer != null) {
                        prefs.edit().putString("gender.$id", answer).remove("voice.$id").apply()
                    }
                }
            }
            return pick(id, guessFromName(id), save = false)
        }
        return pick(id, gender ?: guessFromName(id), save = true)
    }

    private fun pick(id: String, gender: String, save: Boolean): String {
        val pool = if (gender == "F") FEMALE else MALE
        val used = prefs.all.filterKeys { it.startsWith("voice.") }.values.toSet() + FIXED.values
        val start = abs(id.hashCode()) % pool.size
        val voice = (0 until pool.size).map { pool[(start + it) % pool.size] }.firstOrNull { it !in used } ?: pool[start]
        if (save) prefs.edit().putString("voice.$id", voice).apply()
        return voice
    }

    private fun guessFromName(id: String): String {
        val first = id.split(' ', '-', '.').firstOrNull { it.isNotBlank() } ?: id
        return if (first.length > 2 && first.last() in "AE" && first !in MALE_A) "F" else "M"
    }

    fun close() = background.shutdownNow()

    private companion object {
        /** Ana karakterler sabit: Jason sert ve kalın, Lucia kararlı. */
        val FIXED = mapOf(
            "JASON" to "Algenib", "LUCIA" to "Kore",
            "MICHAEL" to "Alnilam", "FRANKLIN" to "Puck", "TREVOR" to "Fenrir",
            "ARTHUR" to "Algenib", "DUTCH" to "Rasalgethi", "JOHN" to "Orus",
        )
        val KNOWN_GENDER = mapOf(
            "CAL" to "M", "BOOBIE" to "M", "DRE'QUAN" to "M", "RAUL" to "M", "BRIAN" to "M", "JASON" to "M",
            "BAE-LUXE" to "F", "ROXY" to "F", "LUCIA" to "F", "MAUREEN" to "F",
            "LAMAR" to "M", "LESTER" to "M", "AMANDA" to "F", "TRACEY" to "F", "JIMMY" to "M", "DAVE" to "M",
            "SADIE" to "F", "ABIGAIL" to "F", "MARY-BETH" to "F", "KAREN" to "F", "TILLY" to "F", "HOSEA" to "M",
            "OFFICER" to "M", "COP" to "M", "POLICE" to "M", "DISPATCH" to "F",
        )
        /** A ile biten erkek adları (tahmin yanılmasın). */
        val MALE_A = setOf("LUCA", "JOSHUA", "MUSTAFA", "ANDREA", "NIKOLA", "ELIJAH", "ZACHARIAH")
        val FEMALE = listOf("Leda", "Aoede", "Despina", "Callirrhoe", "Autonoe", "Erinome", "Laomedeia", "Achernar",
            "Gacrux", "Pulcherrima", "Vindemiatrix", "Sulafat", "Zephyr")
        val MALE = listOf("Orus", "Iapetus", "Umbriel", "Algieba", "Enceladus", "Schedar", "Achird", "Zubenelgenubi",
            "Sadachbia", "Sadaltager", "Puck", "Fenrir", "Rasalgethi", "Alnilam")
    }
}
