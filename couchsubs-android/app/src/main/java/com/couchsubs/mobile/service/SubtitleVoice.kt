// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.service

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Telefon modunda Türkçe çeviriyi telefonun kendi konuşma motoruyla (Android TextToSpeech) sesli okur.
 * Oyuncu gözünü TV'den ayırmadan altyazıyı dinler. İndirme gerekmez; Türkçe ses telefonda yoksa
 * sessizce kapanır. Yeni satır gelince eski cümle kesilir (dublaj altyazıyla birlikte gider, masaüstündeki gibi).
 */
class SubtitleVoice(context: Context, private val rate: Float = 1.1f) {
    @Volatile private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech başlatılamadı: $status")
            return@TextToSpeech
        }
        val result = engine().setLanguage(Locale("tr", "TR"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Türkçe ses yok: $result")
            return@TextToSpeech
        }
        engine().setSpeechRate(rate)
        engine().setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        ready = true
    }

    private fun engine() = tts

    /** Konuşan adı ("ARTHUR: …") okunmaz; yalnız cümle. */
    fun say(text: String) {
        if (!ready) return
        val label = text.indexOf(": ")
        val sentence = (if (label in 1..20) text.substring(label + 2) else text).trim()
        if (sentence.isEmpty()) return
        tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "couchsubs-line")
    }

    fun close() {
        ready = false
        runCatching { tts.stop(); tts.shutdown() }
    }

    private companion object { const val TAG = "SubtitleVoice" }
}
