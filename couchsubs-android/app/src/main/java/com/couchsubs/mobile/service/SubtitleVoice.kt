// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.widget.Toast
import com.couchsubs.mobile.service.voice.CharacterCast
import com.couchsubs.mobile.service.voice.GeminiSpeech
import com.couchsubs.mobile.service.voice.LocalCast
import com.couchsubs.mobile.service.voice.LocalTts
import com.couchsubs.mobile.service.voice.TurkishSpeech
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Türkçe çeviriyi sesli okur (telefon modunda gözü TV'de tutmak için).
 *
 * Sıra, kesme yok: her satır sıraya girer ve sonuna kadar okunur; yeni satır eskisini kesmez. Sıra birikirse okuma
 * hızlanır (yapay zekâ sesinde perde korunarak); çok birikirse en eski bekleyen satır bütünüyle atlanır, çalan cümle
 * yarıda kalmaz. Çok bekleyip bayatlamış satır da atlanır.
 *
 * İki ses motoru:
 * - Yapay zekâ (Gemini TTS, anahtar gerekir): her karaktere ayrı gerçekçi ses, oyundaki İngilizce repliğin duygusuyla
 *   oyunculuk; ses akışlı gelir ve sıradaki satır önceden hazırlanır, gecikme kısalır.
 * - Telefon (Android TextToSpeech): internetsiz, ücretsiz; yapay zekâ kotası dolarsa ya da hata verirse otomatik
 *   olarak buna dönülür.
 */
class SubtitleVoice(context: Context, private val settings: Settings) {
    interface Settings {
        /** 0 normal, 1 hızlı, 2 çok hızlı (taban hız; birikince ayrıca hızlanır). */
        val speed: Int
        val characterVoices: Boolean
        /** 0 telefon, 1 Gemini hızlı, 2 Gemini en kaliteli, 3 telefon içi yapay zekâ (internetsiz, Pocket TTS Turkish). */
        val engine: Int
        val geminiKey: String?
    }

    private class Line(
        val speaker: String?, val text: String, val source: String, val game: String?, val context: List<String>,
        val newTurn: Boolean = false,
    ) {
        val at: Long = SystemClock.elapsedRealtime()
        /** Yapay zekâ sesinin PCM'i üretildikçe buraya yazılır. */
        val pcm = ByteArrayOutputStream()
        @Volatile var requested = false
        @Volatile var done = false
        @Volatile var failed = false
        @Volatile var cancelled = false
        @Volatile var started = false
        val lock = Object()
    }

    private val app = context.applicationContext
    private val queue = ArrayDeque<Line>()
    private val recent = ArrayDeque<String>()
    private val queueLock = Object()
    @Volatile private var running = true
    @Volatile private var aiPausedUntil = 0L
    @Volatile private var lastLineAt = 0L
    @Volatile private var gapMs = 3_500.0
    private fun averageGap() = gapMs
    private val synth = Executors.newFixedThreadPool(3)
    /** Telefon içi motor: işlemciyi paylaşmasın diye cümleler tek tek (sıradaki, çalan sırasında hazırlanır). */
    private val localSynth = Executors.newSingleThreadExecutor()
    private val localCast = LocalCast(app)
    private val cast = CharacterCast(app) { settings.geminiKey?.takeIf { it.isNotBlank() } }

    // Telefon sesi
    @Volatile private var phoneReady = false
    private var phoneVoices: List<Voice> = emptyList()
    private var phoneMain: Voice? = null
    @Volatile private var utteranceDone: CountDownLatch? = null
    private val tts: TextToSpeech = TextToSpeech(app) { status -> onPhoneInit(status) }

    // Yapay zekâ sesi
    private var track: AudioTrack? = null
    private var framesWritten = 0L

    init {
        Thread({ playLoop() }, "CouchSubs voice").apply { isDaemon = true; start() }
        LocalTts.init(app)
        // Telefon içi motor seçiliyse çeviri başlarken açılır; ilk cümle beklemesin.
        if (settings.engine == 3 && LocalTts.modelReady()) localSynth.execute { LocalTts.start() }
    }

    private fun warn(message: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
    }

    private fun onPhoneInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech başlatılamadı: $status")
            if (!aiUsable()) warn("Sesli okuma açılamadı: telefonda konuşma motoru yok. Play Store'dan \"Google Konuşma Hizmetleri\"ni kur.")
            return
        }
        val result = tts.setLanguage(TURKISH)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (!aiUsable()) warn("Türkçe ses yüklü değil: Ayarlar > Metin okuma > Google > Türkçe sesi indir.")
            return
        }
        phoneVoices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == "tr" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenByDescending { it.isNetworkConnectionRequired }.thenBy { it.name })
        phoneMain = phoneVoices.firstOrNull()
        phoneMain?.let { runCatching { tts.voice = it } }
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceDone?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceDone?.countDown() }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceDone?.countDown() }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceDone?.countDown() }
        })
        phoneReady = true
    }

    private fun aiUsable() = when (settings.engine) {
        0 -> false
        3 -> LocalTts.modelReady()
        else -> !settings.geminiKey.isNullOrBlank() && SystemClock.elapsedRealtime() >= aiPausedUntil
    }

    /** [source] oyundaki İngilizce satır (konuşan adı buradan alınır), [translation] Türkçesi, [game] paketin adı. */
    fun say(source: String, translation: String, game: String? = null) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (lastLineAt > 0) gapMs = gapMs * 0.6 + (now - lastLineAt).coerceIn(1_000L, 8_000L) * 0.4
        lastLineAt = now
        val speaker = speakerOf(source) ?: speakerOf(translation)
        val text = stripLabel(translation)
        if (text.isEmpty()) return
        val line: Line
        synchronized(queueLock) {
            val dash = source.trimStart().let { it.startsWith("-") || it.startsWith("–") || it.startsWith("—") }
            line = Line(speaker, text.trimStart('-', '–', '—', ' '), stripLabel(source), game, recent.toList(), dash)
            recent.addLast((speaker?.let { "$it: " } ?: "") + stripLabel(source))
            while (recent.size > 3) recent.removeFirst()
            queue.addLast(line)
            // Çok birikirse en eski bekleyen (henüz çalmaya başlamamış) satır atlanır.
            while (queue.count { !it.started } > MAX_WAITING) {
                val oldest = queue.firstOrNull { !it.started } ?: break
                oldest.cancelled = true
                queue.remove(oldest)
            }
            queueLock.notifyAll()
        }
        if (aiUsable()) startSynthesis(line)
    }

    private fun startSynthesis(line: Line) {
        if (settings.engine == 3) return startLocal(line)
        val key = settings.geminiKey ?: return
        val model = if (settings.engine == 2) GeminiSpeech.MODEL else GeminiSpeech.MODEL_FAST
        val voice = if (settings.characterVoices) cast.voiceFor(line.speaker, line.game) else cast.narrator
        val style = styleFor(line)
        line.requested = true
        synth.execute {
            try {
                if (!line.cancelled) {
                    GeminiSpeech.stream(key, model, voice, Emotion.of(line.source).tag + line.text, style, { line.cancelled || !running }) { chunk ->
                        synchronized(line.lock) { line.pcm.write(chunk); line.lock.notifyAll() }
                    }
                }
            } catch (error: GeminiSpeech.RateLimited) {
                Log.w(TAG, "Gemini kota: ${error.message}")
                if (SystemClock.elapsedRealtime() >= aiPausedUntil) warn("Yapay zekâ sesinin ücretsiz kotası doldu; bir dakikalığına telefon sesi kullanılıyor.")
                aiPausedUntil = SystemClock.elapsedRealtime() + 60_000
                line.failed = true
            } catch (error: GeminiSpeech.Rejected) {
                Log.w(TAG, "Gemini red ${error.status}: ${error.message}")
                if (error.status == 400 || error.status == 401 || error.status == 403 || error.status == 404) {
                    val detail = Regex("\"message\"\\s*:\\s*\"([^\"]{0,120})").find(error.message.orEmpty())?.groupValues?.get(1).orEmpty()
                    if (SystemClock.elapsedRealtime() >= aiPausedUntil) warn("Yapay zekâ sesi çalışmadı (${error.status}), telefon sesi kullanılıyor. $detail")
                    aiPausedUntil = SystemClock.elapsedRealtime() + 5 * 60_000
                }
                line.failed = true
            } catch (error: Throwable) {
                Log.w(TAG, "Gemini ses", error)
                line.failed = true
            } finally {
                synchronized(line.lock) { line.done = true; line.lock.notifyAll() }
            }
        }
    }

    /** Telefon içi motor: duygu etiketi İngilizce asıl replikten, ses karakter kadrosundan. */
    private fun startLocal(line: Line) {
        val voice = if (settings.characterVoices) localCast.voiceFor(line.speaker, line.newTurn) else "male_1"
        val text = TurkishSpeech.prepare(line.text, TurkishSpeech.guess(line.source))
        line.requested = true
        localSynth.execute {
            try {
                if (!line.cancelled && running) {
                    LocalTts.stream(text, voice, { line.cancelled || !running }) { chunk ->
                        synchronized(line.lock) { line.pcm.write(chunk); line.lock.notifyAll() }
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "telefon içi ses", error)
                line.failed = true
                (LocalTts.state.value as? LocalTts.State.Failed)?.let { warnOnce(it.message) }
            } finally {
                synchronized(line.lock) { line.done = true; line.lock.notifyAll() }
            }
        }
    }

    @Volatile private var lastWarning = ""
    private fun warnOnce(message: String) {
        if (message == lastWarning) return
        lastWarning = message
        warn(message)
    }

    /**
     * Oyunculuk talimatı: kısa ve net (uzun talimat hem gecikmeyi artırır hem modeli şaşırtır). Duygu, oyundaki
     * İngilizce asıl repliğin noktalamasından ve sözcüklerinden çıkarılır ([Emotion]).
     */
    private fun styleFor(line: Line): String {
        val emotion = Emotion.of(line.source)
        val who = line.speaker?.let { "as $it, " } ?: ""
        return "${emotion.style}; ${who}real person in a gritty crime drama, natural spoken Turkish, fast pace, no pauses"
    }

    private fun playLoop() {
        while (running) {
            val line: Line = synchronized(queueLock) {
                while (running && queue.isEmpty()) queueLock.wait()
                if (!running) return
                queue.first().also { it.started = true }
            }
            try {
                val age = SystemClock.elapsedRealtime() - line.at
                if (!line.cancelled && age < STALE_MS) {
                    val waiting = synchronized(queueLock) { queue.size - 1 }
                    val tryAi = line.requested && !(line.done && line.failed && line.pcm.size() == 0)
                    if (!(tryAi && playAi(line, waiting))) speakPhone(line, waiting)
                }
            } catch (error: InterruptedException) {
                return
            } catch (error: Throwable) {
                Log.w(TAG, "oynatma", error)
            } finally {
                synchronized(queueLock) { queue.remove(line) }
            }
        }
    }

    /** Akışlı PCM'i çalar; hiç ses gelmezse false (satır telefon sesiyle okunur). */
    private fun playAi(line: Line, waiting: Int): Boolean {
        val out = ensureTrack() ?: return false
        val base = when (settings.speed) { 1 -> 1.1f; 2 -> 1.2f; else -> 1.0f }
        val speed = (base + waiting * 0.2f).coerceAtMost(1.5f)
        runCatching { out.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
        var offset = 0
        val firstDeadline = SystemClock.elapsedRealtime() +
            if (settings.engine == 3) (if (LocalTts.ready) LOCAL_FIRST_AUDIO_TIMEOUT_MS else LOCAL_START_TIMEOUT_MS) else FIRST_AUDIO_TIMEOUT_MS
        while (running && !line.cancelled) {
            var timedOut = false
            val chunk: ByteArray = synchronized(line.lock) {
                while (running && !line.done && line.pcm.size() <= offset) {
                    if (offset == 0 && SystemClock.elapsedRealtime() > firstDeadline) { timedOut = true; break }
                    line.lock.wait(50)
                }
                val all = line.pcm.toByteArray()
                if (all.size > offset) all.copyOfRange(offset, all.size) else ByteArray(0)
            }
            if (timedOut) { line.cancelled = true; return false }
            if (chunk.isEmpty()) break
            val even = chunk.size - chunk.size % 2
            if (even == 0) { if (line.done) break else continue }
            out.write(chunk, 0, even)
            offset += even
            framesWritten += even / 2
        }
        if (offset == 0) return false
        drain(out)
        return true
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        return runCatching {
            val min = AudioTrack.getMinBufferSize(GeminiSpeech.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(GeminiSpeech.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min * 2, GeminiSpeech.SAMPLE_RATE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().also { it.play(); track = it; framesWritten = 0 }
        }.onFailure { Log.w(TAG, "AudioTrack", it) }.getOrNull()
    }

    /** Yazılan ses bitene kadar bekler (sonraki satır üstüne binmesin). */
    private fun drain(out: AudioTrack) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (running && SystemClock.elapsedRealtime() < deadline) {
            val head = out.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head >= framesWritten) break
            Thread.sleep(20)
        }
    }

    private fun speakPhone(line: Line, waiting: Int) {
        if (!phoneReady) return
        val base = when (settings.speed) { 1 -> 1.15f; 2 -> 1.3f; else -> 1.0f }
        val gap = averageGap()
        val needed = line.text.length / 14.0 * 1000.0
        val catchUp = if (gap > 0) (needed / (gap * 0.9)).toFloat() else 1f
        var rate = maxOf(base + waiting * 0.25f, base * catchUp).coerceAtMost(MAX_PHONE_RATE)
        var pitch = 1.0f
        var voice = phoneMain
        if (line.speaker != null && settings.characterVoices) {
            val hash = abs(line.speaker.hashCode())
            pitch = PITCHES[hash % PITCHES.size]
            val good = phoneVoices.filter { it.quality >= (phoneMain?.quality ?: 0) && it.isNetworkConnectionRequired == phoneMain?.isNetworkConnectionRequired }
            if (good.size > 1) voice = good[hash % good.size]
        }
        val sentence = line.text
        val letters = sentence.filter { it.isLetter() }
        val shouting = letters.length >= 4 && letters.count { it.isUpperCase() } > letters.length * 0.8
        when {
            shouting || sentence.endsWith("!") -> { pitch *= 1.12f; rate *= 1.08f }
            sentence.endsWith("?") -> pitch *= 1.06f
            sentence.endsWith("...") || sentence.endsWith("…") -> { pitch *= 0.95f; rate *= 0.92f }
        }
        val spoken = if (shouting) sentence.lowercase(TURKISH).replaceFirstChar { it.titlecase(TURKISH) } else sentence
        if (voice != null && voice != tts.voice) runCatching { tts.voice = voice }
        tts.setSpeechRate(rate.coerceIn(0.8f, MAX_PHONE_RATE + 0.1f))
        tts.setPitch(pitch.coerceIn(0.75f, 1.35f))
        val latch = CountDownLatch(1)
        utteranceDone = latch
        tts.speak(spoken, TextToSpeech.QUEUE_ADD, Bundle(), "line-${line.at}")
        latch.await(20, TimeUnit.SECONDS)
    }

    private fun speakerOf(text: String): String? {
        val colon = text.indexOf(':')
        if (colon !in 2..24) return null
        val label = text.substring(0, colon).trim()
        val letters = label.filter { it.isLetter() }
        if (letters.isEmpty() || letters.any { it.isLowerCase() }) return null
        return label.uppercase(Locale.ROOT)
    }

    private fun stripLabel(text: String): String {
        val colon = text.indexOf(": ")
        return (if (colon in 1..24 && speakerOf(text) != null) text.substring(colon + 2) else text).trim()
    }

    fun close() {
        running = false
        synchronized(queueLock) { queue.forEach { it.cancelled = true }; queueLock.notifyAll() }
        utteranceDone?.countDown()
        synth.shutdownNow()
        localSynth.shutdownNow()
        cast.close()
        runCatching { tts.stop(); tts.shutdown() }
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
    }

    private companion object {
        const val TAG = "SubtitleVoice"
        val TURKISH = Locale("tr", "TR")
        const val MAX_PHONE_RATE = 1.75f
        /** Çalan satırın arkasında en çok bu kadar satır bekler; fazlası (en eskisi) atlanır. */
        const val MAX_WAITING = 1
        /** Bu kadar beklemiş satır artık sahneyle uyuşmaz, okunmaz. */
        const val STALE_MS = 5_000L
        /** Yapay zekâ sesi bu sürede başlamazsa satır telefon sesiyle okunur. */
        const val FIRST_AUDIO_TIMEOUT_MS = 3_500L
        const val LOCAL_FIRST_AUDIO_TIMEOUT_MS = 4_000L
        /** Motor ilk kez açılırken (model belleğe yüklenir) ilk satır biraz daha beklenebilir. */
        const val LOCAL_START_TIMEOUT_MS = 20_000L
        val PITCHES = floatArrayOf(1.0f, 0.88f, 1.12f, 0.94f, 1.06f, 0.82f, 1.18f)
    }
}
