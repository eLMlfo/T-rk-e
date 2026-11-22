// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

/**
 * Telefon modunda Türkçe çeviriyi telefonun konuşma motoruyla (Android TextToSpeech) sesli okur; oyuncu gözünü
 * TV'den ayırmadan altyazıyı dinler.
 *
 * - **En iyi Türkçe ses:** telefondaki Türkçe seslerden kalitesi en yüksek olanı seçilir (Google'ın internet sesleri
 *   gömülü seslerden çok daha doğaldır; internet yoksa motor gömülü sese döner).
 * - **Altyazıya yetişen hız:** satırların ne sıklıkla geldiği ölçülür; cümle bir sonraki satırdan önce bitecek kadar
 *   hızlandırılır, ama anlaşılır kalması için üst sınır vardır.
 * - **Duygu:** ünlem, soru, üç nokta ve büyük harfle bağırma cümlenin tonunu ve hızını değiştirir.
 * - **Karakterlere farklı ses:** konuşan adı ("JASON:") varsa her karaktere hep aynı kalan ayrı bir ses ve ton verilir.
 */
class SubtitleVoice(context: Context, private val settings: Settings) {
    /** Ayarlar her satırda yeniden okunur; çeviri sürerken değiştirilebilir. */
    interface Settings {
        /** 0 normal, 1 hızlı, 2 çok hızlı. */
        val speed: Int
        val characterVoices: Boolean
    }

    @Volatile private var ready = false
    private val app = context.applicationContext
    private var voices: List<Voice> = emptyList()
    private var mainVoice: Voice? = null
    private val characterVoice = HashMap<String, Pair<Voice?, Float>>()
    private var lastLineAt = 0L
    private var gapMs = DEFAULT_GAP_MS.toDouble()

    private fun warn(message: String) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }

    private val tts: TextToSpeech = TextToSpeech(app) { status -> onInit(status) }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech başlatılamadı: $status")
            warn("Sesli okuma açılamadı: telefonda konuşma motoru yok. Play Store'dan \"Google Konuşma Hizmetleri\"ni kur.")
            return
        }
        val result = tts.setLanguage(TURKISH)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Türkçe ses yok: $result")
            warn("Türkçe ses yüklü değil: Ayarlar > Metin okuma > Google > Türkçe sesi indir.")
            return
        }
        // Kurulu Türkçe sesler, en kalitelisi önce (aynı kalitede internet sesi önce: daha doğal).
        voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == "tr" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenByDescending { it.isNetworkConnectionRequired }.thenBy { it.name })
        mainVoice = voices.firstOrNull()
        mainVoice?.let { runCatching { tts.voice = it } }
        Log.i(TAG, "Türkçe sesler: ${voices.joinToString { "${it.name}(${it.quality})" }}; seçilen ${mainVoice?.name}")
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        ready = true
    }

    /**
     * [source] oyundaki İngilizce satır (konuşan adı buradan alınır), [translation] Türkçesi. Yeni satır eskisini keser;
     * hız ayarlandığı için çoğu cümle kesilmeden biter.
     */
    @Synchronized
    fun say(source: String, translation: String) {
        if (!ready) return
        val now = SystemClock.elapsedRealtime()
        if (lastLineAt > 0) {
            val gap = (now - lastLineAt).coerceIn(MIN_GAP_MS, MAX_GAP_MS)
            gapMs = gapMs * 0.6 + gap * 0.4
        }
        lastLineAt = now

        val speaker = speakerOf(source) ?: speakerOf(translation)
        val sentence = stripLabel(translation)
        if (sentence.isEmpty()) return

        // Hız: ayardaki taban hız; cümle bir sonraki satıra yetişmiyorsa üst sınıra kadar hızlanır.
        val base = when (settings.speed) { 1 -> 1.15f; 2 -> 1.3f; else -> 1.0f }
        val neededMs = sentence.length / CHARS_PER_SECOND * 1000.0
        val availableMs = gapMs * 0.9
        val catchUp = (neededMs / (availableMs * base)).toFloat().coerceAtLeast(1f)
        var rate = (base * catchUp).coerceAtMost(MAX_RATE)
        var pitch = 1.0f

        // Karakter sesi.
        var voice = mainVoice
        if (speaker != null && settings.characterVoices) {
            val (v, p) = characterVoice.getOrPut(speaker) { pickCharacter(speaker) }
            voice = v ?: mainVoice
            pitch = p
        }

        // Duygu: cümlenin noktalaması ve bağırması.
        val letters = sentence.filter { it.isLetter() }
        val shouting = letters.length >= 4 && letters.count { it.isUpperCase() } > letters.length * 0.8
        when {
            shouting || sentence.endsWith("!") || sentence.contains("!!") -> { pitch *= 1.12f; rate *= 1.08f }
            sentence.endsWith("?") -> pitch *= 1.06f
            sentence.endsWith("...") || sentence.endsWith("…") -> { pitch *= 0.95f; rate *= 0.92f }
        }
        val spoken = if (shouting) sentence.lowercase(TURKISH).replaceFirstChar { it.titlecase(TURKISH) } else sentence

        if (voice != null && voice != tts.voice) runCatching { tts.voice = voice }
        tts.setSpeechRate(rate.coerceIn(0.8f, MAX_RATE + 0.1f))
        tts.setPitch(pitch.coerceIn(0.75f, 1.35f))
        tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "couchsubs-line")
    }

    /** Her karaktere hep aynı ses ve ton: adın özetinden seçilir, oturumdan oturuma değişmez. */
    private fun pickCharacter(name: String): Pair<Voice?, Float> {
        val hash = abs(name.hashCode())
        val pitches = floatArrayOf(1.0f, 0.88f, 1.12f, 0.94f, 1.06f, 0.82f, 1.18f)
        val pitch = pitches[hash % pitches.size]
        // Ses seçimi: aynı kalitedeki seçenekler arasından (kötü kalite sese düşmesin).
        val good = voices.filter { it.quality >= (mainVoice?.quality ?: 0) && it.isNetworkConnectionRequired == mainVoice?.isNetworkConnectionRequired }
        val voice = if (good.size > 1) good[hash % good.size] else mainVoice
        return voice to pitch
    }

    private fun speakerOf(text: String): String? {
        val colon = text.indexOf(':')
        if (colon !in 2..20) return null
        val label = text.substring(0, colon).trim()
        val letters = label.filter { it.isLetter() }
        if (letters.isEmpty() || letters.any { it.isLowerCase() }) return null
        return label.uppercase(Locale.ROOT)
    }

    private fun stripLabel(text: String): String {
        val colon = text.indexOf(": ")
        return (if (colon in 1..20 && speakerOf(text) != null) text.substring(colon + 2) else text).trim()
    }

    fun close() {
        ready = false
        runCatching { tts.stop(); tts.shutdown() }
    }

    private companion object {
        const val TAG = "SubtitleVoice"
        val TURKISH = Locale("tr", "TR")
        /** Rahat Türkçe konuşmada saniyede yaklaşık harf (boşluklar dahil), hız 1.0'da. */
        const val CHARS_PER_SECOND = 14.0
        /** Bunun üstü anlaşılmaz olur. */
        const val MAX_RATE = 1.75f
        const val DEFAULT_GAP_MS = 3500L
        const val MIN_GAP_MS = 1200L
        const val MAX_GAP_MS = 7000L
    }
}
