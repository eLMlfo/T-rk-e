// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.service.voice

import java.util.Locale

/**
 * Pocket TTS Turkish'in duygu etiketleri ve metin hazırlığı (Windows: SpeechText.cs, Mac: SpeechScript.swift ile aynı
 * kurallar): sayılar yazıyla, her cümlenin ilk harfi küçük (model böyle eğitildi), duygu etiketi başta ("[kızgın] …").
 */
enum class LocalEmotion(val tag: String) { HAPPY("mutlu"), SAD("üzgün"), ANGRY("kızgın"), SURPRISED("şaşkın"), CALM("sakin") }

object TurkishSpeech {
    private val TR = Locale("tr", "TR")

    private val SAD = listOf("i'm sorry", "i am sorry", "forgive me", "i miss", "she's gone", "he's gone", "they're gone", "is dead",
        "are dead", "goodbye", "farewell", "it's my fault", "i failed", "i couldn't save", "rest in peace", "we lost", "i lost")
    private val SURPRISED = listOf("what", "whoa", "wow", "huh", "impossible", "no way", "how", "wait", "look out", "what's that", "holy shit", "oh my god")
    private val HAPPY = listOf("we did it", "yes", "finally", "haha", "ha", "hooray", "great", "amazing", "perfect", "thank you", "thanks",
        "welcome back", "well done", "nice", "awesome", "hell yeah", "let's go", "baby")
    private val ANGRY = listOf("stop", "enough", "get back", "get out", "back off", "shut up", "fool", "idiot", "damn", "die", "kill",
        "never", "how dare", "coward", "leave", "move", "now", "fight", "run", "fuck", "fucking", "shit", "asshole", "bitch",
        "motherfucker", "bastard", "screw you", "drop it", "hands up", "get down", "go go")
    private val CALM = listOf("easy", "calm down", "breathe", "take your time", "it's okay", "it's alright", "don't worry", "rest",
        "slowly", "quiet", "shh", "listen to me", "relax", "chill")

    /** İngilizce asıl replikten duygu; emin olunmayan cümle düz (etiketsiz) okunur. */
    fun guess(english: String): LocalEmotion? {
        val text = english.lowercase(Locale.ROOT)
        val words = text.split(Regex("[^\\p{L}'’]+")).filter { it.isNotEmpty() }.toHashSet()
        fun has(list: List<String>) = list.any { if (' ' in it) text.contains(it) else it in words }
        val letters = english.filter { it.isLetter() }
        val shouting = letters.length >= 4 && letters.count { it.isUpperCase() } > letters.length * 0.7
        if (text.contains("?!") || text.contains("!?")) return LocalEmotion.SURPRISED
        if (has(SAD)) return LocalEmotion.SAD
        if (text.contains('!') || shouting) {
            if (has(SURPRISED)) return LocalEmotion.SURPRISED
            if (has(HAPPY)) return LocalEmotion.HAPPY
            if (has(ANGRY) || shouting) return LocalEmotion.ANGRY
            return null
        }
        return if (has(CALM)) LocalEmotion.CALM else null
    }

    fun prepare(turkish: String, emotion: LocalEmotion?): String {
        var text = turkish.replace("…", "...").replace('“', '"').replace('”', '"').replace('’', '\'')
        text = spellOut(text)
        text = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        // Yığılmış işaretler modeli erken susturabiliyor; tek işarete iner.
        text = Regex("[?!]*\\?[?!]*").replace(text, "?")
        text = Regex("!{2,}").replace(text, "!")
        // Tamamen büyük harfli (bağıran) cümle küçük harfe: model büyük harfi harf harf okumasın.
        val letters = text.filter { it.isLetter() }
        if (letters.length >= 4 && letters.count { it.isUpperCase() } > letters.length * 0.7) text = text.lowercase(TR)
        text = lowercaseSentenceStarts(text)
        return if (emotion != null) "[${emotion.tag}] $text" else text
    }

    fun lowercaseSentenceStarts(text: String): String {
        val out = StringBuilder(text.length)
        var atStart = true
        for (c in text) {
            if (atStart && c.isLetter()) {
                out.append(c.toString().lowercase(TR))
                atStart = false
            } else {
                out.append(c)
                if (c == '.' || c == '!' || c == '?') atStart = true
            }
        }
        return out.toString()
    }

    private val ONES = arrayOf("", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz")
    private val TENS = arrayOf("", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan")
    private val SCALES = arrayOf("", "bin", "milyon", "milyar", "trilyon")

    fun words(number: Long): String {
        if (number == 0L) return "sıfır"
        if (number < 0) return "eksi " + words(-number)
        val groups = ArrayList<Int>()
        var rest = number
        while (rest > 0) { groups += (rest % 1000).toInt(); rest /= 1000 }
        if (groups.size > SCALES.size) return number.toString()
        val parts = ArrayList<String>()
        for (scale in groups.indices.reversed()) {
            val group = groups[scale]
            if (group == 0) continue
            if (scale == 1 && group == 1) { parts += "bin"; continue }
            parts += hundreds(group)
            if (scale > 0) parts += SCALES[scale]
        }
        return parts.joinToString(" ")
    }

    private fun hundreds(n: Int): String {
        val parts = ArrayList<String>()
        val h = n / 100; val t = n % 100 / 10; val o = n % 10
        if (h > 0) parts += if (h == 1) "yüz" else ONES[h] + " yüz"
        if (t > 0) parts += TENS[t]
        if (o > 0) parts += ONES[o]
        return parts.joinToString(" ")
    }

    private val NUMBER = Regex("(%)?(\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:([,:])(\\d+))?(%)?")

    fun spellOut(text: String): String = NUMBER.replace(text) { m ->
        val value = m.groupValues[2].replace(".", "").toLongOrNull() ?: return@replace m.value
        var spoken = words(value)
        val fraction = m.groups[4]?.value
        if (fraction != null) {
            val rest = fraction.toLongOrNull() ?: 0L
            if (m.groupValues[3] == ":") {
                if (rest > 0) spoken += " " + (if (fraction.startsWith('0')) "sıfır " else "") + words(rest)
            } else {
                val zeros = fraction.takeWhile { it == '0' }.length
                spoken += " virgül " + List(zeros) { "sıfır" }.joinToString(" ")
                if (rest > 0) spoken += (if (zeros > 0) " " else "") + words(rest)
                spoken = spoken.trimEnd()
            }
        }
        if (m.groups[1] != null || m.groups[5] != null) spoken = "yüzde $spoken"
        spoken
    }
}
