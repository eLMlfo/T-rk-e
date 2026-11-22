// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.service.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Telefon içi, internetsiz Türkçe dublaj: Kyutai'nin pocket-tts motoru (MIT, uygulamanın içinde libpockettts.so) ve
 * wite-tech'in Pocket TTS Turkish modeli (CC BY 4.0, ilk kullanımda bir kez indirilir). Motor telefonda yerel bir sunucu
 * olarak çalışır (yalnız 127.0.0.1); her cümle `POST /tts` ile seslendirilir ve ses akış halinde gelir.
 * Masaüstü CouchSubs'ın dublajıyla aynı model ve aynı kurallar (Windows: PocketTtsServer.cs, DubbingModel.cs).
 */
object LocalTts {
    private const val TAG = "LocalTts"
    const val REPO = "wite-tech/pocket-tts-turkish"
    const val REVISION = "fcbd37c74dc127be846face0ff36062e9a939f7b"

    class ModelFile(val name: String, val bytes: Long, val sha256: String)

    val FILES = listOf(
        ModelFile("config.yaml", 1212, "fa60a7799ac09778360ce1aa5c2216fa2ce6f1be06aabb2e0dd4f6ea63a6d3d7"),
        ModelFile("model.safetensors", 438_033_312, "c43b72ed336b64c66adceba8fbbb059e869f753b60de086e3f32bf1b118d5a77"),
        ModelFile("tokenizer.model", 303_502, "a6c3a7ca48ecf3c9254bf72dff58e3f09f2798f49effcbd7debcf6f7a87f3568"),
        ModelFile("voices/female_1.wav", 188_684, "82be41762735f5004cec992badc67813a02206fe53888ba7342a589197850225"),
        ModelFile("voices/female_2.wav", 229_964, "98cd697c9dd6f77f253bd1233675496b6d4882cd9c0ef72434b6a12e06564aa7"),
        ModelFile("voices/female_3.wav", 212_684, "555eab5c0640649c1323e6e51c2fae10a334ac3988e6ba0052822147fd9bc40c"),
        ModelFile("voices/male_1.wav", 235_724, "e6538a1f0ce60d08994c3a733946589d327cf9256b35b77e4155c0baefadc81a"),
        ModelFile("voices/male_2.wav", 195_884, "d5585323c634900b054ea20d1c8c9e39d35516b89dba693eaabf8587143dd6d8"),
        ModelFile("voices/male_3.wav", 237_644, "b5bbbca120837cca1b93c3b8d740e70bcc3f32f53c557af04b207fc69a31545e"),
    )
    val TOTAL_BYTES = FILES.sumOf { it.bytes }

    sealed class State {
        /** Motor bu telefonda yok (32 bit işlemci). */
        object Unsupported : State()
        object NotDownloaded : State()
        data class Downloading(val done: Long, val total: Long) : State()
        object Downloaded : State()
        object Starting : State()
        object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.NotDownloaded)
    val state: StateFlow<State> = _state

