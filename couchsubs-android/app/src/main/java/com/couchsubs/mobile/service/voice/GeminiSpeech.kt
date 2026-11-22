// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.service.voice

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * Gemini TTS (gemini-3.8-flash-tts / -lite-tts) ile akışlı Türkçe seslendirme. Ses, üretildikçe 24 kHz mono 16 bit
 * ham PCM parçaları olarak gelir ([onPcm]); ilk parça gelir gelmez çalınabilir, gecikme kısalır.
 *
 * Duygu `speech_metadata.style` ile verilir: oyundaki İngilizce asıl replik, karakter ve sahne bağlamı. Model asıl
 * repliğin duygusunu (öfke, korku, alay, fısıltı…) Türkçe okumaya taşır.
 */
object GeminiSpeech {
    const val SAMPLE_RATE = 24_000
    const val MODEL = "gemini-3.8-flash-tts"
    const val MODEL_FAST = "gemini-3.8-flash-lite-tts"

    /** Ücretsiz katman sınırı ya da kota: bir süre telefon sesine dönülür. */
    class RateLimited(message: String) : IOException(message)
    class Rejected(val status: Int, message: String) : IOException(message)

    fun stream(
        key: String, model: String, voice: String, text: String, style: String,
        cancelled: () -> Boolean, onPcm: (ByteArray) -> Unit,
    ) {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(
                JSONObject().put("text", text).put("speech_metadata", JSONObject().put("style", style))))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("voice", voice))))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:streamGenerateContent?alt=sse"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 6_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("x-goog-api-key", key.trim())
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 429) throw RateLimited(readError(connection))
            if (status >= 400) throw Rejected(status, readError(connection))
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (cancelled()) return
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val json = line.removePrefix("data:").trim()
                    if (json.isEmpty() || json == "[DONE]") continue
                    for (pcm in audioParts(JSONObject(json))) onPcm(pcm)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun audioParts(root: JSONObject): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return out
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data") ?: continue
            val data = inline.optString("data")
            if (data.isEmpty()) continue
            var bytes = Base64.getDecoder().decode(data)
            // Akışta başlıksız PCM gelir; yine de WAV başlığı gelirse atlanır.
            if (bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) bytes = bytes.copyOfRange(44, bytes.size)
            out += bytes
        }
        return out
    }

    private fun readError(connection: HttpURLConnection): String =
        runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(300) }.getOrNull().orEmpty()

    /** Karakterin cinsiyeti (sesi seçmek için), kısa bir metin isteğiyle: "M" ya da "F"; bilinemezse null. */
    fun guessGender(key: String, name: String, game: String?): String? = runCatching {
        val prompt = "In the video game ${game ?: "(unknown game)"}, is the character called \"$name\" male or female? " +
            "If unsure, guess from the name. Answer with exactly one letter: M or F."
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 5))
        val connection = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("x-goog-api-key", key.trim())
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode >= 400) return null
            val text = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text").orEmpty().trim().uppercase()
            when {
                text.startsWith("F") -> "F"
                text.startsWith("M") -> "M"
                else -> null
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
