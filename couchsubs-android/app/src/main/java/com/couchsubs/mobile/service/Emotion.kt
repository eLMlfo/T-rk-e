// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.service

/**
 * Oyundaki İngilizce asıl replikten duygu: yapay zekâ sesine kısa bir oyunculuk tarifi ([style]) ve gerekirse cümlenin
 * başına bir ses olayı ([tag]: <laugh>, <sigh>, <gasp>). Ek istek yok, gecikme eklemez.
 */
enum class Emotion(val style: String, val tag: String = "") {
    FURIOUS("furious, shouting angrily"),
    PANIC("panicked and urgent, out of breath", "<gasp> "),
    SCARED("scared, voice trembling"),
    LAUGHING("laughing, amused", "<laugh> "),
    SARCASTIC("sarcastic, mocking"),
    PLEADING("pleading, desperate"),
    SAD("sad, emotional, close to tears", "<sigh> "),
    TIRED("tired, annoyed sigh", "<sigh> "),
    TENDER("warm, tender, affectionate"),
    WHISPER("whispering quietly"),
    EXCITED("excited and energetic"),
    QUESTION("curious, questioning"),
    HESITANT("hesitant, unsure"),
    COMMAND("firm and commanding"),
    NEUTRAL("casual and natural");

    companion object {
        private fun has(text: String, vararg words: String) = words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }

        fun of(source: String): Emotion {
            val t = source.lowercase()
            val letters = source.filter { it.isLetter() }
            val caps = letters.length >= 4 && letters.count { it.isUpperCase() } > letters.length * 0.7
            val bang = source.trimEnd().endsWith("!") || source.contains("!!")
            return when {
                has(t, "haha", "hahaha", "lol", "heh", "hehe") -> LAUGHING
                has(t, "shh", "quiet", "keep it down", "whisper") -> WHISPER
                has(t, "run", "hurry", "go go", "get down", "cops", "police", "move it", "look out", "watch out", "they're coming") && (bang || caps) -> PANIC
                caps || (bang && has(t, "fuck", "fucking", "shit", "damn", "asshole", "bitch", "motherfucker", "get out", "shut up", "screw you", "bastard")) -> FURIOUS
                has(t, "please", "i'm begging", "don't kill", "let me go", "i'm sorry", "forgive me") -> PLEADING
                has(t, "scared", "afraid", "oh god", "oh my god", "what was that", "help me") -> SCARED
                has(t, "babe", "baby", "honey", "love you", "sweetheart", "darling", "miss you") -> TENDER
                has(t, "sorry", "miss", "dead", "died", "funeral", "lost him", "lost her", "i can't") && !bang -> SAD
                has(t, "ugh", "whatever", "tired", "again?", "seriously") -> TIRED
                has(t, "yeah right", "oh great", "nice job", "real smooth", "sure you did") -> SARCASTIC
                bang && has(t, "yes", "hell yeah", "let's go", "awesome", "we did it", "woo", "wow") -> EXCITED
                bang -> COMMAND
                source.trimEnd().endsWith("?") -> QUESTION
                source.trimEnd().endsWith("...") || source.trimEnd().endsWith("…") -> HESITANT
                else -> NEUTRAL
            }
        }
    }
}