    private lateinit var app: Context
    private var process: Process? = null
    private var port = 0
    private var voicePort = 0
    private var voiceServer: ServerSocket? = null
    private val lock = Object()
    @Volatile private var downloading = false

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        _state.value = when {
            !engine().exists() -> State.Unsupported
            modelReady() -> State.Downloaded
            else -> State.NotDownloaded
        }
    }

    private fun engine() = File(app.applicationInfo.nativeLibraryDir, "libpockettts.so")
    private fun dir() = File(app.filesDir, "tts-tr/$REVISION")
    private fun marker() = File(dir(), ".tamam")
    fun modelReady() = ::app.isInitialized && marker().exists()
    val ready get() = _state.value == State.Ready

    /** Modeli indirir (özetleri denetler); arka planda çağrılır. */
    fun download() {
        if (downloading || modelReady()) return
        downloading = true
        try {
            val directory = dir().apply { mkdirs() }
            var done = 0L
            _state.value = State.Downloading(0, TOTAL_BYTES)
            for (file in FILES) {
                val target = File(directory, file.name)
                target.parentFile?.mkdirs()
                if (target.exists() && target.length() == file.bytes && sha256(target) == file.sha256) {
                    done += file.bytes
                    _state.value = State.Downloading(done, TOTAL_BYTES)
                    continue
                }
                val partial = File(target.path + ".part")
                var attempt = 0
                while (true) {
                    try {
                        fetch("https://huggingface.co/$REPO/resolve/$REVISION/${file.name}", partial) { got ->
                            _state.value = State.Downloading(done + got, TOTAL_BYTES)
                        }
                        break
                    } catch (error: IOException) {
                        if (++attempt >= 4) throw error
                        Thread.sleep(2000L * attempt)
                    }
                }
                if (partial.length() != file.bytes || sha256(partial) != file.sha256) {
                    partial.delete()
                    throw IOException("${file.name} bozuk indi, tekrar dene")
                }
                if (!partial.renameTo(target)) throw IOException("${file.name} kaydedilemedi")
                done += file.bytes
            }
            File(directory, "local.yaml").writeText(localConfig(File(directory, "config.yaml").readText(), directory.path))
            marker().writeText("ok")
            _state.value = State.Downloaded
        } catch (error: Throwable) {
            Log.e(TAG, "indirme", error)
            _state.value = State.Failed("İndirme yarıda kaldı: ${error.message ?: error}")
        } finally {
            downloading = false
        }
    }

    /** Yarıda kalan indirme kaldığı yerden sürer (Range). */
    private fun fetch(url: String, target: File, progress: (Long) -> Unit) {
        val have = if (target.exists()) target.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
        try {
            val status = connection.responseCode
            if (status >= 400) throw IOException("HTTP $status")
            val append = have > 0 && status == 206
            var got = if (append) have else 0L
            connection.inputStream.use { input ->
                java.io.FileOutputStream(target, append).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var last = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        got += n
                        if (got - last > 2_000_000) { progress(got); last = got }
                    }
                }
            }
            progress(got)
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Masaüstüyle aynı: yollar telefondaki dosyalara, ilk harf büyütülmez. */
    fun localConfig(original: String, directory: String): String {
        val lines = ArrayList<String>()
        for (line in original.replace("\r\n", "\n").split('\n')) {
            val trimmed = line.trim()
            val indent = line.takeWhile { it == ' ' }
            when {
                trimmed.startsWith("weights_path:") -> lines += "${indent}weights_path: '${directory.replace("'", "''")}/model.safetensors'"
                trimmed.startsWith("tokenizer_path:") -> lines += "${indent}tokenizer_path: '${directory.replace("'", "''")}/tokenizer.model'"
                trimmed.startsWith("capitalize_first_letter:") -> Unit
                else -> lines += line
            }
        }
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return lines.joinToString("\n") + "\ncapitalize_first_letter: false\n"
    }

    /** Motoru başlatır (gerekirse) ve hazır olana kadar bekler; ilk açılış birkaç saniye sürer. */
    fun start(): Boolean {
        synchronized(lock) {
            if (process?.isAlive == true && _state.value == State.Ready) return true
            if (!modelReady() || !engine().exists()) return false
            _state.value = State.Starting
            try {
                stopLocked()
                startVoiceServer()
                port = freePort()
                val directory = dir()
                val builder = ProcessBuilder(
                    engine().path, "serve", "--config", File(directory, "local.yaml").path, "--quantize",
                    "--host", "127.0.0.1", "--port", port.toString(),
                    "--default-voice", File(directory, "voices/male_1.wav").path,
                ).redirectErrorStream(true)
                // İşlemcinin geri kalanı görüntü çözme ve altyazı okumaya kalsın.
                builder.environment()["RAYON_NUM_THREADS"] = "3"
                // libc++_shared.so motorla birlikte uygulamanın kitaplık klasöründe.
                builder.environment()["LD_LIBRARY_PATH"] = app.applicationInfo.nativeLibraryDir
                builder.environment()["HF_HOME"] = File(app.cacheDir, "hf").path
                builder.environment()["HOME"] = app.filesDir.path
                val started = builder.start()
                process = started
                val log = StringBuilder()
                Thread({
                    runCatching {
                        started.inputStream.bufferedReader().forEachLine { line ->
                            Log.i(TAG, line)
                            synchronized(log) { log.append(line).append('\n'); if (log.length > 4000) log.delete(0, log.length - 4000) }
                        }
                    }
                }, "pocket-tts log").apply { isDaemon = true; start() }
                val deadline = System.currentTimeMillis() + 120_000
                while (System.currentTimeMillis() < deadline) {
                    if (!started.isAlive) {
                        val tail = synchronized(log) { log.toString().trim().lines().takeLast(3).joinToString(" | ") }
                        _state.value = State.Failed("Ses motoru kapandı (${started.exitValue()}): $tail")
                        return false
                    }
                    if (health()) {
                        _state.value = State.Ready
                        return true
                    }
                    Thread.sleep(300)
                }
                _state.value = State.Failed("Ses motoru 2 dakikada açılmadı")
                stopLocked()
                return false
            } catch (error: Throwable) {
                Log.e(TAG, "başlatma", error)
                _state.value = State.Failed("Ses motoru başlamadı: ${error.message ?: error}")
                return false
            }
        }
    }

    private fun health(): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
        c.connectTimeout = 500; c.readTimeout = 1000
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    /**
     * [voice]: female_1…male_3. Ses 24 kHz mono 16 bit PCM olarak üretildikçe [onPcm]'e gelir (WAV başlığı atlanır).
     */
    fun stream(text: String, voice: String, cancelled: () -> Boolean, onPcm: (ByteArray) -> Unit) {
        if (!start()) throw IOException("ses motoru hazır değil")
        val body = "text=" + URLEncoder.encode(text, "UTF-8") +
            "&voice_url=" + URLEncoder.encode("http://127.0.0.1:$voicePort/$voice.wav", "UTF-8")
        val connection = URL("http://127.0.0.1:$port/tts").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 2_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status != 200) {
                val error = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty().take(200)
                throw IOException("ses motoru $status: $error")
            }
            connection.inputStream.use { input ->
                var header = 44
                val buffer = ByteArray(8192)
                while (!cancelled()) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    var from = 0
                    if (header > 0) { val skip = minOf(header, n); header -= skip; from = skip }
                    if (n > from) onPcm(buffer.copyOfRange(from, n))
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Ses kayıtlarını motora yerel adresle verir (motor sesi bir kez hazırlayıp saklar). */
    private fun startVoiceServer() {
        if (voiceServer?.isClosed == false) return
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        voiceServer = server
        voicePort = server.localPort
        Thread({
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread({ serveVoice(socket) }, "voice file").apply { isDaemon = true; start() }
            }
        }, "voice server").apply { isDaemon = true; start() }
    }

    private fun serveVoice(socket: Socket) = socket.use {
        runCatching {
            val request = it.getInputStream().bufferedReader().readLine().orEmpty()
            val name = request.split(' ').getOrNull(1).orEmpty().trimStart('/')
            val file = File(dir(), "voices/" + name.substringAfterLast('/'))
            val out = it.getOutputStream()
            if (!name.endsWith(".wav") || !file.exists()) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                val bytes = file.readBytes()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(bytes)
            }
            out.flush()
        }
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        process?.let { runCatching { it.destroy() } }
        process = null
        if (_state.value == State.Ready || _state.value == State.Starting) _state.value = if (modelReady()) State.Downloaded else State.NotDownloaded
    }

    fun describe(state: State): String = when (state) {
        State.Unsupported -> "Bu telefonda çalışmıyor (64 bit işlemci gerekli)"
        State.NotDownloaded -> "İndir (${TOTAL_BYTES / 1_000_000} MB, bir kez, Wi-Fi önerilir)"
        is State.Downloading -> "İndiriliyor %${state.done * 100 / maxOf(1, state.total)} (${state.done / 1_000_000}/${state.total / 1_000_000} MB)"
        State.Downloaded -> "İndirildi, çeviri başlayınca açılır"
        State.Starting -> "Açılıyor…"
        State.Ready -> "Hazır ✅"
        is State.Failed -> state.message + " (tekrar denemek için dokun)"
    }

    /** Altı hazır ses: cinsiyete göre. */
    val FEMALE_VOICES = listOf("female_1", "female_2", "female_3")
    val MALE_VOICES = listOf("male_1", "male_2", "male_3")

    fun lowercase(name: String) = name.lowercase(Locale.ROOT)
}
